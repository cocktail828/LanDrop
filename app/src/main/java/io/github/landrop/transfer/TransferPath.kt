package io.github.landrop.transfer

/** Relative wire paths are preserved, but may never escape the authorized destination. */
internal object TransferPath {
    fun segments(path: String): List<String> {
        require(path.isNotEmpty() && path.length <= 4096 && '\u0000' !in path) { "Invalid file path" }
        val normalized = path.replace('\\', '/')
        require(!normalized.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) { "Absolute file path" }
        return normalized.split('/').also { parts ->
            require(parts.size <= 64 && parts.all { it.isNotEmpty() && it != "." && it != ".." }) { "Invalid relative file path" }
        }
    }
}
