package com.bluemessenger.crypto.dbus

import com.bluemessenger.crypto.constants.SecretServiceConstants
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.Variant

/**
 * Freedesktop Secret Service `org.freedesktop.Secret.Service` D-Bus interface.
 */
@DBusInterfaceName(SecretServiceConstants.SERVICE_INTERFACE)
internal interface SecretServiceDbus : DBusInterface {
    @DBusMemberName("OpenSession")
    fun openSession(
        algorithm: String,
        input: Variant<*>,
    ): OpenSessionResult

    @DBusMemberName("ReadAlias")
    fun readAlias(name: String): DBusPath

    @DBusMemberName("SearchItems")
    fun searchItems(attributes: Map<String, String>): SearchItemsResult

    @DBusMemberName("Unlock")
    fun unlock(objects: List<DBusPath>): UnlockResult
}

/**
 * Freedesktop Secret Service `org.freedesktop.Secret.Session` D-Bus interface.
 */
@DBusInterfaceName(SecretServiceConstants.SESSION_INTERFACE)
internal interface SecretSessionDbus : DBusInterface {
    @DBusMemberName("Close")
    fun close()
}

/**
 * Freedesktop Secret Service `org.freedesktop.Secret.Collection` D-Bus interface.
 */
@DBusInterfaceName(SecretServiceConstants.COLLECTION_INTERFACE)
internal interface SecretCollectionDbus : DBusInterface {
    @DBusMemberName("CreateItem")
    fun createItem(
        properties: Map<String, Variant<*>>,
        secret: SecretStruct,
        replace: Boolean,
    ): CreateItemResult
}

/**
 * Freedesktop Secret Service `org.freedesktop.Secret.Item` D-Bus interface.
 */
@DBusInterfaceName(SecretServiceConstants.ITEM_INTERFACE)
internal interface SecretItemDbus : DBusInterface {
    @DBusMemberName("GetSecret")
    fun getSecret(session: DBusPath): SecretStruct

    @DBusMemberName("Delete")
    fun delete(): DBusPath
}

/**
 * D-Bus `(oayays)` struct carrying an encoded secret payload bound to a Secret Service session.
 */
internal class SecretStruct(
    @field:Position(SecretServiceConstants.POSITION_FIRST)
    val session: DBusPath,
    @field:Position(SecretServiceConstants.POSITION_SECOND)
    val parameters: ByteArray,
    @field:Position(SecretServiceConstants.POSITION_THIRD)
    val value: ByteArray,
    @field:Position(SecretServiceConstants.POSITION_FOURTH)
    val contentType: String,
) : Struct()

/**
 * Multi-value return tuple for `org.freedesktop.Secret.Service.OpenSession`.
 */
internal class OpenSessionResult(
    @field:Position(SecretServiceConstants.POSITION_FIRST)
    val output: Variant<*>,
    @field:Position(SecretServiceConstants.POSITION_SECOND)
    val sessionPath: DBusPath,
) : Tuple()

/**
 * Multi-value return tuple for `org.freedesktop.Secret.Service.SearchItems`.
 */
internal class SearchItemsResult(
    @field:Position(SecretServiceConstants.POSITION_FIRST)
    val unlocked: List<DBusPath>,
    @field:Position(SecretServiceConstants.POSITION_SECOND)
    val locked: List<DBusPath>,
) : Tuple()

/**
 * Multi-value return tuple for `org.freedesktop.Secret.Service.Unlock`.
 */
internal class UnlockResult(
    @field:Position(SecretServiceConstants.POSITION_FIRST)
    val unlocked: List<DBusPath>,
    @field:Position(SecretServiceConstants.POSITION_SECOND)
    val prompt: DBusPath,
) : Tuple()

/**
 * Multi-value return tuple for `org.freedesktop.Secret.Collection.CreateItem`.
 */
internal class CreateItemResult(
    @field:Position(SecretServiceConstants.POSITION_FIRST)
    val item: DBusPath,
    @field:Position(SecretServiceConstants.POSITION_SECOND)
    val prompt: DBusPath,
) : Tuple()
