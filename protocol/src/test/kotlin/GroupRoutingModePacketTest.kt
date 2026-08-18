import com.google.common.io.ByteStreams
import su.plo.voice.proto.packets.PacketDirection
import su.plo.voice.proto.packets.tcp.PacketTcpCodec
import su.plo.voice.proto.packets.tcp.clientbound.ClientPacketTcpHandler
import su.plo.voice.proto.packets.tcp.clientbound.GroupRoutingModePacket
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GroupRoutingModePacketTest {

    @Test
    fun `group routing mode packet round trips as clientbound`() {
        val encoded = requireNotNull(PacketTcpCodec.encode(GroupRoutingModePacket(true)))
        val decoded = PacketTcpCodec.decode<ClientPacketTcpHandler>(
            ByteStreams.newDataInput(encoded),
            PacketDirection.CLIENT,
        ).orElseThrow()

        val routingPacket = assertIs<GroupRoutingModePacket>(decoded)
        assertTrue(routingPacket.isReplaceProximityWithGroup)
    }
}
