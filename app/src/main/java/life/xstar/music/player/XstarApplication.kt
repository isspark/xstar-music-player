package life.xstar.music.player

import android.app.Application
import life.xstar.music.player.cast.CastSessionController
import life.xstar.music.player.data.local.MusicDatabase
import life.xstar.music.player.data.repository.MusicRepository
import life.xstar.music.player.data.preferences.PlaybackPreferences

class XstarApplication : Application() {
    val database by lazy { MusicDatabase.create(this) }
    val repository by lazy { MusicRepository(this, database.songDao(), database.playlistDao()) }
    val playbackPreferences by lazy { PlaybackPreferences(this) }
    val castController by lazy { CastSessionController(this) }
}
