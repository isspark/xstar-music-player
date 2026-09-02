package life.xstar.music.player.data.lyrics

private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")

object LrcParser {
    fun parse(content: String): List<LyricLine> = content
        .removePrefix("\uFEFF")
        .lineSequence()
        .flatMap { line ->
            val matches = timestamp.findAll(line).toList()
            if (matches.isEmpty()) emptySequence()
            else {
                val text = line.substring(matches.last().range.last + 1).trim()
                matches.asSequence().map { match -> LyricLine(parseTime(match), text) }
            }
        }
        .filter { it.text.isNotBlank() }
        .sortedBy { it.timeMs }
        .toList()

    private fun parseTime(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
        return minutes * 60_000 + seconds * 1_000 + fraction
    }
}
