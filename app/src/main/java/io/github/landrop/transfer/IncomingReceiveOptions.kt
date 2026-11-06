package io.github.landrop.transfer

import android.net.Uri

/** Snapshot of the destination authorized when the user accepts the request. */
data class IncomingReceiveOptions(val receiveDirectoryUri: Uri?)
