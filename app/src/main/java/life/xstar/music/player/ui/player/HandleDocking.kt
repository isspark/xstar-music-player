package life.xstar.music.player.ui.player

/** Distances are from the button's outer bounds, in dp. */
internal fun shouldDockHandle(horizontalDistance: Float, verticalDistance: Float): Boolean =
    minOf(horizontalDistance, verticalDistance) <= 8f
