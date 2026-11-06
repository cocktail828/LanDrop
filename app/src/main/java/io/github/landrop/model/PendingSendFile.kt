package io.github.landrop.model

import android.net.Uri

/** A file selected for sending but not yet assigned to a transfer session. */
data class PendingSendFile(
    val uri: Uri,
    val displayName: String,
    val size: Long
)
