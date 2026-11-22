package com.bluemessenger.crypto.constants

/**
 * D-Bus names, object paths, attribute keys, and failure constants for the Linux Secret Service
 * (`org.freedesktop.secrets`) KeyStore adapter.
 */
internal object SecretServiceConstants {
    const val BUS_NAME: String = "org.freedesktop.secrets"
    const val SERVICE_PATH: String = "/org/freedesktop/secrets"
    const val SERVICE_INTERFACE: String = "org.freedesktop.Secret.Service"
    const val SESSION_INTERFACE: String = "org.freedesktop.Secret.Session"
    const val COLLECTION_INTERFACE: String = "org.freedesktop.Secret.Collection"
    const val ITEM_INTERFACE: String = "org.freedesktop.Secret.Item"

    const val ROOT_OBJECT_PATH: String = "/"
    const val DEFAULT_COLLECTION_ALIAS: String = "default"
    const val SESSION_ALGORITHM_PLAIN: String = "plain"
    const val SESSION_PLAIN_INPUT: String = ""
    const val SECRET_CONTENT_TYPE_OCTET_STREAM: String = "application/octet-stream"

    const val PROPERTY_ITEM_LABEL: String = "org.freedesktop.Secret.Item.Label"
    const val PROPERTY_ITEM_ATTRIBUTES: String = "org.freedesktop.Secret.Item.Attributes"
    const val PROPERTY_COLLECTION_LOCKED: String = "Locked"
    const val DBUS_STRING_MAP_SIGNATURE: String = "a{ss}"

    const val ITEM_LABEL_IDENTITY_KEY: String = "Blue Messenger Local Identity Key"
    const val ATTRIBUTE_APPLICATION_KEY: String = "application"
    const val ATTRIBUTE_APPLICATION_VALUE: String = "blue-messenger"
    const val ATTRIBUTE_KEY_KIND_KEY: String = "key-kind"
    const val ATTRIBUTE_KEY_KIND_VALUE: String = "ed25519-identity"

    const val POSITION_FIRST: Int = 0
    const val POSITION_SECOND: Int = 1
    const val POSITION_THIRD: Int = 2
    const val POSITION_FOURTH: Int = 3

    const val TAG_KEYSTORE: String = "LinuxSecretServiceKeyStoreProviderImpl"
    const val SECRET_SERVICE_UNAVAILABLE_MESSAGE: String =
        "Linux Secret Service (org.freedesktop.secrets) is absent, unavailable, or locked. " +
            "A key service such as GNOME Keyring or KWallet is required to store application " +
            "secrets securely."
}
