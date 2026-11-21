package life.xstar.music.player.data.nas

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import life.xstar.music.player.data.local.MusicDatabase
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NasSyncIntegrationTest {
    @Test fun interruptedListingKeepsCommittedBatchAndResumesWithoutDuplicates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).build()
        val server = MockWebServer()
        server.start()
        try {
            // Use isolated preferences so the real user's NAS setup is never touched.
            val isolated = object : android.content.ContextWrapper(context) {
                override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("test-nas-$name", mode)
            }
            val repository = NasRepository(isolated, db)
            repository.save(NasConfig(address = server.url("/music").toString(), username = "test", password = "test"))
            val source = repository.config.value.id
            fun listing(count: Int, closed: Boolean) = buildString {
                append("<d:multistatus xmlns:d=\"DAV:\">")
                repeat(count) { append("<d:response><d:href>/music/song$it.mp3</d:href><d:propstat><d:prop><d:getcontentlength>100</d:getcontentlength></d:prop></d:propstat></d:response>") }
                if (closed) append("</d:multistatus>")
            }
            server.enqueue(MockResponse().setResponseCode(207).setBody(listing(350, false)))
            repository.sync()
            assertEquals(300, db.songDao().allSongs().size)
            assertFalse(db.nasSyncDao().task(source)!!.state == "complete")
            server.enqueue(MockResponse().setResponseCode(207).setBody(listing(350, true)))
            // A fresh repository represents process recreation, using only durable state.
            val restored = NasRepository(isolated, db)
            restored.prepareResume()
            restored.sync()
            assertEquals(350, db.songDao().allSongs().size)
            assertEquals("complete", db.nasSyncDao().task(source)!!.state)
            assertEquals(350, restored.status.value.count)
            isolated.getSharedPreferences("nas", Context.MODE_PRIVATE).edit().clear().commit()
            Unit
        } finally { db.close(); server.shutdown() }
    }
}
