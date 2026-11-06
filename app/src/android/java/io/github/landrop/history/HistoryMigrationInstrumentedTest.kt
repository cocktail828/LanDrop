package io.github.landrop.history

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class HistoryMigrationInstrumentedTest {
    @Test fun versionTwoRowsSurviveDirectionAndResultMigration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Exercise the migration against an isolated temporary DB, never the user's history.
        val file = java.io.File.createTempFile("landrop-history-", ".db", context.cacheDir)
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE receive_history (id INTEGER PRIMARY KEY AUTOINCREMENT, display_name TEXT NOT NULL, file_uri TEXT NOT NULL, mime_type TEXT NOT NULL, file_size INTEGER NOT NULL, sender_alias TEXT NOT NULL, received_at INTEGER NOT NULL, is_message INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("INSERT INTO receive_history VALUES(1,'old.txt','content://old','text/plain',12,'Peer',1,0)")
                ReceiveHistoryStore(context).use { helper -> helper.onUpgrade(db, 2, 3) }
                db.rawQuery("SELECT display_name,outgoing,result FROM receive_history", null).use {
                    assertTrue(it.moveToFirst()); assertEquals("old.txt", it.getString(0))
                    assertEquals(0,it.getInt(1)); assertEquals("saved",it.getString(2))
                }
            }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }
    @Test fun sentFileWithoutLocalUriCanOpenDetails() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sent-history-${System.nanoTime()}.txt"
        ReceiveHistoryStore(context).use { store ->
            store.addTransfer(name, "Peer", 12, true, "sent")
            val entry = store.list().first { it.displayName == name }
            try {
                assertTrue(entry.outgoing)
                assertEquals("sent", entry.result)
                androidx.test.core.app.ActivityScenario.launch(io.github.landrop.ReceiveHistoryActivity::class.java).use {
                    androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(name))
                        .perform(androidx.test.espresso.action.ViewActions.click())
                    androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(io.github.landrop.R.id.file_info_path))
                        .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.withText("—")))
                }
            } finally { store.delete(entry.id) }
        }
    }

}
