package su.plo.voice.server.audio.source

import org.junit.jupiter.api.Test
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class VoiceServerBroadcastSourceTest {

    @Test
    fun `broadcast recipient packet copies preserve metadata and isolate opus payload`() {
        val sourceId = UUID.randomUUID()
        val originalPayload = byteArrayOf(1, 2, 3, 4, 5, 6)
        val packet = SourceAudioPacket(
            42L,
            7.toByte(),
            originalPayload,
            sourceId,
            0.toShort()
        )

        val firstRecipient = packet.copyForBroadcastRecipient()
        val secondRecipient = packet.copyForBroadcastRecipient()

        assertEquals(packet.sequenceNumber, firstRecipient.sequenceNumber)
        assertEquals(packet.sourceState, firstRecipient.sourceState)
        assertEquals(packet.sourceId, firstRecipient.sourceId)
        assertEquals(packet.distance, firstRecipient.distance)
        assertContentEquals(originalPayload, firstRecipient.data)
        assertContentEquals(originalPayload, secondRecipient.data)

        assertFalse(firstRecipient.data === packet.data)
        assertFalse(secondRecipient.data === packet.data)
        assertFalse(firstRecipient.data === secondRecipient.data)

        packet.data[0] = 99
        firstRecipient.data[1] = 88

        assertContentEquals(byteArrayOf(1, 88, 3, 4, 5, 6), firstRecipient.data)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), secondRecipient.data)
    }
}
