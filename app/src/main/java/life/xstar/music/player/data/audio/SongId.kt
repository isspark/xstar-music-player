package life.xstar.music.player.data.audio

import java.security.MessageDigest

fun stableSongId(value: String): Long =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(8)
        .fold(0L) { result, byte -> (result shl 8) or (byte.toLong() and 0xff) }
