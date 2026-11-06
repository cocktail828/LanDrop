package io.github.landrop.discovery

import android.content.Context
import androidx.annotation.StringRes
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import io.github.landrop.model.DeviceInfo
import io.github.landrop.model.RegisterResponse
import io.github.landrop.model.RemoteDevice
import io.github.landrop.protocol.LocalSendProtocol
import io.github.landrop.server.LocalSendServer
import io.github.landrop.R
import io.github.landrop.settings.AppLocale
import io.github.landrop.settings.AppSettings
import io.github.landrop.transfer.IncomingTransferManager
import io.github.landrop.transfer.UploadClient
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground implementation of LocalSend v2 UDP multicast discovery. */
class DiscoveryManager(
    context: Context,
    private val listener: DiscoveryListener
) {

    private val appContext = context.applicationContext
    private fun text(@StringRes resource: Int, vararg arguments: Any): String = AppLocale.text(appContext, resource, *arguments)
    private val gson = Gson()
    private val identity = LocalIdentity(appContext)
    private val settings = AppSettings(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val devices = ConcurrentHashMap<String, RemoteDevice>()
    private val running = AtomicBoolean(false)
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()
    private val peers by lazy { io.github.landrop.security.TlsPeerClient(
        io.github.landrop.security.LocalTlsIdentity.load(appContext), httpClient) }
    @Volatile private var executor: ExecutorService? = null
    @Volatile private var legacyScanExecutor: ExecutorService? = null
    @Volatile private var multicastReceiveExecutor: ExecutorService? = null
    @Volatile private var multicastSockets: List<MulticastSocket> = emptyList()
    @Volatile private var servers: List<LocalSendServer> = emptyList()
    @Volatile private var incomingTransfers: IncomingTransferManager? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var legacyProcessNetworkBound = false

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val worker = Executors.newFixedThreadPool(2)
        executor = worker
        legacyScanExecutor = Executors.newFixedThreadPool(LEGACY_SCAN_PARALLELISM)
        worker.execute { runDiscovery() }
    }

    fun announce() {
        val worker = executor ?: return
        try {
            worker.execute {
                if (running.get()) sendAnnouncement(announce = true)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // A concurrent network change stopped this manager. Its replacement
            // announces the latest saved identity when the network is ready.
        }
    }

    /** Clear the current peer snapshot before asking the network for fresh announcements. */
    fun refresh() {
        devices.clear()
        publishDevices()
        announce()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        multicastSockets.forEach { it.close() }
        multicastSockets = emptyList()
        multicastReceiveExecutor?.shutdownNow()
        multicastReceiveExecutor = null
        if (legacyProcessNetworkBound) {
            // setProcessDefaultNetwork is the API 21-compatible counterpart to socket binding.
            ConnectivityManager.setProcessDefaultNetwork(null)
            legacyProcessNetworkBound = false
        }
        servers.forEach { it.stop() }
        servers = emptyList()
        multicastLock?.let { lock -> if (lock.isHeld) lock.release() }
        multicastLock = null
        executor?.shutdownNow()
        executor = null
        legacyScanExecutor?.shutdownNow()
        legacyScanExecutor = null
    }

    private fun runDiscovery() {
        try {
            acquireMulticastLock()
            val selectedInterfaces = selectedInterfaces()
            if (selectedInterfaces.isEmpty()) {
                throw IOException(text(R.string.discovery_no_interface))
            }
            val transferManager = IncomingTransferManager(
                appContext,
                onTransferRequested = { request, decide ->
                    mainHandler.post { listener.onIncomingTransferRequest(request, decide) }
                },
                onSessionPrepared = { sessionId, request ->
                    mainHandler.post { listener.onIncomingSessionPrepared(sessionId, request) }
                },
                onTransferCancelRequested = { info, address, sessionId ->
                    cancelRemoteTransfer(info.protocol, address, info.port, info.fingerprint, sessionId)
                },
                onFileProgress = { sessionId, fileId, fileName, received, total ->
                    mainHandler.post { listener.onFileReceiveProgress(sessionId, fileId, fileName, received, total) }
                },
                onFileReceiveCancelled = { sessionId, fileId, fileName, sessionComplete ->
                    mainHandler.post { listener.onFileReceiveCancelled(sessionId, fileId, fileName, sessionComplete) }
                },
                onFileReceived = { sessionId, fileId, file, sessionComplete ->
                    mainHandler.post { listener.onFileReceived(sessionId, fileId, file, sessionComplete) }
                },
                onPreparationCancelled = { request ->
                    mainHandler.post { listener.onIncomingPreparationCancelled(request) }
                },
                onFileReceiveFailed = { sessionId, fileId, fileName, complete ->
                    mainHandler.post { listener.onFileReceiveFailed(sessionId, fileId, fileName, complete) }
                }
            )
            incomingTransfers = transferManager
            val startedServers = ArrayList<LocalSendServer>()
            selectedInterfaces.flatMap { it.addresses }.forEach { address ->
                try {
                    LocalSendServer(
                        gson,
                        identity::deviceInfo,
                        ::registerDevice,
                        transferManager,
                        settings.port(),
                        bindAddress = address,
                        receivePin = settings::receivePin,
                        onTransferCancelled = UploadClient::cancel
                    ).also { localServer ->
                        localServer.start(SOCKET_READ_TIMEOUT_MS, false)
                        startedServers += localServer
                        Log.i(TAG, "Listening for LocalSend HTTP on $address:${settings.port()}")
                    }
                } catch (exception: Exception) {
                    Log.w(TAG, "Unable to listen on $address:${settings.port()}", exception)
                }
            }
            if (startedServers.isEmpty()) throw IOException(text(R.string.discovery_listen_failed))
            servers = startedServers

            val transports = selectedInterfaces.mapNotNull { createTransport(it) }
            prepareProcessNetworkBinding(transports)
            val sockets = transports.mapNotNull { transport ->
                try {
                    createMulticastSocket(transport).also {
                        Log.i(TAG, "Listening for LocalSend discovery on ${transport.interfaceInfo.name}")
                    }
                } catch (exception: Exception) {
                    Log.w(TAG, "Unable to listen for multicast on ${transport.interfaceInfo.name}", exception)
                    null
                }
            }
            multicastSockets = sockets
            multicastReceiveExecutor = Executors.newFixedThreadPool(sockets.size.coerceAtLeast(1))
            sockets.forEach { multicastReceiveExecutor?.execute { receiveAnnouncements(it) } }
            sendAnnouncement(announce = true)
            scheduleMulticastRetries()
            scheduleStagedDiscovery()
            while (running.get()) {
                try {
                    Thread.sleep(500L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to start LocalSend discovery", exception)
            reportError(text(R.string.discovery_start_failed, exception.message ?: text(R.string.discovery_network_error)))
        } finally {
            stop()
        }
    }

    fun cancelIncomingTransfer(): Boolean = incomingTransfers?.cancelCurrent() == true

    fun cancelIncomingSession(sessionId: String): Boolean = incomingTransfers?.cancelSession(sessionId) == true

    fun cancelAllIncomingTransfers(): Boolean = incomingTransfers?.cancelAll() == true

    fun cancelIncomingFile(sessionId: String, fileId: String): Boolean = incomingTransfers?.cancelFile(sessionId, fileId) == true

    private fun cancelRemoteTransfer(protocol: String, address: String, port: Int, fingerprint: String, sessionId: String) {
        if (protocol !in SUPPORTED_PROTOCOLS) return
        try {
            val url = DeviceEndpoint(address, port).url(protocol, LocalSendProtocol.CANCEL_PATH) + "?sessionId=$sessionId"
            val request = Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()
            peers.client(protocol, fingerprint).newCall(request).execute().use { }
        } catch (_: Exception) { }
    }

    private fun receiveAnnouncements(multicastSocket: MulticastSocket) {
        val buffer = ByteArray(MAX_PACKET_SIZE)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                multicastSocket.receive(packet)
                val message = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val device = gson.fromJson(message, DeviceInfo::class.java) ?: continue
                processAnnouncement(device, packet.address.hostAddress ?: continue)
            } catch (exception: IOException) {
                if (running.get()) reportError(text(R.string.discovery_connection_lost))
                return
            } catch (exception: Exception) {
                // Ignore malformed packets from unrelated services on the multicast address.
            }
        }
    }

    private fun processAnnouncement(device: DeviceInfo, address: String) {
        if (!isCompatible(device) || device.fingerprint == identity.deviceInfo().fingerprint) return
        // v2.2 announcements omit the old announce flag; both require unicast registration.
        val result = postRegistration(address, device.port, device.protocol, device.fingerprint)
        if (result != null) registerDevice(device, address)
        else if (device.announce == true) sendAnnouncement(announce = false)
    }

    private fun postRegistration(address: String, port: Int, protocol: String, expectedFingerprint: String? = null): RegistrationResult? {
        if (protocol !in SUPPORTED_PROTOCOLS) return null
        val requestBody = gson.toJson(identity.deviceInfo()).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(DeviceEndpoint(address, port).url(protocol, LocalSendProtocol.REGISTER_PATH))
            .post(requestBody)
            .build()
        return try {
            peers.client(protocol, expectedFingerprint).newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = gson.fromJson(response.body?.charStream(), RegisterResponse::class.java) ?: return null
                val peerFingerprint = body.fingerprint?.takeIf { it.isNotBlank() } ?: return null
                if (expectedFingerprint != null && !expectedFingerprint.equals(peerFingerprint, ignoreCase = true)) return null
                if (protocol == "https") {
                    val cert = response.handshake?.peerCertificates?.firstOrNull() as? java.security.cert.X509Certificate ?: return null
                    if (!io.github.landrop.security.certificateFingerprint(cert).equals(peerFingerprint, ignoreCase = true)) return null
                }
                RegistrationResult(body, peerFingerprint)
            }
        } catch (exception: Exception) {
            null
        }
    }

    /** Give multicast discovery time to respond before the HTTP subnet scan. */
    private fun scheduleStagedDiscovery() {
        legacyScanExecutor?.execute {
            try {
                Thread.sleep(LEGACY_SCAN_DELAY_MS)
                if (running.get() && devices.isEmpty()) scanLocalNetwork()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Match LocalSend's startup announcement burst: immediately (at startup), then after
     * roughly 500 ms and 2 s. This avoids missing peers that are still joining the group.
     */
    private fun scheduleMulticastRetries() {
        executor?.execute {
            for (delay in MULTICAST_RETRY_DELAYS_MS) {
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                }
                if (!running.get() || devices.isNotEmpty()) return@execute
                Log.d(TAG, "Retrying LocalSend multicast announcement")
                sendAnnouncement(announce = true)
            }
        }
    }

    /** HTTP legacy-mode fallback for selected interfaces that suppress multicast packets. */
    private fun scanLocalNetwork() {
        val selectedInterfaces = selectedInterfaces()
        if (selectedInterfaces.isEmpty()) {
            reportError(text(R.string.discovery_no_interface))
            return
        }
        selectedInterfaces.flatMap { it.ipv4Addresses }.forEach { localAddress ->
            val effectivePrefix = maxOf(localAddress.prefixLength, MINIMUM_SCAN_PREFIX)
            val hostBits = 32 - effectivePrefix
            if (hostBits <= 1) return@forEach
            val networkAddress = ipv4ToInt(localAddress.address) and (-1 shl hostBits)
            val addressCount = 1 shl hostBits
            Log.i(TAG, "Starting HTTP legacy scan on ${localAddress.address.hostAddress} with ${addressCount - 2} hosts")
            orderedHostOffsets(networkAddress, addressCount, localAddress.address).forEach { offset ->
                legacyScanExecutor?.execute { scanAddress(intToIpv4(networkAddress + offset)) }
            }
        }
    }

    /** DHCP leases are usually adjacent, so check Wi-Fi neighbours before the rest of the subnet. */
    private fun orderedHostOffsets(networkAddress: Int, addressCount: Int, localAddress: Inet4Address): List<Int> {
        val localOffset = ipv4ToInt(localAddress) - networkAddress
        val offsets = ArrayList<Int>(addressCount - 2)
        for (distance in 1 until addressCount) {
            val lower = localOffset - distance
            if (lower > 0) offsets += lower
            val upper = localOffset + distance
            if (upper < addressCount - 1) offsets += upper
        }
        return offsets
    }

    private fun scanAddress(address: String) {
        if (!running.get()) return
        val secure = postRegistration(address, settings.port(), "https")
        val protocol = if (secure != null) "https" else "http"
        val response = secure ?: postRegistration(address, settings.port(), protocol) ?: return
        val alias = response.body.alias ?: return
        val version = response.body.version ?: return
        val fingerprint = response.peerFingerprint ?: response.body.fingerprint ?: return
        registerDevice(
            DeviceInfo(
                alias = alias,
                version = version,
                deviceModel = response.body.deviceModel,
                deviceType = response.body.deviceType,
                fingerprint = fingerprint,
                port = settings.port(),
                protocol = protocol,
                download = response.body.download
            ),
            address
        )
    }

    private fun sendAnnouncement(announce: Boolean) {
        if (multicastSockets.isEmpty()) return
        val message = gson.toJson(identity.deviceInfo().copy(announce = announce))
        val data = message.toByteArray(Charsets.UTF_8)
        multicastSockets.forEach { multicastSocket ->
            try {
                multicastSocket.send(
                    DatagramPacket(
                        data,
                        data.size,
                        InetAddress.getByName(settings.multicastAddress()),
                        settings.port()
                    )
                )
                Log.d(TAG, "Sent LocalSend multicast announcement on ${multicastSocket.networkInterface.name}")
            } catch (exception: IOException) {
                Log.w(TAG, "Unable to send device announcement on ${multicastSocket.networkInterface.name}", exception)
            }
        }
    }

    private fun registerDevice(device: DeviceInfo, address: String) {
        if (!isCompatible(device) || device.fingerprint == identity.deviceInfo().fingerprint) return
        val key = device.fingerprint.ifBlank { "$address:${device.port}" }
        devices[key] = RemoteDevice(
            alias = device.alias,
            deviceModel = device.deviceModel,
            deviceType = device.deviceType,
            fingerprint = device.fingerprint,
            address = address,
            port = device.port,
            protocol = device.protocol,
            downloadEnabled = device.download
        )
        publishDevices()
    }

    private fun isCompatible(device: DeviceInfo): Boolean =
        device.version.substringBefore('.') == LocalSendProtocol.VERSION.substringBefore('.') &&
            device.port in 1..65535 &&
            device.protocol in SUPPORTED_PROTOCOLS

    private fun publishDevices() {
        val discovered = devices.values.sortedBy { it.alias.lowercase() }
        mainHandler.post { listener.onDevicesChanged(discovered) }
    }

    private fun reportError(message: String) {
        mainHandler.post { listener.onDiscoveryError(message) }
    }

    private fun acquireMulticastLock() {
        val wifiManager = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifiManager.createMulticastLock("localsend-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /** Creates one IPv4 multicast socket for a selected interface. */
    @Throws(IOException::class)
    private fun createMulticastSocket(transport: InterfaceTransport): MulticastSocket {
        val group = InetAddress.getByName(settings.multicastAddress())
        return MulticastSocket(null).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                transport.network?.bindSocket(this)
            }
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), settings.port()))
            networkInterface = transport.networkInterface
            timeToLive = 1
            loopbackMode = false
            joinGroup(
                InetSocketAddress(group, settings.port()),
                transport.networkInterface
            )
        }
    }

    private fun selectedInterfaces(): List<LocalNetworkInterface> {
        val available = NetworkInterfaceCatalog.list()
        val selected = NetworkInterfaceCatalog.resolveSelection(
            available,
            null,
            NetworkInterfaceCatalog.defaultSelection(appContext, available)
        )
        return available.filter { it.name in selected }
    }

    private fun createTransport(interfaceInfo: LocalNetworkInterface): InterfaceTransport? {
        val networkInterface = NetworkInterface.getByName(interfaceInfo.name) ?: return null
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: throw IOException(text(R.string.discovery_connectivity_unavailable))
        val network = connectivityManager.allNetworks.firstOrNull { candidate ->
            connectivityManager.getLinkProperties(candidate)?.interfaceName == interfaceInfo.name
        }
        if (!networkInterface.isUp || !networkInterface.supportsMulticast()) return null
        return InterfaceTransport(interfaceInfo, network, networkInterface)
    }

    private fun prepareProcessNetworkBinding(transports: List<InterfaceTransport>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 || transports.size != 1) return
        val network = transports.first().network ?: return
        if (!ConnectivityManager.setProcessDefaultNetwork(network)) {
            throw IOException(text(R.string.discovery_bind_failed))
        }
        legacyProcessNetworkBound = true
    }

    private fun ipv4ToInt(address: Inet4Address): Int {
        val bytes = address.address
        return ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)
    }

    private fun intToIpv4(value: Int): String = listOf(
        value ushr 24,
        (value ushr 16) and 0xff,
        (value ushr 8) and 0xff,
        value and 0xff
    ).joinToString(".")

    private companion object {
        const val TAG = "LocalSendDiscovery"
        const val MAX_PACKET_SIZE = 8 * 1024
        const val SOCKET_READ_TIMEOUT_MS = 60_000
        // The HTTP legacy scan is a fallback and must not race the multicast announcement burst.
        const val LEGACY_SCAN_DELAY_MS = 2_500L
        const val LEGACY_SCAN_PARALLELISM = 24
        val MULTICAST_RETRY_DELAYS_MS = longArrayOf(500L, 1_500L)
        const val MINIMUM_SCAN_PREFIX = 24
        val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()
        val SUPPORTED_PROTOCOLS = setOf("http", "https")
    }

    private data class InterfaceTransport(
        val interfaceInfo: LocalNetworkInterface,
        val network: Network?,
        val networkInterface: NetworkInterface
    )

    private data class RegistrationResult(
        val body: RegisterResponse,
        val peerFingerprint: String?
    )
}
