package io.github.landrop.settings

/** Device type values defined by the LocalSend protocol. */
enum class DeviceType(val value: String) {
    MOBILE("mobile"),
    DESKTOP("desktop"),
    WEB("web"),
    HEADLESS("headless"),
    SERVER("server");

    companion object {
        fun fromValue(value: String?): DeviceType =
            entries.firstOrNull { it.value == value } ?: MOBILE
    }
}
