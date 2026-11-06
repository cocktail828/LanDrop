package io.github.landrop.discovery

/** Shared address formatting for automatic registration and cancellation. */
internal data class DeviceEndpoint(val host: String, val port: Int) {
    fun url(protocol: String, path: String): String {
        require(protocol == "http" || protocol == "https") { "Unsupported LocalSend protocol" }
        val formattedHost = if (host.contains(':')) "[$host]" else host
        return "$protocol://$formattedHost:$port$path"
    }

}
