package su.plo.voice.server.audio.capture

import net.mytrix.voice.api.VoiceChannelId
import su.plo.slib.api.permission.PermissionDefault
import su.plo.voice.api.event.EventSubscribe
import su.plo.voice.api.server.audio.capture.ServerActivation
import su.plo.voice.api.server.audio.source.ServerBroadcastSource
import su.plo.voice.api.server.event.connection.UdpClientDisconnectedEvent
import su.plo.voice.api.server.player.VoicePlayer
import su.plo.voice.BaseVoice
import su.plo.voice.proto.data.audio.capture.VoiceActivation
import su.plo.voice.proto.data.audio.line.VoiceSourceLine
import su.plo.voice.proto.packets.tcp.clientbound.SourceAudioEndPacket
import su.plo.voice.proto.packets.tcp.serverbound.PlayerAudioEndPacket
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket
import su.plo.voice.server.BaseVoiceServer
import su.plo.voice.server.config.VoiceServerConfig
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import kotlin.jvm.optionals.getOrNull

class GroupServerActivation(private val voiceServer: BaseVoiceServer) {

    private var groupHelper: GroupServerActivationHelper? = null

    fun register(config: VoiceServerConfig) {
        groupHelper?.let {
            it.unregisterListeners(voiceServer)
            voiceServer.activationManager.unregister(it.activation.id)
            voiceServer.sourceLineManager.unregister(it.sourceLine.id)
        }

        val activation = voiceServer.activationManager.createBuilder(
            voiceServer,
            VoiceActivation.GROUP_NAME,
            "pv.activation.group",
            "plasmovoice:textures/icons/microphone_group.png",
            "pv.activation.group",
            2
        )
            .setDistances(emptyList())
            .setDefaultDistance(0)
            .setProximity(false)
            .setTransitive(false)
            .setStereoSupported(false)
            .setPermissionDefault(PermissionDefault.TRUE)
            .build()

        val sourceLine = voiceServer.sourceLineManager.createBuilder(
            voiceServer,
            VoiceSourceLine.GROUP_NAME,
            "pv.activation.group",
            "plasmovoice:textures/icons/speaker_group.png",
            2
        )
            .setDefaultVolume(1.0)
            .withPlayers(true)
            .build()

        groupHelper = GroupServerActivationHelper(voiceServer, activation, sourceLine)
        groupHelper?.registerListeners(voiceServer)
        runCatching {
            BaseVoice.LOGGER.info("[MytrixVoice] Group voice activation registered")
        }
    }

    fun removePlayerSources(playerId: UUID): Boolean =
        groupHelper?.removePlayerSources(playerId) ?: false

    fun removeChannelSources(channelId: VoiceChannelId): Boolean =
        groupHelper?.removeChannelSources(channelId) ?: false

    fun removeChannelRecipient(channelId: VoiceChannelId, playerId: UUID): Boolean =
        groupHelper?.removeChannelRecipient(channelId, playerId) ?: false

    fun routeNormalVoicePacket(player: VoicePlayer, packet: PlayerAudioPacket): Boolean =
        groupHelper?.routeNormalVoicePacket(player, packet) ?: false

    fun routeNormalVoiceEndPacket(player: VoicePlayer, packet: PlayerAudioEndPacket): Boolean =
        groupHelper?.routeNormalVoiceEndPacket(player, packet) ?: false

    private class GroupServerActivationHelper(
        private val voiceServer: BaseVoiceServer,
        val activation: ServerActivation,
        val sourceLine: su.plo.voice.api.server.audio.line.ServerSourceLine
    ) {

        private val sourceByKey: MutableMap<GroupSourceKey, ServerBroadcastSource> = ConcurrentHashMap()
        private val sourceInfoRecipientsByKey: MutableMap<GroupSourceKey, MutableSet<UUID>> = ConcurrentHashMap()
        private val activeSpeakingByKey: MutableSet<GroupSourceKey> = ConcurrentHashMap.newKeySet()
        private val stereoGuard = GroupAudioStereoGuard<GroupSourceKey>()
        private val rateBySpeaker: MutableMap<UUID, PacketRateState> = ConcurrentHashMap()
        private val diagnosticsByKey: MutableMap<GroupSourceKey, GroupAudioDiagnostics> = ConcurrentHashMap()

        init {
            activation.onPlayerActivation(this::onActivation)
            activation.onPlayerActivationEnd(this::onActivationEnd)
        }

        fun registerListeners(addon: Any) {
            voiceServer.eventBus.register(addon, this)
        }

        fun unregisterListeners(addon: Any) {
            voiceServer.eventBus.unregister(addon, this)
            sourceByKey.keys.toList().forEach(::removeSource)
            stereoGuard.clear()
        }

        fun routeNormalVoicePacket(player: VoicePlayer, packet: PlayerAudioPacket): Boolean {
            val speakerId = player.instance.uuid
            if (!voiceServer.dynamicVoiceService.selectedGroupChannel(speakerId).isPresent) return false
            onActivation(player, packet)
            return true
        }

        fun routeNormalVoiceEndPacket(player: VoicePlayer, packet: PlayerAudioEndPacket): Boolean {
            val speakerId = player.instance.uuid
            if (!voiceServer.dynamicVoiceService.selectedGroupChannel(speakerId).isPresent) return false
            onActivationEnd(player, packet)
            return true
        }

        @EventSubscribe
        fun onClientDisconnected(event: UdpClientDisconnectedEvent) {
            removePlayerSources(event.connection.player.instance.uuid)
        }

        fun removePlayerSources(playerId: UUID): Boolean {
            var removed = false
            rateBySpeaker.remove(playerId)
            diagnosticsByKey.keys
                .filter { it.speakerId == playerId }
                .toList()
                .forEach { diagnosticsByKey.remove(it) }
            sourceByKey.keys
                .filter { it.speakerId == playerId }
                .toList()
                .forEach {
                    removeSource(it)
                    removed = true
                }

            sourceInfoRecipientsByKey.values.forEach { it.remove(playerId) }
            return removed
        }

        fun removeChannelSources(channelId: VoiceChannelId): Boolean {
            var removed = false
            diagnosticsByKey.keys
                .filter { it.channelId == channelId }
                .toList()
                .forEach { diagnosticsByKey.remove(it) }
            sourceByKey.keys
                .filter { it.channelId == channelId }
                .toList()
                .forEach {
                    removeSource(it)
                    removed = true
                }
            return removed
        }

        fun removeChannelRecipient(channelId: VoiceChannelId, playerId: UUID): Boolean {
            var removed = false

            sourceByKey.entries
                .filter { it.key.channelId == channelId }
                .toList()
                .forEach { entry ->
                    if (entry.key.speakerId == playerId) {
                        removeSource(entry.key)
                        removed = true
                        return@forEach
                    }

                    val players = entry.value.players ?: return@forEach
                    val filtered = players.filter { voicePlayer ->
                        voicePlayer.instance.uuid != playerId
                    }
                    if (filtered.size != players.size) {
                        entry.value.players = filtered
                        removed = true
                    }
                }

            sourceInfoRecipientsByKey.entries
                .filter { it.key.channelId == channelId }
                .forEach { it.value.remove(playerId) }

            return removed
        }

        private fun onActivation(player: VoicePlayer, packet: PlayerAudioPacket): ServerActivation.Result {
            val speakerId = player.instance.uuid
            val receivedAtMillis = System.currentTimeMillis()
            val receivedAtNanos = System.nanoTime()
            val selectedBeforeRouting = voiceServer.dynamicVoiceService
                .selectedGroupChannel(speakerId)
                .orElse(null)

            if (!isPayloadAllowed(packet)) {
                selectedBeforeRouting?.let {
                    diagnosticsFor(GroupSourceKey(speakerId, it)).recordDrop(
                        "payload",
                        packet,
                        receivedAtMillis,
                        0,
                        0L,
                        0L
                    )
                }
                BaseVoice.DEBUG_LOGGER.log(
                    "[MytrixVoice] Dropped group voice frame: speaker={} bytes={} sequence={}",
                    speakerId,
                    packet.data.size,
                    packet.sequenceNumber
                )
                return ServerActivation.Result.HANDLED
            }

            if (!isRateAllowed(speakerId, packet.data.size)) {
                selectedBeforeRouting?.let {
                    diagnosticsFor(GroupSourceKey(speakerId, it)).recordDrop(
                        "rate",
                        packet,
                        receivedAtMillis,
                        0,
                        0L,
                        0L
                    )
                }
                BaseVoice.DEBUG_LOGGER.log(
                    "[MytrixVoice] Dropped group voice frame by rate limit: speaker={} bytes={} sequence={}",
                    speakerId,
                    packet.data.size,
                    packet.sequenceNumber
                )
                return ServerActivation.Result.HANDLED
            }

            val routeStartNanos = System.nanoTime()
            val result = voiceServer.dynamicVoiceService.routeGroup(speakerId, packet)
            val routeElapsedMicros = (System.nanoTime() - routeStartNanos) / 1_000L
            val channelId = result.selectedChannel().orElse(null)
            if (result.discardPacket() || channelId == null || result.recipients().isEmpty()) {
                if (channelId != null) {
                    diagnosticsFor(GroupSourceKey(speakerId, channelId)).recordDrop(
                        if (result.discardPacket()) "route_discard" else "no_recipients",
                        packet,
                        receivedAtMillis,
                        result.recipients().size,
                        routeElapsedMicros,
                        0L
                    )
                }
                if (channelId != null) {
                    removeSource(GroupSourceKey(speakerId, channelId))
                } else if (result.discardPacket()) {
                    removePlayerSources(speakerId)
                }
                return ServerActivation.Result.HANDLED
            }

            val key = GroupSourceKey(speakerId, channelId)
            val requestedSourceStereo = packet.isStereo && activation.isStereoSupported
            val stereoDecision = stereoGuard.resolve(
                key,
                requestedSourceStereo,
                activeSpeakingByKey.contains(key)
            )
            if (stereoDecision.dropPacket) {
                diagnosticsFor(key).recordDrop(
                    "stereo_switch_active",
                    packet,
                    receivedAtMillis,
                    result.recipients().size,
                    routeElapsedMicros,
                    0L
                )
                BaseVoice.DEBUG_LOGGER.log(
                    "[MytrixVoice] Dropped group voice frame because stereo mode changed during active stream: speaker={} channel={} seq={} packet_stereo={} source_stereo={}",
                    speakerId,
                    channelId,
                    packet.sequenceNumber,
                    packet.isStereo,
                    stereoDecision.sourceStereo
                )
                return ServerActivation.Result.HANDLED
            }
            if (stereoDecision.recreateSource) {
                removeSource(key)
                stereoGuard.remember(key, stereoDecision.sourceStereo)
                BaseVoice.DEBUG_LOGGER.log(
                    "[MytrixVoice] Recreated group voice source after stereo mode changed between streams: speaker={} channel={} old_stereo={} new_stereo={}",
                    speakerId,
                    channelId,
                    stereoDecision.previousStereo,
                    stereoDecision.sourceStereo
                )
            }

            val source = getGroupSource(key, player, stereoDecision.sourceStereo)
            val recipients = toVoicePlayers(result.recipients())
            if (recipients.isEmpty()) return ServerActivation.Result.HANDLED

            source.players = recipients
            markDirtyForNewRecipients(source, key, result.recipients())

            val sourcePacket = SourceAudioPacket(
                packet.sequenceNumber,
                source.state.toByte(),
                packet.data.copyOf(),
                source.id,
                GROUP_PACKET_DISTANCE
            )

            if (activeSpeakingByKey.add(key)) {
                voiceServer.dynamicVoiceService.notifyGroupTransmissionStart(speakerId, channelId)
            }
            val sendStartNanos = System.nanoTime()
            source.sendAudioPacket(sourcePacket, null)
            val sendElapsedMicros = (System.nanoTime() - sendStartNanos) / 1_000L
            diagnosticsFor(key).recordForwarded(
                packet,
                source.id,
                result.recipients(),
                receivedAtMillis,
                routeElapsedMicros,
                sendElapsedMicros,
                (System.nanoTime() - receivedAtNanos) / 1_000L,
                packet.isStereo,
                stereoDecision.sourceStereo,
                sourcePacket.sourceState
            )
            return ServerActivation.Result.HANDLED
        }

        private fun onActivationEnd(player: VoicePlayer, packet: PlayerAudioEndPacket): ServerActivation.Result {
            val speakerId = player.instance.uuid

            val result = voiceServer.dynamicVoiceService.routeGroup(speakerId, packet)
            val channelId = result.selectedChannel().orElse(null)
            if (channelId == null) {
                removePlayerSources(speakerId)
                return ServerActivation.Result.HANDLED
            }

            val key = GroupSourceKey(speakerId, channelId)
            val source = sourceByKey[key] ?: return ServerActivation.Result.HANDLED
            val recipients = toVoicePlayers(result.recipients())
            if (recipients.isNotEmpty()) {
                source.players = recipients
                source.sendPacket(SourceAudioEndPacket(source.id, packet.sequenceNumber))
            }
            if (activeSpeakingByKey.remove(key)) {
                voiceServer.dynamicVoiceService.notifyGroupTransmissionStop(speakerId, channelId, "client")
            }

            return ServerActivation.Result.HANDLED
        }

        private fun getGroupSource(
            key: GroupSourceKey,
            speaker: VoicePlayer,
            isStereo: Boolean
        ): ServerBroadcastSource {
            val source = sourceByKey.getOrPut(key) {
                sourceLine.createBroadcastSource(isStereo) { newSource ->
                    newSource.sender = speaker
                    newSource.isCameraRelative = true
                    newSource.setIconVisible(true)
                }
            }

            source.sender = speaker
            source.isCameraRelative = true
            source.setStereo(isStereo)
            return source
        }

        private fun markDirtyForNewRecipients(
            source: ServerBroadcastSource,
            key: GroupSourceKey,
            recipients: Set<UUID>
        ) {
            val sentRecipients = sourceInfoRecipientsByKey.computeIfAbsent(key) {
                ConcurrentHashMap.newKeySet()
            }

            if (sentRecipients != recipients) {
                sentRecipients.clear()
                sentRecipients.addAll(recipients)
                source.setDirty()
            }
        }

        private fun toVoicePlayers(playerIds: Set<UUID>): List<VoicePlayer> =
            playerIds.mapNotNull { playerId ->
                voiceServer.playerManager
                    .getPlayerById(playerId, false)
                    .getOrNull()
            }

        private fun isPayloadAllowed(packet: PlayerAudioPacket): Boolean {
            val maxBytes = voiceServer.config?.voice()?.mtuSize() ?: return true
            return packet.data.size <= maxBytes
        }

        private fun isRateAllowed(speakerId: UUID, payloadBytes: Int): Boolean {
            val maxPayloadBytes = voiceServer.config?.voice()?.mtuSize() ?: 2048
            val maxBytesPerSecond = (maxPayloadBytes * GROUP_PACKETS_PER_SECOND_LIMIT * GROUP_BYTES_PER_SECOND_MULTIPLIER)
                .coerceAtLeast(maxPayloadBytes)
            val now = System.currentTimeMillis()
            val state = rateBySpeaker.computeIfAbsent(speakerId) {
                PacketRateState(now)
            }

            synchronized(state) {
                if (now - state.windowStartMillis >= 1_000L) {
                    state.windowStartMillis = now
                    state.packetCount = 0
                    state.byteCount = 0
                }

                if (state.packetCount >= GROUP_PACKETS_PER_SECOND_LIMIT) return false
                if (state.byteCount + payloadBytes > maxBytesPerSecond) return false

                state.packetCount += 1
                state.byteCount += payloadBytes
                return true
            }
        }

        private fun removeSource(key: GroupSourceKey) {
            sourceInfoRecipientsByKey.remove(key)
            diagnosticsByKey.remove(key)
            stereoGuard.remove(key)
            sourceByKey.remove(key)?.remove()
            if (activeSpeakingByKey.remove(key)) {
                voiceServer.dynamicVoiceService.notifyGroupTransmissionStop(key.speakerId, key.channelId, "timeout")
            }
        }

        private fun diagnosticsFor(key: GroupSourceKey): GroupAudioDiagnostics =
            diagnosticsByKey.computeIfAbsent(key) {
                GroupAudioDiagnostics(key)
            }

        private data class GroupSourceKey(
            val speakerId: UUID,
            val channelId: VoiceChannelId
        )

        private class GroupAudioDiagnostics(
            private val key: GroupSourceKey
        ) {

            private var nextLogAtMillis = 0L
            private var framesIn = 0L
            private var framesForwarded = 0L
            private var framesDropped = 0L
            private var duplicateOrLate = 0L
            private var gapEvents = 0L
            private var estimatedLostPackets = 0L
            private var maxGap = 0L
            private var lastSequenceNumber = -1L
            private var maxRecipients = 0
            private var maxPayloadBytes = 0
            private var lastDropReason = "none"
            private var maxRouteMicros = 0L
            private var maxSendMicros = 0L
            private var maxTotalMicros = 0L
            private var lastRecipientPreview = ""
            private var lastThread = ""
            private var lastReceivedAtMillis = 0L
            private var lastForwardedAtMillis = 0L
            private var lastPayloadCrc = 0L
            private var lastPacketStereo = false
            private var lastSourceStereo = false
            private var lastSourceState: Byte = 0

            @Synchronized
            fun recordForwarded(
                packet: PlayerAudioPacket,
                sourceId: UUID,
                recipients: Set<UUID>,
                receivedAtMillis: Long,
                routeMicros: Long,
                sendMicros: Long,
                totalMicros: Long,
                packetStereo: Boolean,
                sourceStereo: Boolean,
                sourceState: Byte
            ) {
                framesIn++
                framesForwarded++
                recordSequence(packet.sequenceNumber)
                maxRecipients = maxRecipients.coerceAtLeast(recipients.size)
                maxPayloadBytes = maxPayloadBytes.coerceAtLeast(packet.data.size)
                maxRouteMicros = maxRouteMicros.coerceAtLeast(routeMicros)
                maxSendMicros = maxSendMicros.coerceAtLeast(sendMicros)
                maxTotalMicros = maxTotalMicros.coerceAtLeast(totalMicros)
                lastRecipientPreview = recipients.joinToString(limit = 8, truncated = "...")
                lastThread = Thread.currentThread().name
                lastReceivedAtMillis = receivedAtMillis
                lastForwardedAtMillis = System.currentTimeMillis()
                lastPayloadCrc = checksum(packet.data)
                lastPacketStereo = packetStereo
                lastSourceStereo = sourceStereo
                lastSourceState = sourceState

                maybeLog("forwarded", sourceId, packet.sequenceNumber, packet.data.size, recipients.size, false)
            }

            @Synchronized
            fun recordDrop(
                reason: String,
                packet: PlayerAudioPacket,
                receivedAtMillis: Long,
                recipients: Int,
                routeMicros: Long,
                sendMicros: Long
            ) {
                framesIn++
                framesDropped++
                lastDropReason = reason
                recordSequence(packet.sequenceNumber)
                maxRecipients = maxRecipients.coerceAtLeast(recipients)
                maxPayloadBytes = maxPayloadBytes.coerceAtLeast(packet.data.size)
                maxRouteMicros = maxRouteMicros.coerceAtLeast(routeMicros)
                maxSendMicros = maxSendMicros.coerceAtLeast(sendMicros)
                lastThread = Thread.currentThread().name
                lastReceivedAtMillis = receivedAtMillis
                lastPayloadCrc = checksum(packet.data)
                lastPacketStereo = packet.isStereo

                maybeLog("drop=$reason", null, packet.sequenceNumber, packet.data.size, recipients, true)
            }

            private fun recordSequence(sequenceNumber: Long) {
                if (lastSequenceNumber >= 0L) {
                    if (sequenceNumber <= lastSequenceNumber) {
                        duplicateOrLate++
                    } else if (sequenceNumber > lastSequenceNumber + 1L) {
                        val gap = sequenceNumber - lastSequenceNumber - 1L
                        gapEvents++
                        estimatedLostPackets += gap
                        maxGap = maxGap.coerceAtLeast(gap)
                    }
                }
                lastSequenceNumber = sequenceNumber
            }

            private fun maybeLog(
                reason: String,
                sourceId: UUID?,
                sequenceNumber: Long,
                payloadBytes: Int,
                recipients: Int,
                force: Boolean
            ) {
                if (!diagnosticsEnabled() || !matchesDiagnosticsFilter(key)) return

                val now = System.currentTimeMillis()
                if (!force && now < nextLogAtMillis) return
                nextLogAtMillis = now + GROUP_DIAGNOSTIC_INTERVAL_MS

                BaseVoice.DEBUG_LOGGER.log(
                    "[MytrixVoice] Server group audio diag reason={} speaker={} channel={} source={} seq={} bytes={} crc={} packet_stereo={} source_stereo={} source_state={} recipients={} recipient_sample=[{}] frames_in={} forwarded={} dropped={} last_drop={} late_or_duplicate={} gaps={} estimated_lost={} max_gap={} max_recipients={} max_bytes={} route_us={} send_us={} total_us={} received_at={} forwarded_at={} thread={}",
                    reason,
                    key.speakerId,
                    key.channelId,
                    sourceId,
                    sequenceNumber,
                    payloadBytes,
                    lastPayloadCrc,
                    lastPacketStereo,
                    lastSourceStereo,
                    lastSourceState,
                    recipients,
                    lastRecipientPreview,
                    framesIn,
                    framesForwarded,
                    framesDropped,
                    lastDropReason,
                    duplicateOrLate,
                    gapEvents,
                    estimatedLostPackets,
                    maxGap,
                    maxRecipients,
                    maxPayloadBytes,
                    maxRouteMicros,
                    maxSendMicros,
                    maxTotalMicros,
                    lastReceivedAtMillis,
                    lastForwardedAtMillis,
                    lastThread
                )
            }
        }

        private data class PacketRateState(
            var windowStartMillis: Long,
            var packetCount: Int = 0,
            var byteCount: Int = 0
        )

        companion object {
            private const val GROUP_PACKET_DISTANCE: Short = 0
            private const val GROUP_PACKETS_PER_SECOND_LIMIT = 120
            private const val GROUP_BYTES_PER_SECOND_MULTIPLIER = 2
            private const val GROUP_DIAGNOSTIC_INTERVAL_MS = 5_000L

            private fun checksum(data: ByteArray): Long {
                val crc = CRC32()
                crc.update(data, 0, data.size)
                return crc.value
            }

            private fun diagnosticsEnabled(): Boolean =
                java.lang.Boolean.parseBoolean(
                    System.getProperty("mytrixvoice.groupAudioDiag.enabled", "true")
                )

            private fun matchesDiagnosticsFilter(key: GroupSourceKey): Boolean {
                val playerFilter = System.getProperty("mytrixvoice.groupAudioDiag.player")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                if (playerFilter != null && !key.speakerId.toString().equals(playerFilter, ignoreCase = true)) {
                    return false
                }

                val groupFilter = System.getProperty("mytrixvoice.groupAudioDiag.group")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                if (groupFilter != null && !key.channelId.toString().contains(groupFilter, ignoreCase = true)) {
                    return false
                }

                return true
            }
        }
    }
}
