package io.github.landrop.discovery

import android.content.Context
import io.github.landrop.model.DeviceInfo
import io.github.landrop.protocol.LocalSendProtocol
import io.github.landrop.settings.AppSettings

class LocalIdentity(private val context: Context) {
    private val settings = AppSettings(context.applicationContext)

    fun deviceInfo(): DeviceInfo = DeviceInfo(
        alias = settings.deviceName(),
        version = LocalSendProtocol.VERSION,
        deviceModel = settings.deviceModel(),
        deviceType = settings.deviceType(),
        fingerprint = io.github.landrop.security.LocalTlsIdentity.load(context).fingerprint,
        port = settings.port(),
        protocol = "http",
        download = false
    )

}
