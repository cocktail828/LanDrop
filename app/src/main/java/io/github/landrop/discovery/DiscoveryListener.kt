package io.github.landrop.discovery

import io.github.landrop.model.RemoteDevice
import io.github.landrop.model.ReceivedFile
import io.github.landrop.transfer.IncomingTransferManager
import io.github.landrop.transfer.IncomingReceiveOptions

/** Boundary between UDP/HTTP discovery and the UI layer. */
interface DiscoveryListener {
    fun onDevicesChanged(devices: List<RemoteDevice>)
    fun onDiscoveryError(message: String)
    fun onIncomingTransferRequest(request: IncomingTransferManager.PrepareUploadRequest, decide: (IncomingReceiveOptions?) -> Unit)
    fun onIncomingPreparationCancelled(request: IncomingTransferManager.PrepareUploadRequest)
    fun onIncomingSessionPrepared(sessionId: String, request: IncomingTransferManager.PrepareUploadRequest)
    fun onFileReceiveProgress(sessionId: String, fileId: String, fileName: String, received: Long, total: Long)
    fun onFileReceiveCancelled(sessionId: String, fileId: String, fileName: String, sessionComplete: Boolean)
    fun onFileReceiveFailed(sessionId: String, fileId: String, fileName: String, sessionComplete: Boolean)
    fun onFileReceived(sessionId: String, fileId: String, file: ReceivedFile, sessionComplete: Boolean)
}
