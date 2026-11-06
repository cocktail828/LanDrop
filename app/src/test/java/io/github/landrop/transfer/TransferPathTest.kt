package io.github.landrop.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TransferPathTest {
    @Test fun preservesFolderHierarchyAndUnicodeNames() {
        assertEquals(listOf("照片", "旅行", "a.jpg"), TransferPath.segments("照片/旅行/a.jpg"))
        assertEquals(listOf("B", "readme.txt"), TransferPath.segments("B\\readme.txt"))
        assertEquals(listOf(" notes .txt"), TransferPath.segments(" notes .txt"))
    }
    @Test fun rejectsTraversalAbsoluteAndMalformedPaths() {
        listOf("../x", "A/../../x", "/tmp/x", "\\tmp\\x", "C:\\tmp\\x", "A//x", "A/./x", "", "A/", "a\u0000b")
            .forEach { path ->
                try { TransferPath.segments(path); fail("Accepted invalid path: $path") }
                catch (_: IllegalArgumentException) { }
            }
    }
}
