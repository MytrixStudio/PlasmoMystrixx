import net.mytrix.voice.api.DynamicVoiceApi
import net.mytrix.voice.api.MytrixVoiceServices
import net.mytrix.voice.api.RestrictionTarget
import net.mytrix.voice.api.RoutingMode
import net.mytrix.voice.api.VoiceChatApi
import net.mytrix.voice.api.VoiceChannelId
import net.mytrix.voice.api.VoiceChannelOptions
import net.mytrix.voice.api.VoiceChannelPolicy
import net.mytrix.voice.api.VoiceMemberDefinition
import net.mytrix.voice.api.VoiceRestrictionRequest
import net.mytrix.voice.api.VoiceRestrictionType
import net.mytrix.voice.api.VoiceSessionId
import net.mytrix.voice.api.VoiceSessionOptions
import net.mytrix.voice.api.channel.MembershipStatus
import net.mytrix.voice.api.channel.RegistrationStatus
import net.mytrix.voice.api.channel.VoiceChannelConfig
import net.mytrix.voice.api.channel.VoiceChannelDefinition
import net.mytrix.voice.api.channel.VoiceChannelPermission
import net.mytrix.voice.api.channel.VoiceChannelId as PublicVoiceChannelId
import net.mytrix.voice.api.event.VoiceChannelMemberJoinedEvent
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import su.plo.slib.api.logging.McLogger
import su.plo.slib.api.logging.McLoggerFactory
import su.plo.slib.api.position.Pos3d
import su.plo.slib.api.server.entity.player.McServerPlayer
import su.plo.voice.api.server.connection.UdpServerConnectionManager
import su.plo.voice.api.server.player.VoiceServerPlayer
import su.plo.voice.api.server.socket.UdpServer
import su.plo.voice.api.server.socket.UdpServerConnection
import su.plo.voice.proto.data.audio.capture.VoiceActivation
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket
import su.plo.voice.server.BaseVoiceServer
import su.plo.voice.server.dynamic.DynamicVoiceService
import java.time.Duration
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicVoiceServiceTest {

    @Test
    fun `service is discoverable before backend is ready`() {
        val server = mock<BaseVoiceServer> {
            on { udpServer } doReturn Optional.empty()
        }
        val service = DynamicVoiceService(server)

        service.registerService()

        val found = MytrixVoiceServices.find(DynamicVoiceApi::class.java)
        assertTrue(found.isPresent)
        assertTrue(found.get() === service)
        assertFalse(service.isReady())
        val sessionId = VoiceSessionId.of("early", "setup")
        service.createSession(sessionId, VoiceSessionOptions.memoryOnly())
        assertTrue(service.findSession(sessionId).isPresent)

        service.shutdown()
    }

    @Test
    fun `creates many dynamic sessions and channels without crossing resources`() {
        val service = service()

        repeat(100) { sessionIndex ->
            val sessionId = VoiceSessionId.of("test", "session_$sessionIndex")
            service.createSession(sessionId, VoiceSessionOptions.memoryOnly())

            repeat(3) { channelIndex ->
                val channelId = VoiceChannelId.of(sessionId, "channel_$channelIndex")
                service.createChannel(sessionId, channelId, options(priority = channelIndex))
                if (channelIndex == 1) service.deleteChannel(channelId)
            }
        }

        assertEquals(100, service.inspectSessions().size)
        service.inspectSessions().forEach { session ->
            assertEquals(2, session.channels().size)
            assertTrue(session.channels().all { it.sessionId() == session.id() })
        }
    }

    @Test
    fun `sync members is idempotent and rebuilds indexes`() {
        val service = service()
        val sessionId = VoiceSessionId.of("test", "sync")
        val channelId = VoiceChannelId.of(sessionId, "party")
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()

        service.createSession(sessionId, VoiceSessionOptions.memoryOnly())
        service.createChannel(sessionId, channelId, options())
        service.syncMembers(channelId, listOf(VoiceMemberDefinition.participant(a), VoiceMemberDefinition.participant(b)))
        service.syncMembers(channelId, listOf(VoiceMemberDefinition.participant(a), VoiceMemberDefinition.participant(b)))

        assertEquals(setOf(a, b), service.inspectChannel(channelId).members())

        service.syncMembers(channelId, listOf(VoiceMemberDefinition.participant(a), VoiceMemberDefinition.participant(c)))

        assertEquals(setOf(a, c), service.inspectChannel(channelId).members())
        assertTrue(service.inspectPlayer(a).channels().contains(channelId))
        assertFalse(service.inspectPlayer(b).channels().contains(channelId))
        assertTrue(service.inspectPlayer(c).channels().contains(channelId))
    }

    @Test
    fun `remove member is idempotent after channel was already deleted`() {
        val service = service()
        val sessionId = VoiceSessionId.of("test", "cleanup")
        val channelId = VoiceChannelId.of(sessionId, "party")
        val playerId = UUID.randomUUID()

        service.createSession(sessionId, VoiceSessionOptions.memoryOnly())
        service.createChannel(sessionId, channelId, options(active = true))
        service.addMember(channelId, VoiceMemberDefinition.participant(playerId))
        assertTrue(service.selectGroupChannel(playerId, channelId))

        service.deleteChannel(channelId)
        service.removeMember(channelId, playerId)
        service.clearGroupChannelSelection(playerId)

        assertTrue(service.selectedGroupChannel(playerId).isEmpty)
        assertFalse(service.inspectPlayer(playerId).channels().contains(channelId))
    }

    @Test
    fun `exclusive private channels do not leak between groups`() {
        val service = service()
        val sessionId = VoiceSessionId.of("test", "exclusive")
        val channelA = VoiceChannelId.of(sessionId, "group_a")
        val channelB = VoiceChannelId.of(sessionId, "group_b")
        val a1 = UUID.randomUUID()
        val a2 = UUID.randomUUID()
        val b1 = UUID.randomUUID()
        val b2 = UUID.randomUUID()
        connect(service, a1, a2, b1, b2)

        service.createSession(sessionId, VoiceSessionOptions.memoryOnly())
        service.createChannel(sessionId, channelA, options(priority = 500, active = true))
        service.createChannel(sessionId, channelB, options(priority = 500, active = true))
        service.syncMembers(channelA, listOf(VoiceMemberDefinition.participant(a1), VoiceMemberDefinition.participant(a2)))
        service.syncMembers(channelB, listOf(VoiceMemberDefinition.participant(b1), VoiceMemberDefinition.participant(b2)))

        val result = service.route(a1, packet())

        assertTrue(result.cancelDefaultRoute())
        assertFalse(result.discardPacket())
        assertEquals(setOf(a2), result.recipients())
        assertEquals(Optional.of(channelA), result.selectedChannel())
    }

    @Test
    fun `restriction handles do not overwrite each other`() {
        val service = service()
        val sessionId = VoiceSessionId.of("test", "restrictions")
        val channelId = VoiceChannelId.of(sessionId, "group")
        val speaker = UUID.randomUUID()
        val listener = UUID.randomUUID()
        connect(service, speaker, listener)

        service.createSession(sessionId, VoiceSessionOptions.memoryOnly())
        service.createChannel(sessionId, channelId, options(active = true))
        service.syncMembers(channelId, listOf(VoiceMemberDefinition.participant(speaker), VoiceMemberDefinition.participant(listener)))

        val first = service.applyRestriction(
            VoiceRestrictionRequest.builder()
                .ownerNamespace("test")
                .reason("intro")
                .target(RestrictionTarget.channel(channelId))
                .type(VoiceRestrictionType.BLOCK_BOTH)
                .priority(1000)
                .build(),
        )
        val second = service.applyRestriction(
            VoiceRestrictionRequest.builder()
                .ownerNamespace("other")
                .reason("moderation")
                .target(RestrictionTarget.channel(channelId))
                .type(VoiceRestrictionType.BLOCK_BOTH)
                .priority(1000)
                .build(),
        )

        assertTrue(service.route(speaker, packet()).discardPacket())
        service.removeRestriction(first)
        assertTrue(service.route(speaker, packet()).discardPacket())
        service.removeRestriction(second)
        assertFalse(service.route(speaker, packet()).discardPacket())
        assertEquals(setOf(listener), service.route(speaker, packet()).recipients())
    }

    @Test
    fun `highest priority exclusive channel wins deterministically`() {
        val service = service()
        val sessionId = VoiceSessionId.of("test", "priority")
        val low = VoiceChannelId.of(sessionId, "low")
        val high = VoiceChannelId.of(sessionId, "high")
        val speaker = UUID.randomUUID()
        val lowListener = UUID.randomUUID()
        val highListener = UUID.randomUUID()
        connect(service, speaker, lowListener, highListener)

        service.createSession(sessionId, VoiceSessionOptions.memoryOnly())
        service.createChannel(sessionId, low, options(priority = 500, active = true))
        service.createChannel(sessionId, high, options(priority = 1000, active = true))
        service.syncMembers(low, listOf(VoiceMemberDefinition.participant(speaker), VoiceMemberDefinition.participant(lowListener)))
        service.syncMembers(high, listOf(VoiceMemberDefinition.participant(speaker), VoiceMemberDefinition.participant(highListener)))

        val result = service.route(speaker, packet())

        assertEquals(Optional.of(high), result.selectedChannel())
        assertEquals(setOf(highListener), result.recipients())
    }

    @Test
    fun `channel maximum distance filters dynamic recipients`() {
        val world = mockWorld("world")
        val speaker = UUID.randomUUID()
        val near = UUID.randomUUID()
        val far = UUID.randomUUID()
        val service = serviceWithPlayers(
            mapOf(
                speaker to MockServerPlayer(world, Pos3d(0.0, 0.0, 0.0), speaker),
                near to MockServerPlayer(world, Pos3d(6.0, 0.0, 0.0), near),
                far to MockServerPlayer(world, Pos3d(30.0, 0.0, 0.0), far),
            ),
        )
        val sessionId = VoiceSessionId.of("test", "distance")
        val channelId = VoiceChannelId.of(sessionId, "near_group")

        service.createSession(sessionId, VoiceSessionOptions.memoryOnly())
        service.createChannel(
            sessionId,
            channelId,
            VoiceChannelOptions.builder()
                .policy(VoiceChannelPolicy(RoutingMode.PRIVATE_CHANNEL, true, false, false, 500, 10.0, true))
                .active(true)
                .build(),
        )
        service.syncMembers(
            channelId,
            listOf(
                VoiceMemberDefinition.participant(speaker),
                VoiceMemberDefinition.participant(near),
                VoiceMemberDefinition.participant(far),
            ),
        )

        val result = service.route(speaker, packet())

        assertTrue(result.cancelDefaultRoute())
        assertEquals(setOf(near), result.recipients())
    }

    @Test
    fun `channel zero maximum distance keeps far dynamic recipients`() {
        val world = mockWorld("world")
        val speaker = UUID.randomUUID()
        val far = UUID.randomUUID()
        val service = serviceWithPlayers(
            mapOf(
                speaker to MockServerPlayer(world, Pos3d(0.0, 0.0, 0.0), speaker),
                far to MockServerPlayer(world, Pos3d(500.0, 0.0, 0.0), far),
            ),
        )
        val sessionId = VoiceSessionId.of("test", "global_distance")
        val channelId = VoiceChannelId.of(sessionId, "group")

        service.createSession(sessionId, VoiceSessionOptions.memoryOnly())
        service.createChannel(
            sessionId,
            channelId,
            VoiceChannelOptions.builder()
                .policy(VoiceChannelPolicy(RoutingMode.PRIVATE_CHANNEL, true, false, false, 500, 0.0, true))
                .active(true)
                .build(),
        )
        service.syncMembers(
            channelId,
            listOf(
                VoiceMemberDefinition.participant(speaker),
                VoiceMemberDefinition.participant(far),
            ),
        )

        val result = service.route(speaker, packet())

        assertTrue(result.cancelDefaultRoute())
        assertEquals(setOf(far), result.recipients())
    }

    @Test
    fun `group route ignores distance and dimension`() {
        val overworld = mockWorld("overworld")
        val nether = mockWorld("the_nether")
        val speaker = UUID.randomUUID()
        val farSameGroup = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        val service = serviceWithPlayers(
            mapOf(
                speaker to MockServerPlayer(overworld, Pos3d(0.0, 64.0, 0.0), speaker),
                farSameGroup to MockServerPlayer(nether, Pos3d(5000.0, 64.0, 5000.0), farSameGroup),
                outsider to MockServerPlayer(overworld, Pos3d(1.0, 64.0, 1.0), outsider),
            ),
        )
        val sessionId = VoiceSessionId.of("test", "group_global")
        val channelId = VoiceChannelId.of(sessionId, "group")

        service.createSession(sessionId, VoiceSessionOptions.memoryOnly())
        service.createChannel(
            sessionId,
            channelId,
            VoiceChannelOptions.builder()
                .policy(VoiceChannelPolicy(RoutingMode.PRIVATE_CHANNEL, true, false, false, 500, 10.0, false))
                .active(true)
                .build(),
        )
        service.syncMembers(
            channelId,
            listOf(
                VoiceMemberDefinition.participant(speaker),
                VoiceMemberDefinition.participant(farSameGroup),
            ),
        )

        val proximityResult = service.route(speaker, packet())
        val groupResult = service.routeGroup(speaker, inputPacket())

        assertTrue(proximityResult.recipients().isEmpty())
        assertEquals(setOf(farSameGroup), groupResult.recipients())
        assertFalse(groupResult.recipients().contains(outsider))
        assertEquals(Optional.of(channelId), groupResult.selectedChannel())
    }

    @Test
    fun `group route discards when speaker has no group`() {
        val service = service()
        val speaker = UUID.randomUUID()
        connect(service, speaker)

        val result = service.routeGroup(speaker, inputPacket())

        assertTrue(result.discardPacket())
        assertTrue(result.recipients().isEmpty())
        assertTrue(result.selectedChannel().isEmpty)
    }

    @Test
    fun `public api registers distance free group channel and routes without distance`() {
        val overworld = mockWorld("overworld")
        val nether = mockWorld("the_nether")
        val speaker = UUID.randomUUID()
        val listener = UUID.randomUUID()
        val outsider = UUID.randomUUID()
        val service = serviceWithPlayers(
            mapOf(
                speaker to MockServerPlayer(overworld, Pos3d(0.0, 64.0, 0.0), speaker),
                listener to MockServerPlayer(nether, Pos3d(5000.0, 64.0, 5000.0), listener),
                outsider to MockServerPlayer(overworld, Pos3d(1.0, 64.0, 1.0), outsider),
            ),
        )
        val api = VoiceChatApi.server().orElseThrow()
        val publicId = PublicVoiceChannelId.of("partytest", "party/one")

        val registration = api.channels().register(
            VoiceChannelDefinition.builder()
                .id(publicId)
                .displayName("Party One")
                .config(VoiceChannelConfig.distanceFreeGroup())
                .build(),
        )
        assertEquals(RegistrationStatus.SUCCESS, registration.status())
        val handle = registration.handle().orElseThrow()
        assertEquals(MembershipStatus.ADDED, handle.addMember(speaker).status())
        assertEquals(MembershipStatus.ADDED, handle.addMember(listener).status())
        assertTrue(api.transmissions().selectChannel(speaker, publicId).successful())

        val groupResult = service.routeGroup(speaker, inputPacket())

        assertEquals(setOf(listener), groupResult.recipients())
        assertFalse(groupResult.recipients().contains(outsider))
        assertEquals(Optional.of(publicId), api.transmissions().selectedChannel(speaker))
        assertTrue(groupResult.selectedChannel().isPresent)
    }

    @Test
    fun `public api rejects duplicate channel and member limit`() {
        service()
        val api = VoiceChatApi.server().orElseThrow()
        val publicId = PublicVoiceChannelId.of("apitest", "limited")
        val definition = VoiceChannelDefinition.builder()
            .id(publicId)
            .config(VoiceChannelConfig.builder().maxMembers(1).build())
            .build()

        val first = api.channels().register(definition)
        val duplicate = api.channels().register(definition)
        val handle = first.handle().orElseThrow()
        val firstPlayer = UUID.randomUUID()
        val secondPlayer = UUID.randomUUID()

        assertEquals(RegistrationStatus.SUCCESS, first.status())
        assertEquals(RegistrationStatus.ALREADY_REGISTERED, duplicate.status())
        assertEquals(MembershipStatus.ADDED, handle.addMember(firstPlayer).status())
        assertEquals(MembershipStatus.MEMBER_LIMIT_REACHED, handle.addMember(secondPlayer).status())
    }

    @Test
    fun `public api selection validates membership and speak permission`() {
        service()
        val api = VoiceChatApi.server().orElseThrow()
        val publicId = PublicVoiceChannelId.of("permtest", "leaders")
        val speaker = UUID.randomUUID()
        val notMember = UUID.randomUUID()
        val blocked = UUID.randomUUID()
        val definition = VoiceChannelDefinition.builder()
            .id(publicId)
            .permission(object : VoiceChannelPermission {
                override fun canSpeak(
                    player: net.mytrix.voice.api.channel.VoicePlayerContext,
                    channel: net.mytrix.voice.api.channel.VoiceChannelContext,
                ): Boolean = player.playerId() != blocked
            })
            .build()
        val handle = api.channels().register(definition).handle().orElseThrow()

        handle.addMember(speaker)
        handle.addMember(blocked)

        assertTrue(api.transmissions().selectChannel(speaker, publicId).successful())
        assertEquals(net.mytrix.voice.api.transmission.TransmissionSelectionStatus.NOT_A_MEMBER, api.transmissions().selectChannel(notMember, publicId).status())
        assertEquals(net.mytrix.voice.api.transmission.TransmissionSelectionStatus.CANNOT_SPEAK, api.transmissions().selectChannel(blocked, publicId).status())
    }

    @Test
    fun `public api event subscription is removable and listener exceptions are isolated`() {
        service()
        val api = VoiceChatApi.server().orElseThrow()
        val publicId = PublicVoiceChannelId.of("eventtest", "party")
        val handled = AtomicInteger()
        api.events().subscribe(VoiceChannelMemberJoinedEvent::class.java) {
            throw IllegalStateException("broken listener")
        }
        val subscription = api.events().subscribe(VoiceChannelMemberJoinedEvent::class.java) {
            handled.incrementAndGet()
        }
        val handle = api.channels().register(
            VoiceChannelDefinition.builder()
                .id(publicId)
                .build(),
        ).handle().orElseThrow()

        handle.addMember(UUID.randomUUID())
        subscription.close()
        handle.addMember(UUID.randomUUID())

        assertEquals(1, handled.get())
        assertFalse(subscription.active())
    }

    @Test
    fun `channel restriction does not block another higher priority channel`() {
        val service = service()
        val sessionId = VoiceSessionId.of("test", "scoped_restriction")
        val muted = VoiceChannelId.of(sessionId, "muted")
        val live = VoiceChannelId.of(sessionId, "live")
        val speaker = UUID.randomUUID()
        val mutedListener = UUID.randomUUID()
        val liveListener = UUID.randomUUID()
        connect(service, speaker, mutedListener, liveListener)

        service.createSession(sessionId, VoiceSessionOptions.memoryOnly())
        service.createChannel(sessionId, muted, options(priority = 500, active = true))
        service.createChannel(sessionId, live, options(priority = 1000, active = true))
        service.syncMembers(muted, listOf(VoiceMemberDefinition.participant(speaker), VoiceMemberDefinition.participant(mutedListener)))
        service.syncMembers(live, listOf(VoiceMemberDefinition.participant(speaker), VoiceMemberDefinition.participant(liveListener)))
        service.applyRestriction(
            VoiceRestrictionRequest.builder()
                .ownerNamespace("test")
                .target(RestrictionTarget.channel(muted))
                .type(VoiceRestrictionType.BLOCK_BOTH)
                .priority(1000)
                .build(),
        )

        val result = service.route(speaker, packet())

        assertFalse(result.discardPacket())
        assertEquals(Optional.of(live), result.selectedChannel())
        assertEquals(setOf(liveListener), result.recipients())
    }

    @Test
    fun `closing a session cleans only its own resources`() {
        val service = service()
        val first = VoiceSessionId.of("test", "first")
        val second = VoiceSessionId.of("test", "second")
        val firstChannel = VoiceChannelId.of(first, "group")
        val secondChannel = VoiceChannelId.of(second, "group")

        service.createSession(first, VoiceSessionOptions.memoryOnly())
        service.createSession(second, VoiceSessionOptions.memoryOnly())
        service.createChannel(first, firstChannel, options(active = true))
        service.createChannel(second, secondChannel, options(active = true))
        service.applyRestriction(
            VoiceRestrictionRequest.builder()
                .ownerNamespace("test")
                .target(RestrictionTarget.session(first))
                .type(VoiceRestrictionType.BLOCK_BOTH)
                .build(),
        )

        service.closeSession(first)

        assertTrue(service.findSession(first).isEmpty)
        assertTrue(service.findChannel(firstChannel).isEmpty)
        assertTrue(service.findSession(second).isPresent)
        assertTrue(service.findChannel(secondChannel).isPresent)
        assertTrue(service.inspectRestrictions().none { it.target() == RestrictionTarget.session(first) })
    }

    @Test
    fun `temporary restrictions expire automatically`() {
        val service = service()
        val playerId = UUID.randomUUID()
        service.applyRestriction(
            VoiceRestrictionRequest.builder()
                .ownerNamespace("test")
                .target(RestrictionTarget.player(playerId))
                .type(VoiceRestrictionType.BLOCK_BOTH)
                .duration(Duration.ofMillis(10))
                .build(),
        )

        Thread.sleep(30)

        assertTrue(service.inspectPlayer(playerId).restrictions().isEmpty())
    }

    private fun service(): DynamicVoiceService {
        McLoggerFactory.supplier = object : McLoggerFactory.Supplier {
            override fun createLogger(name: String): McLogger = JavaLogger(name)
        }
        val udpManager = mock<UdpServerConnectionManager> {
            on { connections } doReturn emptyList()
            on { getConnectionByPlayerId(any()) } doReturn Optional.empty()
        }
        val server = mock<BaseVoiceServer> {
            on { udpConnectionManager } doReturn udpManager
            on { udpServer } doReturn Optional.of(mock<UdpServer>())
            on { removeProximitySourceOwnedBy(any()) } doReturn false
        }
        return DynamicVoiceService(server).also { it.initialize() }
    }

    private fun serviceWithPlayers(players: Map<UUID, McServerPlayer>): DynamicVoiceService {
        McLoggerFactory.supplier = object : McLoggerFactory.Supplier {
            override fun createLogger(name: String): McLogger = JavaLogger(name)
        }
        val connectionByPlayer = players.mapValues { (_, mcPlayer) ->
            val voicePlayer = mock<VoiceServerPlayer> {
                on { instance } doReturn mcPlayer
            }
            mock<UdpServerConnection> {
                on { player } doReturn voicePlayer
            }
        }
        val udpManager = mock<UdpServerConnectionManager> {
            on { connections } doReturn connectionByPlayer.values.toList()
            on { getConnectionByPlayerId(any()) } doAnswer { invocation ->
                Optional.ofNullable(connectionByPlayer[invocation.getArgument(0)])
            }
        }
        val server = mock<BaseVoiceServer> {
            on { udpConnectionManager } doReturn udpManager
            on { udpServer } doReturn Optional.of(mock<UdpServer>())
            on { removeProximitySourceOwnedBy(any()) } doReturn false
        }
        return DynamicVoiceService(server).also { it.initialize() }
    }

    private fun connect(service: DynamicVoiceService, vararg players: UUID) {
        for (playerId in players) {
            val mcPlayer = mock<McServerPlayer> {
                on { uuid } doReturn playerId
            }
            val voicePlayer = mock<VoiceServerPlayer> {
                on { instance } doReturn mcPlayer
            }
            val connection = mock<UdpServerConnection> {
                on { player } doReturn voicePlayer
            }
            service.onUdpClientConnected(su.plo.voice.api.server.event.connection.UdpClientConnectedEvent(connection))
        }
    }

    private fun options(priority: Int = 500, active: Boolean = false): VoiceChannelOptions =
        VoiceChannelOptions.builder()
            .policy(VoiceChannelPolicy(RoutingMode.PRIVATE_CHANNEL, true, false, false, priority, 0.0, false))
            .active(active)
            .build()

    private fun packet(): su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket =
        su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket(1L, 0, byteArrayOf(1), UUID.randomUUID(), 16)

    @Suppress("unused")
    private fun inputPacket(): PlayerAudioPacket =
        PlayerAudioPacket(1L, byteArrayOf(1), VoiceActivation.PROXIMITY_ID, 16, false)
}
