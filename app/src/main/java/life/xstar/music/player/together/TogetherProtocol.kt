package life.xstar.music.player.together

import kotlinx.serialization.json.Json
import java.util.Base64

object TogetherProtocol {
    const val VERSION = 1
    const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
        explicitNulls = false
    }

    fun newCode(): String = buildString {
        repeat(6) {
            append(CODE_ALPHABET[kotlin.random.Random.nextInt(CODE_ALPHABET.length)])
        }
    }

    fun encodeInvite(invite: TogetherInvite): String =
        json.encodeToString(TogetherInvite.serializer(), invite)

    fun decodeInvite(text: String): TogetherInvite? = runCatching {
        val trimmed = text.trim()
        json.decodeFromString(TogetherInvite.serializer(), trimmed)
    }.getOrNull()

    fun encodeMessage(message: TogetherMessage): ByteArray =
        json.encodeToString(TogetherMessage.serializer(), message).toByteArray(Charsets.UTF_8)

    fun decodeMessage(bytes: ByteArray): TogetherMessage? = runCatching {
        json.decodeFromString(TogetherMessage.serializer(), String(bytes, Charsets.UTF_8))
    }.getOrNull()

    fun base64(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))
    fun fromBase64(text: String): String = String(Base64.getUrlDecoder().decode(text), Charsets.UTF_8)
}
