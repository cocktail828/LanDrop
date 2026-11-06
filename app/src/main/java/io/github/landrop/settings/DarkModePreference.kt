package io.github.landrop.settings

/** How the app chooses between its light and dark palettes. */
enum class DarkModePreference(
    val id: String
) {
    FOLLOW_SYSTEM("follow_system"),
    ENABLED("enabled"),
    DISABLED("disabled");

    companion object {
        fun fromId(id: String?): DarkModePreference =
            values().firstOrNull { it.id == id } ?: FOLLOW_SYSTEM
    }
}
