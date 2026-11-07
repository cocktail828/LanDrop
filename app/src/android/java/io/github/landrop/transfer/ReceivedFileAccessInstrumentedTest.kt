package io.github.landrop.transfer

import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.landrop.history.ReceiveHistoryStore
import io.github.landrop.model.ReceivedFile
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ReceivedFileAccessInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun fileProviderDeletionRemovesOnlyRecordsForThatFile() {
        val local = File.createTempFile("delete-file-", ".txt", context.cacheDir).apply { writeText("hello") }
        val other = File.createTempFile("keep-file-", ".txt", context.cacheDir).apply { writeText("keep") }
        val file = received(local)
        val kept = received(other)
        ReceiveHistoryStore(context).use { history ->
            try {
                history.add(file, "Peer")
                history.add(file, "Peer")
                history.add(kept, "Peer")
                val access = ReceivedFileAccess(context)
                access.checkReadable(file.uri)
                assertTrue(access.delete(file.uri))
                assertFalse(local.exists())
                assertTrue(other.exists())
                assertTrue(history.list().none { it.uri == file.uri })
                assertEquals(1, history.list().count { it.uri == kept.uri })
            } finally {
                local.delete(); other.delete()
                history.deleteFileRecords(file.uri)
                history.deleteFileRecords(kept.uri)
            }
        }
    }

    @Test fun failedDeletionKeepsHistoryAndDeletingHistoryKeepsTheFile() {
        val local = File.createTempFile("keep-record-", ".txt", context.cacheDir).apply { writeText("hello") }
        val file = received(local)
        val unavailable = file.copy(uri = Uri.parse("content://io.github.landrop.unavailable/file"))
        ReceiveHistoryStore(context).use { history ->
            try {
                history.add(unavailable, "Peer")
                val record = history.list().first { it.uri == unavailable.uri }
                assertTrue(runCatching { ReceivedFileAccess(context).delete(unavailable.uri) }.isFailure)
                assertTrue(history.list().any { it.id == record.id })
                history.delete(record.id)
                history.add(file, "Peer")
                history.delete(history.list().first { it.uri == file.uri }.id)
                assertTrue(local.exists())
            } finally {
                local.delete()
                history.deleteFileRecords(unavailable.uri)
                history.deleteFileRecords(file.uri)
            }
        }
    }

    @Test fun mediaStoreDownloadCanBeReadAndDeletedWithItsHistory() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val store = IncomingFileStore(context)
        val destination = store.create("delete-download-${System.nanoTime()}.txt", "text/plain", 5, null)
        ReceiveHistoryStore(context).use { history ->
            try {
                context.contentResolver.openOutputStream(destination.file.uri)!!.use { it.write("hello".toByteArray()) }
                store.complete(destination)
                history.add(destination.file, "Peer")
                val access = ReceivedFileAccess(context)
                access.checkReadable(destination.file.uri)
                assertTrue(access.delete(destination.file.uri))
                assertTrue(runCatching { access.checkReadable(destination.file.uri) }.isFailure)
                assertTrue(history.list().none { it.uri == destination.file.uri })
            } finally {
                context.contentResolver.delete(destination.file.uri, null, null)
                history.deleteFileRecords(destination.file.uri)
            }
        }
    }

    private fun received(file: File) = ReceivedFile(file.name,
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), "text/plain", file.length())
}
