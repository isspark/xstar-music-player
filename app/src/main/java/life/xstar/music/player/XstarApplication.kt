package life.xstar.music.player

import android.app.Application
import life.xstar.music.player.cast.CastSessionController
import life.xstar.music.player.data.local.MusicDatabase
import life.xstar.music.player.data.online.OnlineMusicRepository
import life.xstar.music.player.data.repository.MusicRepository
import life.xstar.music.player.data.preferences.PlaybackPreferences
import life.xstar.music.player.together.TogetherSessionController

class XstarApplication : Application() {
    val database by lazy { MusicDatabase.create(this) }
    val repository by lazy { MusicRepository(this, database.songDao(), database.playlistDao(), database.singingDao()) }
    val playbackPreferences by lazy { PlaybackPreferences(this) }
    val castController by lazy { CastSessionController(this) }
    val togetherController by lazy { TogetherSessionController(this, repository) }
    val onlineMusicRepository by lazy { OnlineMusicRepository(this, database.songDao(), playbackPreferences) }
}
