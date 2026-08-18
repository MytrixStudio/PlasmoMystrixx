package su.plo.voice.proto.packets.tcp.clientbound;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import su.plo.voice.proto.packets.Packet;

import java.io.IOException;

/**
 * Synchronizes whether the client must route the normal microphone activation
 * through the native group activation instead of proximity voice.
 */
@NoArgsConstructor
@AllArgsConstructor
@ToString
public final class GroupRoutingModePacket implements Packet<ClientPacketTcpHandler> {

    @Getter
    private boolean replaceProximityWithGroup;

    @Override
    public void read(ByteArrayDataInput in) throws IOException {
        this.replaceProximityWithGroup = in.readBoolean();
    }

    @Override
    public void write(ByteArrayDataOutput out) throws IOException {
        out.writeBoolean(replaceProximityWithGroup);
    }

    @Override
    public void handle(ClientPacketTcpHandler handler) {
        handler.handle(this);
    }
}
