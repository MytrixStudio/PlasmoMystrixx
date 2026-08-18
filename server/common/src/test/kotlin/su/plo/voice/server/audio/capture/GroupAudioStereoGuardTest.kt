package su.plo.voice.server.audio.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupAudioStereoGuardTest {

    @Test
    fun `first packet establishes source stereo mode`() {
        val guard = GroupAudioStereoGuard<String>()

        val decision = guard.resolve("speaker:group", requestedStereo = false, active = false)

        assertFalse(decision.sourceStereo)
        assertNull(decision.previousStereo)
        assertFalse(decision.recreateSource)
        assertFalse(decision.dropPacket)
    }

    @Test
    fun `same stereo mode keeps existing source`() {
        val guard = GroupAudioStereoGuard<String>()
        guard.resolve("speaker:group", requestedStereo = true, active = false)

        val decision = guard.resolve("speaker:group", requestedStereo = true, active = true)

        assertTrue(decision.sourceStereo)
        assertEquals(true, decision.previousStereo)
        assertFalse(decision.recreateSource)
        assertFalse(decision.dropPacket)
    }

    @Test
    fun `stereo mode switch during active stream drops packet instead of mutating source`() {
        val guard = GroupAudioStereoGuard<String>()
        guard.resolve("speaker:group", requestedStereo = false, active = false)

        val decision = guard.resolve("speaker:group", requestedStereo = true, active = true)

        assertFalse(decision.sourceStereo)
        assertEquals(false, decision.previousStereo)
        assertFalse(decision.recreateSource)
        assertTrue(decision.dropPacket)
    }

    @Test
    fun `stereo mode switch between streams recreates source`() {
        val guard = GroupAudioStereoGuard<String>()
        guard.resolve("speaker:group", requestedStereo = false, active = false)

        val decision = guard.resolve("speaker:group", requestedStereo = true, active = false)

        assertTrue(decision.sourceStereo)
        assertEquals(false, decision.previousStereo)
        assertTrue(decision.recreateSource)
        assertFalse(decision.dropPacket)
    }

    @Test
    fun `removed stream can establish a fresh stereo mode`() {
        val guard = GroupAudioStereoGuard<String>()
        guard.resolve("speaker:group", requestedStereo = false, active = false)
        guard.remove("speaker:group")

        val decision = guard.resolve("speaker:group", requestedStereo = true, active = false)

        assertTrue(decision.sourceStereo)
        assertNull(decision.previousStereo)
        assertFalse(decision.recreateSource)
        assertFalse(decision.dropPacket)
    }
}
