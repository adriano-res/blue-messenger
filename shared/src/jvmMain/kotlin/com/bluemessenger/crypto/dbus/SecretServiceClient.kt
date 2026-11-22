package com.bluemessenger.crypto.dbus

import com.bluemessenger.crypto.constants.SecretServiceConstants
import com.bluemessenger.crypto.util.ByteArrayZeroizer
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.io.Closeable
import java.io.IOException

/**
 * Session D-Bus client for the Linux Freedesktop Secret Service (`org.freedesktop.secrets`) API,
 * backed by `dbus-java`.
 *
 * Thread safety: Connection and session lifecycle are synchronized via an internal lock.
 */
internal open class SecretServiceClient(
    private val connectionProvider: () -> DBusConnection = {
        DBusConnectionBuilder.forSessionBus().withShared(false).build()
    },
) : Closeable {
    private val lock: Any = Any()

    @Volatile
    private var connection: DBusConnection? = null

    @Volatile
    private var activeSessionPath: DBusPath? = null

    /**
     * Returns whether any secret item matching [attributes] exists in the keyring.
     */
    open fun hasSecret(attributes: Map<String, String>): Boolean {
        val (conn, _) = ensureSession()
        val service = serviceProxy(conn)
        val searchResult = service.searchItems(attributes)
        return when {
            searchResult.unlocked.isNotEmpty() -> true
            searchResult.locked.isEmpty() -> false
            else -> unlockOrFail(service, searchResult.locked).isNotEmpty()
        }
    }

    /**
     * Stores or replaces [secret] under [label] and [attributes] in the default Secret Service
     * collection without persisting plaintext bytes on disk.
     */
    open fun storeSecret(
        attributes: Map<String, String>,
        label: String,
        secret: ByteArray,
    ) {
        val (conn, sessionPath) = ensureSession()
        val service = serviceProxy(conn)
        val collectionPath = resolveDefaultUnlockedCollection(conn, service)
        val collection =
            conn.getRemoteObject(
                SecretServiceConstants.BUS_NAME,
                collectionPath.path,
                SecretCollectionDbus::class.java,
            )
        val properties =
            mapOf(
                SecretServiceConstants.PROPERTY_ITEM_LABEL to Variant(label),
                SecretServiceConstants.PROPERTY_ITEM_ATTRIBUTES to
                    Variant(attributes, SecretServiceConstants.DBUS_STRING_MAP_SIGNATURE),
            )
        val secretPayload = secret.copyOf()
        val secretStruct =
            SecretStruct(
                session = sessionPath,
                parameters = ByteArray(0),
                value = secretPayload,
                contentType = SecretServiceConstants.SECRET_CONTENT_TYPE_OCTET_STREAM,
            )
        try {
            val result = collection.createItem(properties, secretStruct, true)
            check(
                result.prompt.path == SecretServiceConstants.ROOT_OBJECT_PATH &&
                    result.item.path != SecretServiceConstants.ROOT_OBJECT_PATH,
            ) {
                "Secret Service requires interactive prompt or refused item creation."
            }
        } finally {
            ByteArrayZeroizer.zeroize(secretPayload)
        }
    }

    /**
     * Reads and returns a defensive copy of the secret matching [attributes], or `null` if absent.
     */
    open fun readSecret(attributes: Map<String, String>): ByteArray? {
        val (conn, sessionPath) = ensureSession()
        val service = serviceProxy(conn)
        val searchResult = service.searchItems(attributes)
        val itemPath =
            searchResult.unlocked.firstOrNull()
                ?: if (searchResult.locked.isNotEmpty()) {
                    unlockOrFail(service, searchResult.locked).firstOrNull()
                } else {
                    null
                }
                ?: return null

        val item =
            conn.getRemoteObject(
                SecretServiceConstants.BUS_NAME,
                itemPath.path,
                SecretItemDbus::class.java,
            )
        val secretStruct = item.getSecret(sessionPath)
        return try {
            secretStruct.value.copyOf()
        } finally {
            ByteArrayZeroizer.zeroize(secretStruct.value)
        }
    }

    /**
     * Deletes any secret items matching [attributes] from the Secret Service keyring.
     */
    open fun deleteSecret(attributes: Map<String, String>) {
        val (conn, _) = ensureSession()
        val service = serviceProxy(conn)
        val searchResult = service.searchItems(attributes)
        val targets =
            buildList {
                addAll(searchResult.unlocked)
                if (searchResult.locked.isNotEmpty()) {
                    addAll(unlockOrFail(service, searchResult.locked))
                }
            }
        for (itemPath in targets) {
            val item =
                conn.getRemoteObject(
                    SecretServiceConstants.BUS_NAME,
                    itemPath.path,
                    SecretItemDbus::class.java,
                )
            val promptPath = item.delete()
            check(promptPath.path == SecretServiceConstants.ROOT_OBJECT_PATH) {
                "Secret Service requires interactive prompt to delete identity key item."
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            val conn = connection
            val sessionPath = activeSessionPath
            activeSessionPath = null
            connection = null
            if (conn != null && sessionPath != null) {
                closeSessionQuietly(conn, sessionPath)
            }
            conn?.disconnect()
        }
    }

    private fun ensureSession(): Pair<DBusConnection, DBusPath> {
        val currentConnection = connection
        val currentSession = activeSessionPath
        if (currentConnection != null && currentSession != null) {
            return currentConnection to currentSession
        }
        return synchronized(lock) {
            val conn =
                connection ?: connectionProvider().also { created ->
                    connection = created
                }
            val session =
                activeSessionPath ?: openPlainSession(conn).also { createdSession ->
                    activeSessionPath = createdSession
                }
            conn to session
        }
    }

    private fun openPlainSession(conn: DBusConnection): DBusPath {
        val result =
            serviceProxy(conn).openSession(
                SecretServiceConstants.SESSION_ALGORITHM_PLAIN,
                Variant(SecretServiceConstants.SESSION_PLAIN_INPUT),
            )
        check(result.sessionPath.path != SecretServiceConstants.ROOT_OBJECT_PATH) {
            "Secret Service returned an empty session object path."
        }
        return result.sessionPath
    }

    private fun resolveDefaultUnlockedCollection(
        conn: DBusConnection,
        service: SecretServiceDbus,
    ): DBusPath {
        val collectionPath = service.readAlias(SecretServiceConstants.DEFAULT_COLLECTION_ALIAS)
        check(collectionPath.path != SecretServiceConstants.ROOT_OBJECT_PATH) {
            "Default Secret Service collection alias is missing."
        }
        val props =
            conn.getRemoteObject(
                SecretServiceConstants.BUS_NAME,
                collectionPath.path,
                Properties::class.java,
            )
        val rawLocked: Any? =
            props.Get<Any?>(
                SecretServiceConstants.COLLECTION_INTERFACE,
                SecretServiceConstants.PROPERTY_COLLECTION_LOCKED,
            )
        val isLocked = (rawLocked as? Variant<*>)?.value as? Boolean ?: (rawLocked as? Boolean ?: false)
        if (isLocked) {
            val unlocked = unlockOrFail(service, listOf(collectionPath))
            check(collectionPath in unlocked) {
                "Default Secret Service collection remains locked."
            }
        }
        return collectionPath
    }

    private fun unlockOrFail(
        service: SecretServiceDbus,
        objects: List<DBusPath>,
    ): List<DBusPath> {
        val unlockResult = service.unlock(objects)
        check(unlockResult.prompt.path == SecretServiceConstants.ROOT_OBJECT_PATH) {
            "Secret Service keyring is locked and requires interactive unlocking."
        }
        check(unlockResult.unlocked.isNotEmpty()) {
            "Secret Service failed to unlock requested keyring objects."
        }
        return unlockResult.unlocked
    }

    private fun serviceProxy(conn: DBusConnection): SecretServiceDbus =
        conn.getRemoteObject(
            SecretServiceConstants.BUS_NAME,
            SecretServiceConstants.SERVICE_PATH,
            SecretServiceDbus::class.java,
        )

    private fun closeSessionQuietly(
        conn: DBusConnection,
        sessionPath: DBusPath,
    ) {
        try {
            conn.getRemoteObject(
                SecretServiceConstants.BUS_NAME,
                sessionPath.path,
                SecretSessionDbus::class.java,
            ).close()
        } catch (_: DBusException) {
        } catch (_: DBusExecutionException) {
        } catch (_: IOException) {
        }
    }
}
