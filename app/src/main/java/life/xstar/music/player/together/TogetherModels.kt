package life.xstar.music.player.together

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class TogetherConnectMode { Lan, MultiPath }

enum class TogetherRole { Host, Member }

enum class TogetherConnection { Idle, Listening, Connecting, Connected, Error }

enum class TogetherReaction(val symbol: String, val label: String) {
    Heart("❤️", "爱心"), Clap("👏", "鼓掌"), Groove("🎵", "蹦迪"), Laugh("😂", "大笑"), Party("🥳", "派对")
}

@Serializable
data class TogetherPeer(val id: String, val nickname: String)

@Serializable
data class TogetherQueueEntry(val sourceId: Long, val title: String, val artist: String, val album: String)

@Serializable
data class TogetherSong(
    val sourceId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long = 0,
    val quality: String = "Standard",
    val token: String = "",
    val artToken: String = ""
)

@Serializable
data class TogetherInvite(
    val version: Int = 1,
    val code: String,
    val nickname: String,
    val mode: String,
    val controlHost: String,
    val streamHost: String,
    val streamPort: Int,
    val extControlHost: String = "",
    val extStreamHost: String = "",
    val extStreamPort: Int = 0,
    val serverControlHost: String = "",
    val serverHttpBase: String = "",
    val ts: Long = 0
)

@Serializable
sealed interface TogetherMessage {
    @Serializable @SerialName("hello")
    data class Hello(val version: Int, val role: String, val nickname: String, val mode: String) : TogetherMessage

    @Serializable @SerialName("welcome")
    data class Welcome(val host: TogetherPeer, val member: TogetherPeer) : TogetherMessage

    @Serializable @SerialName("control")
    data class Control(val action: String, val index: Int = -1, val positionMs: Long = -1, val mode: String = "") : TogetherMessage

    @Serializable @SerialName("snapshot")
    data class Snapshot(
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
        val song: TogetherSong?,
        val queue: List<TogetherQueueEntry> = emptyList(),
        val index: Int = -1,
        val mode: String = "Sequential",
        val seq: Long = 0
    ) : TogetherMessage

    @Serializable @SerialName("reaction")
    data class Reaction(val reaction: String, val nickname: String) : TogetherMessage

    @Serializable @SerialName("chat")
    data class Chat(val nickname: String, val text: String, val ts: Long = 0) : TogetherMessage

    @Serializable @SerialName("ping")
    data class Ping(val t: Long) : TogetherMessage

    @Serializable @SerialName("pong")
    data class Pong(val t: Long) : TogetherMessage

    @Serializable @SerialName("bye")
    data class Bye(val reason: String) : TogetherMessage
}

enum class TogetherChatKind { Reaction, Text }

data class TogetherChatMessage(
    val kind: TogetherChatKind,
    val nickname: String,
    val text: String,
    val fromSelf: Boolean,
    val ts: Long = 0,
    val at: Long = 0
)

data class TogetherSessionState(
    val role: TogetherRole? = null,
    val connection: TogetherConnection = TogetherConnection.Idle,
    val code: String? = null,
    val me: TogetherPeer? = null,
    val peer: TogetherPeer? = null,
    val connectMode: TogetherConnectMode = TogetherConnectMode.MultiPath,
    val inviteText: String? = null,
    val roomOpen: Boolean = false,
    val memberCount: Int = 0,
    val sharedQueue: List<TogetherQueueEntry> = emptyList(),
    val sharedIndex: Int = -1,
    val sharedMode: String = "Sequential",
    val sharedSong: TogetherSong? = null,
    val messages: List<TogetherChatMessage> = emptyList(),
    val error: String? = null
) {
    val inSession: Boolean
        get() = connection == TogetherConnection.Connected || connection == TogetherConnection.Listening || connection == TogetherConnection.Connecting
    val isHost: Boolean get() = role == TogetherRole.Host
    val isMember: Boolean get() = role == TogetherRole.Member
}
