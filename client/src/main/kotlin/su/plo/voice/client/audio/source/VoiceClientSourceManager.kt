package su.plo.voice.client.audio.source

import com.google.common.collect.ListMultimap
import com.google.common.collect.Maps
import com.google.common.collect.Multimaps
import kotlinx.coroutines.runBlocking
import su.plo.voice.api.client.audio.device.DeviceException
import su.plo.voice.api.client.audio.source.ClientAudioSource
import su.plo.voice.api.client.audio.source.ClientSelfSourceInfo
import su.plo.voice.api.client.audio.source.ClientSourceManager
import su.plo.voice.api.client.event.audio.source.AudioSourceClosedEvent
import su.plo.voice.api.event.EventSubscribe
import su.plo.voice.BaseVoice
import su.plo.voice.client.BaseVoiceClient
import su.plo.voice.client.config.VoiceClientConfig
import su.plo.voice.proto.data.audio.source.*
import su.plo.voice.proto.packets.tcp.clientbound.SourceAudioEndPacket
import su.plo.voice.proto.packets.tcp.serverbound.SourceInfoRequestPacket
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32

class VoiceClientSourceManager(
    private val voiceClient: BaseVoiceClient,
    private val config: VoiceClientConfig
) : ClientSourceManager {

    private val sourcesByLineId: ListMultimap<UUID, ClientAudioSource<*>> = Multimaps.newListMultimap(
        Maps.newConcurrentMap(),
        ::CopyOnWriteArrayList
    )

    private val sourcesByPlayerId: ListMultimap<UUID, ClientAudioSource<PlayerSourceInfo>> = Multimaps.newListMultimap(
        Maps.newConcurrentMap(),
        ::CopyOnWriteArrayList
    )

    private val sourcesByEntityId: ListMultimap<Int, ClientAudioSource<EntitySourceInfo>> = Multimaps.newListMultimap(
        Maps.newConcurrentMap(),
        ::CopyOnWriteArrayList
    )

    private val sourceById: MutableMap<UUID, ClientAudioSource<out SourceInfo>> = Maps.newConcurrentMap()
    private val sourceRequestById: MutableMap<UUID, Long> = Maps.newConcurrentMap()
    private val pendingAudioBySourceId: MutableMap<UUID, Deque<PendingSourceAudioPacket>> = Maps.newConcurrentMap()
    private val pendingAudioDiagnostics = PendingAudioDiagnostics()
    private val selfSourceInfoById: MutableMap<UUID, VoiceClientSelfSourceInfo> = Maps.newConcurrentMap()

//    init {
//        voiceClient.backgroundExecutor.scheduleAtFixedRate(
//            { tickSelfSourceInfo() },
//            0L, 5L, TimeUnit.SECONDS
//        )
//    }

    override fun createLoopbackSource(relative: Boolean) =
        ClientLoopbackSource(voiceClient, config, relative)

    override fun getSourceById(sourceId: UUID, request: Boolean): Optional<ClientAudioSource<*>> {
        check(voiceClient.serverConnection.isPresent) { "Not connected" }
        val source = sourceById[sourceId]
        if (source != null) return Optional.of(source)
        if (!request) return Optional.empty()

        // request source
        val lastRequest = sourceRequestById.getOrDefault(sourceId, 0L)
        if (System.currentTimeMillis() - lastRequest > 1000L)
            sendSourceInfoRequest(sourceId)

        return Optional.empty()
    }

    override fun getSourcesByLineId(lineId: UUID): Collection<ClientAudioSource<*>> {
        return sourcesByLineId[lineId]
    }

    override fun getEntitySources(entityId: Int): Collection<ClientAudioSource<EntitySourceInfo>> =
        sourcesByEntityId[entityId]

    override fun getPlayerSources(playerId: UUID): Collection<ClientAudioSource<PlayerSourceInfo>> =
        sourcesByPlayerId[playerId]

    override fun getSourceById(sourceId: UUID) =
        getSourceById(sourceId, true)

    override fun getSources(): Collection<ClientAudioSource<*>> =
        sourceById.values

    override fun getSelfSourceInfo(sourceId: UUID): Optional<ClientSelfSourceInfo> =
        Optional.ofNullable(selfSourceInfoById[sourceId])

    override fun getAllSelfSourceInfos(): Collection<ClientSelfSourceInfo> =
        selfSourceInfoById.values

    override fun clear() {
        sourceById.values.forEach { it.closeAsync().get() }
        sourcesByLineId.clear()
        sourcesByPlayerId.clear()
        sourcesByEntityId.clear()
        sourceRequestById.clear()
        pendingAudioBySourceId.clear()
        selfSourceInfoById.clear()
    }

    fun processAudioPacket(packet: SourceAudioPacket) {
        val source = sourceById[packet.sourceId]
        if (source == null) {
            val queueSize = enqueuePendingAudioPacket(packet)
            pendingAudioDiagnostics.recordPending(packet, queueSize)
            sendSourceInfoRequest(packet.sourceId)
            return
        }

        if (source.sourceInfo.state != packet.sourceState) {
            pendingAudioDiagnostics.recordStateMismatch(packet, source.sourceInfo.state)
            sendSourceInfoRequest(packet.sourceId, true)
        }

        source.process(packet)
    }

    fun processAudioEndPacket(packet: SourceAudioEndPacket) {
        val source = sourceById[packet.sourceId]
        if (source == null) {
            pendingAudioDiagnostics.recordMissingEnd(packet.sourceId, packet.sequenceNumber)
            return
        }

        source.process(packet)
    }

    override fun createOrUpdateSource(sourceInfo: SourceInfo): Unit = runBlocking {
        try {
            if (sourceById.containsKey(sourceInfo.id)) {
                val source = sourceById[sourceInfo.id]!!
                if (source.isClosed()) {
                    sourceRequestById.remove(sourceInfo.id)
                    return@runBlocking
                }
                if (source.sourceInfo.lineId != sourceInfo.lineId) {
                    sourcesByLineId.remove(source.sourceInfo.lineId, source)
                    sourcesByLineId.put(sourceInfo.lineId, source)
                }

                source.updateUnchecked(sourceInfo)
                pendingAudioDiagnostics.recordDrain(sourceInfo.id, drainPendingAudioPackets(sourceInfo.id, source))
                return@runBlocking
            }

            val source: ClientAudioSource<out SourceInfo>
            when (sourceInfo) {
                is PlayerSourceInfo -> {
                    source = createPlayerSource(sourceInfo)
                    sourceById[sourceInfo.getId()] = source
                    sourcesByLineId.put(sourceInfo.getLineId(), source)
                    sourcesByPlayerId.put(sourceInfo.playerInfo.playerId, source)
                }

                is EntitySourceInfo -> {
                    source = createEntitySource(sourceInfo)
                    sourceById[sourceInfo.getId()] = source
                    sourcesByLineId.put(sourceInfo.getLineId(), source)
                    sourcesByEntityId.put(sourceInfo.entityId, source)
                }

                is StaticSourceInfo -> {
                    source = createStaticSource(sourceInfo)
                    sourceById[sourceInfo.getId()] = source
                    sourcesByLineId.put(sourceInfo.getLineId(), source)
                }

                is DirectSourceInfo -> {
                    source = createDirectSource(sourceInfo)
                    sourceById[sourceInfo.getId()] = source
                    sourcesByLineId.put(sourceInfo.getLineId(), source)
                }

                else -> throw IllegalArgumentException("Invalid source type")
            }
            sourceRequestById.remove(sourceInfo.id)
            pendingAudioDiagnostics.recordDrain(sourceInfo.id, drainPendingAudioPackets(sourceInfo.id, source))
        } catch (e: DeviceException) {
            throw IllegalStateException("Failed to initialize audio source", e)
        }
    }

    override fun sendSourceInfoRequest(sourceId: UUID, requestIfExist: Boolean) {
        if (!requestIfExist && sourceById.containsKey(sourceId)) return

        val now = System.currentTimeMillis()
        val lastRequest = sourceRequestById.getOrDefault(sourceId, 0L)
        if (now - lastRequest <= SOURCE_INFO_REQUEST_INTERVAL_MS) return

        val connection = voiceClient.serverConnection
            .orElseThrow { IllegalStateException("Not connected") }

        sourceRequestById[sourceId] = now
        connection.sendPacket(SourceInfoRequestPacket(sourceId))
    }

    override fun updateSelfSourceInfo(selfSourceInfo: SelfSourceInfo) {
        selfSourceInfoById.computeIfAbsent(
            selfSourceInfo.sourceInfo.id
        ) {
            VoiceClientSelfSourceInfo()
        }.selfSourceInfo = selfSourceInfo

        if (getSourceById(selfSourceInfo.sourceInfo.id, false).isPresent) {
            createOrUpdateSource(selfSourceInfo.sourceInfo)
        }
    }

    @EventSubscribe
    fun onAudioSourceClosed(event: AudioSourceClosedEvent) {
        val source = event.source

        voiceClient.eventBus.unregister(voiceClient, source)

        sourceById.remove(source.sourceInfo.id)
        pendingAudioBySourceId.remove(source.sourceInfo.id)
        sourcesByLineId.remove(source.sourceInfo.lineId, source)

        (source.sourceInfo as? PlayerSourceInfo)?.playerInfo?.let {
            sourcesByPlayerId.remove(it.playerId, source)
        }

        (source.sourceInfo as? EntitySourceInfo)?.entityId?.let {
            sourcesByEntityId.remove(it, source)
        }
    }

//    private fun tickSelfSourceInfo() {
//        selfSourceInfoById.values
//            .filter {
//                System.currentTimeMillis() - it.lastUpdate > TIMEOUT_MS
//            }
//            .map { it.selfSourceInfo.sourceInfo.id }
//            .forEach { selfSourceInfoById.remove(it) }
//    }

    private fun createPlayerSource(sourceInfo: PlayerSourceInfo): ClientAudioSource<PlayerSourceInfo> {
        return ClientPlayerSource(
            voiceClient, config, sourceInfo
        ).also { voiceClient.eventBus.register(voiceClient, it) }
    }

    private fun createEntitySource(sourceInfo: EntitySourceInfo): ClientAudioSource<EntitySourceInfo> {
        return ClientEntitySource(
            voiceClient, config, sourceInfo
        ).also { voiceClient.eventBus.register(voiceClient, it) }
    }

    private fun createDirectSource(sourceInfo: DirectSourceInfo): ClientAudioSource<DirectSourceInfo> {
        return ClientDirectSource(
            voiceClient, config, sourceInfo
        ).also { voiceClient.eventBus.register(voiceClient, it) }
    }

    private fun createStaticSource(sourceInfo: StaticSourceInfo): ClientAudioSource<StaticSourceInfo> {
        return ClientStaticSource(
            voiceClient, config, sourceInfo
        ).also { voiceClient.eventBus.register(voiceClient, it) }
    }

    private fun enqueuePendingAudioPacket(packet: SourceAudioPacket): Int {
        val now = System.currentTimeMillis()
        val queue = pendingAudioBySourceId.computeIfAbsent(packet.sourceId) {
            ArrayDeque()
        }

        synchronized(queue) {
            while (queue.isNotEmpty() &&
                (queue.size >= MAX_PENDING_AUDIO_PACKETS_PER_SOURCE ||
                    now - queue.peekFirst().receivedAtMillis > MAX_PENDING_AUDIO_PACKET_AGE_MS)
            ) {
                queue.removeFirst()
            }

            queue.addLast(
                PendingSourceAudioPacket(
                    SourceAudioPacket(
                        packet.sequenceNumber,
                        packet.sourceState,
                        packet.data.copyOf(),
                        packet.sourceId,
                        packet.distance
                    ),
                    now
                )
            )

            return queue.size
        }
    }

    private fun drainPendingAudioPackets(sourceId: UUID, source: ClientAudioSource<out SourceInfo>): Int {
        val queue = pendingAudioBySourceId.remove(sourceId) ?: return 0
        val now = System.currentTimeMillis()
        val packets = synchronized(queue) {
            queue
                .asSequence()
                .filter { now - it.receivedAtMillis <= MAX_PENDING_AUDIO_PACKET_AGE_MS }
                .map { it.packet }
                .sortedBy { it.sequenceNumber }
                .toList()
        }

        packets.forEach { source.process(it) }
        return packets.size
    }

    private data class PendingSourceAudioPacket(
        val packet: SourceAudioPacket,
        val receivedAtMillis: Long
    )

    private class PendingAudioDiagnostics {

        private val statsBySourceId: MutableMap<UUID, PendingSourceStats> = Maps.newConcurrentMap()

        fun recordPending(packet: SourceAudioPacket, queueSize: Int) {
            val stats = statsBySourceId.computeIfAbsent(packet.sourceId) { PendingSourceStats() }
            stats.pendingFrames += 1
            stats.maxPendingQueue = stats.maxPendingQueue.coerceAtLeast(queueSize)
            stats.lastSequenceNumber = packet.sequenceNumber
            stats.lastBytes = packet.data.size
            stats.lastPayloadCrc = checksum(packet.data)
            stats.maybeLog("pending_source_info", packet.sourceId, false)
        }

        fun recordDrain(sourceId: UUID, drainedFrames: Int) {
            if (drainedFrames <= 0) return
            val stats = statsBySourceId.computeIfAbsent(sourceId) { PendingSourceStats() }
            stats.drainedFrames += drainedFrames.toLong()
            stats.maybeLog("drain_pending_source_info", sourceId, true)
        }

        fun recordStateMismatch(packet: SourceAudioPacket, expectedState: Byte) {
            val stats = statsBySourceId.computeIfAbsent(packet.sourceId) { PendingSourceStats() }
            stats.stateMismatches += 1
            stats.lastSequenceNumber = packet.sequenceNumber
            stats.lastBytes = packet.data.size
            stats.lastPayloadCrc = checksum(packet.data)
            stats.maybeLog("state_mismatch expected=$expectedState got=${packet.sourceState}", packet.sourceId, true)
        }

        fun recordMissingEnd(sourceId: UUID, sequenceNumber: Long) {
            val stats = statsBySourceId.computeIfAbsent(sourceId) { PendingSourceStats() }
            stats.missingEndPackets += 1
            stats.lastSequenceNumber = sequenceNumber
            stats.maybeLog("missing_source_for_end", sourceId, true)
        }

        private class PendingSourceStats {
            var nextLogAtMillis = 0L
            var pendingFrames = 0L
            var drainedFrames = 0L
            var stateMismatches = 0L
            var missingEndPackets = 0L
            var maxPendingQueue = 0
            var lastSequenceNumber = -1L
            var lastBytes = 0
            var lastPayloadCrc = 0L

            fun maybeLog(reason: String, sourceId: UUID, force: Boolean) {
                val now = System.currentTimeMillis()
                if (!force && now < nextLogAtMillis) return
                nextLogAtMillis = now + SOURCE_INFO_DIAGNOSTIC_INTERVAL_MS

                BaseVoice.DEBUG_LOGGER.log(
                    "[MytrixVoice] Client source-info diag reason={} source={} seq={} bytes={} crc={} pending_frames={} drained_frames={} state_mismatch={} missing_end={} max_pending_queue={} thread={}",
                    reason,
                    sourceId,
                    lastSequenceNumber,
                    lastBytes,
                    lastPayloadCrc,
                    pendingFrames,
                    drainedFrames,
                    stateMismatches,
                    missingEndPackets,
                    maxPendingQueue,
                    Thread.currentThread().name
                )
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 25000L
        private const val SOURCE_INFO_REQUEST_INTERVAL_MS = 1000L
        private const val SOURCE_INFO_DIAGNOSTIC_INTERVAL_MS = 5_000L
        private const val MAX_PENDING_AUDIO_PACKETS_PER_SOURCE = 12
        private const val MAX_PENDING_AUDIO_PACKET_AGE_MS = 750L

        private fun checksum(data: ByteArray): Long {
            val crc = CRC32()
            crc.update(data, 0, data.size)
            return crc.value
        }
    }
}
