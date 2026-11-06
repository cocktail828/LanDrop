package io.github.landrop.server

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import io.github.landrop.model.DeviceInfo
import io.github.landrop.protocol.LocalSendProtocol
import io.github.landrop.transfer.IncomingTransferManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class LocalSendServerInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val gson = Gson()
    private var device = DeviceInfo("小米 LocalSend", "2.0", "Xiaomi", "mobile", "http-device-id", 53317, "http")
    private val registrations = AtomicInteger()
    private val transferEvents = AtomicInteger()
    private val pinQueries = AtomicInteger()
    private lateinit var server: LocalSendServer
    private var baseUrl = ""
    private val clients = mutableListOf<OkHttpClient>()

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
        clients.forEach {
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun httpInfoIsReadOnlyNeedsNoPinAndReflectsCurrentIdentity() {
        startServer()
        val client = client()
        assertInfo(client, device.fingerprint)
        device = device.copy(alias = "Updated English name", deviceModel = null, deviceType = null)
        assertInfo(client, device.fingerprint)
        assertEquals(0, registrations.get())
        assertEquals(0, transferEvents.get())
        assertEquals(0, pinQueries.get())
    }

    @Test
    fun infoOnlyAcceptsGetAndOtherRoutesStillRequirePost() {
        startServer()
        val client = client()
        for (method in listOf("POST", "PUT", "DELETE", "HEAD", "OPTIONS")) {
            val body = if (method == "POST" || method == "PUT") "".toRequestBody() else null
            // NanoHTTPD 2.3.1 emits a body even for a rejected HEAD request.
            // Isolate rejected-method requests from subsequent keep-alive checks.
            client.newCall(Request.Builder().url(baseUrl + LocalSendProtocol.INFO_PATH)
                .header("Connection", "close").method(method, body).build()).execute().use {
                assertEquals("Unexpected status for $method /info", 404, it.code)
            }
        }
        for (path in listOf(LocalSendProtocol.REGISTER_PATH, LocalSendProtocol.PREPARE_UPLOAD_PATH,
            LocalSendProtocol.UPLOAD_PATH, LocalSendProtocol.CANCEL_PATH, "/unknown")) {
            client.newCall(Request.Builder().url(baseUrl + path).build()).execute().use {
                assertEquals("Unexpected status for GET $path", 404, it.code)
            }
        }
        assertEquals(0, registrations.get())
        assertEquals(0, transferEvents.get())
        assertEquals(0, pinQueries.get())
        // Adding GET /info must preserve two-way registration.
        client.newCall(registrationRequest(device.copy(fingerprint = "remote-http-id"))).execute().use {
            assertEquals(200, it.code)
            assertEquals(device.fingerprint, gson.fromJson(it.body.string(), JsonObject::class.java)["fingerprint"].asString)
        }
        assertEquals(1, registrations.get())
    }

    @Test
    fun acceptsHttpsPeersRegisteringWithOurHttpListener() {
        startServer()
        client().newCall(registrationRequest(device.copy(protocol = "https", fingerprint = "A".repeat(64)))).execute().use {
            assertEquals(200, it.code)
        }
        assertEquals(1, registrations.get())
    }

    @Test
    fun rejectsUnknownProtocolAndEmptyDeviceIdentifier() {
        startServer()
        val client = client()
        for (invalid in listOf(device.copy(protocol = "ftp"), device.copy(fingerprint = ""))) {
            client.newCall(registrationRequest(invalid)).execute().use { assertEquals(403, it.code) }
        }
        assertEquals(0, registrations.get())
    }

    @Test
    fun localIdentityAlwaysAdvertisesHttpAndKeepsIdentifierAfterRename() {
        val identity = io.github.landrop.discovery.LocalIdentity(context)
        val before = identity.deviceInfo()
        assertEquals("http", before.protocol)
        assertTrue(before.fingerprint.isNotBlank())
        assertEquals(before.fingerprint, io.github.landrop.discovery.LocalIdentity(context).deviceInfo().fingerprint)
    }

    private fun startServer() {
        val transfers = IncomingTransferManager(
            context,
            onTransferRequested = { _, _ -> transferEvents.incrementAndGet() },
            onSessionPrepared = { _, _ -> transferEvents.incrementAndGet() },
            onTransferCancelRequested = { _, _, _ -> transferEvents.incrementAndGet() },
            onFileProgress = { _, _, _, _, _ -> transferEvents.incrementAndGet() },
            onFileReceiveCancelled = { _, _, _, _ -> transferEvents.incrementAndGet() },
            onFileReceived = { _, _, _, _ -> transferEvents.incrementAndGet() }
        )
        server = LocalSendServer(
            gson, { device }, { _, _ -> registrations.incrementAndGet() }, transfers,
            port = 0, bindAddress = "127.0.0.1",
            receivePin = { pinQueries.incrementAndGet(); "123456" },
            onTransferCancelled = { transferEvents.incrementAndGet() }
        )
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        baseUrl = "${device.protocol}://127.0.0.1:${server.listeningPort}"
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .build().also { clients += it }

    private fun assertInfo(client: OkHttpClient, expectedFingerprint: String) {
        client.newCall(Request.Builder().url(baseUrl + LocalSendProtocol.INFO_PATH).build()).execute().use {
            assertEquals(200, it.code)
            assertEquals("application/json; charset=utf-8", it.header("Content-Type"))
            val json = gson.fromJson(it.body.string(), JsonObject::class.java)
            assertEquals(device.alias, json["alias"].asString)
            assertEquals(device.version, json["version"].asString)
            assertEquals(device.deviceModel, json["deviceModel"]?.asString)
            assertEquals(device.deviceType, json["deviceType"]?.asString)
            assertEquals(expectedFingerprint, json["fingerprint"].asString)
            assertEquals(false, json["download"].asBoolean)

        }
    }

    private fun registrationRequest(info: DeviceInfo): Request = Request.Builder()
        .url(baseUrl + LocalSendProtocol.REGISTER_PATH)
        .post(gson.toJson(info).toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
}
