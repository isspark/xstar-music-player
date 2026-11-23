package life.xstar.music.player.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandleDockingTest {
    @Test fun docksOnlyVeryCloseToAnyEdge() {
        assertTrue(shouldDockHandle(0f, 100f))
        assertTrue(shouldDockHandle(8f, 100f))
        assertTrue(shouldDockHandle(100f, 8f))
        assertFalse(shouldDockHandle(8.1f, 8.1f))
        assertFalse(shouldDockHandle(16f, 100f))
        assertFalse(shouldDockHandle(100f, 46f))
        assertFalse(shouldDockHandle(56f, 100f))
    }
}
