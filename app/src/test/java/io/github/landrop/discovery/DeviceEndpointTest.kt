package io.github.landrop.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceEndpointTest {
    @Test fun formatsIpv4RegistrationUrl() {
        assertEquals("http://192.168.1.10:53317/api/localsend/v2/register",
            DeviceEndpoint("192.168.1.10", 53317).url("http", "/api/localsend/v2/register"))
    }
    @Test fun bracketsIpv6ForRemoteCancellation() {
        assertEquals("http://[fe80::1234]:53317/api/localsend/v2/cancel",
            DeviceEndpoint("fe80::1234", 53317).url("http", "/api/localsend/v2/cancel"))
    }
    @Test fun formatsHttpsPeerUrl() {
        assertEquals("https://192.168.1.10:53317/api/localsend/v2/register",
            DeviceEndpoint("192.168.1.10", 53317).url("https", "/api/localsend/v2/register"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownTransport() {
        DeviceEndpoint("192.168.1.10", 53317).url("ftp", "/api/localsend/v2/register")
    }
}
