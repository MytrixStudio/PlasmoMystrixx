package su.plo.voice.client.audio.source

import su.plo.voice.api.client.time.TimeSupplier
import su.plo.voice.proto.packets.tcp.clientbound.SourceAudioEndPacket
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket
import java.util.Queue
import java.util.concurrent.PriorityBlockingQueue

class StaticJitterBuffer(
    private val timeSupplier: TimeSupplier,
    packetDelay: Int,
    private val staleThresholdMillis: Long = 500,
) : JitterBuffer {

    private val effectivePacketDelay = packetDelay.coerceAtLeast(MIN_PACKET_DELAY_FOR_ORDERING)

    private val queue: Queue<JitterBuffer.PacketWithSequenceNumber> = PriorityBlockingQueue(
        maxOf(effectivePacketDelay * 2, MIN_JITTER_QUEUE_CAPACITY),
        compareBy { it.sequenceNumber }
    )

    private var endPacket: SourceAudioEndPacket? = null

    override fun offer(packet: SourceAudioPacket) {
        if (endPacket != null && packet.sequenceNumber > endPacket!!.sequenceNumber) {
            this.endPacket = null
        }

        queue.offer(JitterBuffer.SourceAudioPacketWrapper(packet, timeSupplier.currentTimeMillis))
    }

    override fun offer(packet: SourceAudioEndPacket) {
        this.endPacket = packet

        queue.offer(JitterBuffer.SourceAudioEndPacketWrapper(packet, timeSupplier.currentTimeMillis))
    }

    override fun poll(): JitterBuffer.PacketWithSequenceNumber? {
        if (endPacket != null || queue.size >= effectivePacketDelay) {
            return queue.poll()
                ?.takeIf { timeSupplier.currentTimeMillis - it.arrivalTime < staleThresholdMillis }
        }

        return null
    }

    override fun isEmpty(): Boolean =
        queue.isEmpty()

    override fun size(): Int =
        queue.size

    private companion object {
        private const val MIN_PACKET_DELAY_FOR_ORDERING = 2
        private const val MIN_JITTER_QUEUE_CAPACITY = 8
    }
}
