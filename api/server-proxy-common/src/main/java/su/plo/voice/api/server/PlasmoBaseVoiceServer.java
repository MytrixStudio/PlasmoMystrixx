package su.plo.voice.api.server;

import org.jetbrains.annotations.NotNull;
import su.plo.slib.api.McLib;
import su.plo.voice.api.PlasmoVoice;
import su.plo.voice.api.audio.codec.AudioDecoder;
import su.plo.voice.api.audio.codec.AudioEncoder;
import su.plo.voice.api.encryption.Encryption;
import su.plo.voice.api.server.audio.capture.ServerActivationManager;
import su.plo.voice.api.server.audio.line.BaseServerSourceLine;
import su.plo.voice.api.server.audio.line.BaseServerSourceLineManager;
import su.plo.voice.api.server.connection.UdpConnectionManager;
import su.plo.voice.api.server.language.ServerLanguages;
import su.plo.voice.api.server.player.VoicePlayer;
import su.plo.voice.api.server.player.VoicePlayerManager;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerAudioEndPacket;
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket;

/**
 * Represents a base API for proxy and server.
 */
public interface PlasmoBaseVoiceServer extends PlasmoVoice {

    /**
     * Gets the server languages.
     *
     * @return The server languages.
     */
    @NotNull ServerLanguages getLanguages();

    /**
     * Gets the {@link McLib}.
     *
     * @return The {@link McLib}.
     */
    @NotNull McLib getMinecraftServer();

    /**
     * Gets the {@link VoicePlayerManager}.
     *
     * <p>
     *     This manager can be used to get voice players.
     * </p>
     *
     * @return The {@link VoicePlayerManager}.
     */
    @NotNull VoicePlayerManager<?> getPlayerManager();

    /**
     * Gets the {@link UdpConnectionManager}.
     *
     * @return The {@link UdpConnectionManager}.
     */
    @NotNull UdpConnectionManager<?, ?> getUdpConnectionManager();

    /**
     * Gets the {@link BaseServerSourceLineManager}.
     *
     * <p>
     *     Source lines are used to create audio sources.
     *     To create audio source, you need to create source line using {@link BaseServerSourceLineManager#createBuilder}
     *     and then you can create audio sources using your {@link BaseServerSourceLine}.
     * </p>
     *
     * @return {@link BaseServerSourceLineManager}.
     */
    @NotNull BaseServerSourceLineManager<?> getSourceLineManager();

    /**
     * Gets the {@link ServerActivationManager}.
     *
     * @return The {@link ServerActivationManager}.
     */
    @NotNull ServerActivationManager getActivationManager();

    /**
     * Gets the default encryption instance.
     * <br/>
     * AES/CBC/PKCS5Padding is used by default.
     *
     * @return The {@link Encryption} instance.
     */
    @NotNull Encryption getDefaultEncryption();

    /**
     * Creates a new opus encoder using default params.
     *
     * @param stereo {@code true} if the encoder should be initialized in stereo mode.
     * @return {@link AudioEncoder} instance.
     */
    @NotNull AudioEncoder createOpusEncoder(boolean stereo);

    /**
     Creates a new opus encoder using default params.
     *
     * @param stereo {@code true} if the decoder should be initialized in stereo mode.
     * @return {@link AudioDecoder } instance.
     */
    @NotNull AudioDecoder createOpusDecoder(boolean stereo);

    /**
     * Legacy compatibility hook for old server-side proximity-to-group routing.
     *
     * <p>Modern implementations should keep this disabled. Group replacement
     * must be selected before transmission so the client sends the native group
     * activation and never duplicates or reinterprets proximity packets.</p>
     *
     * @param player the authenticated voice player that sent the packet
     * @param packet the already validated proximity audio packet
     * @return {@code true} only for a legacy implementation that handled it
     */
    default boolean routeProximityPacketAsGroup(@NotNull VoicePlayer player, @NotNull PlayerAudioPacket packet) {
        return false;
    }

    /**
     * Legacy counterpart to {@link #routeProximityPacketAsGroup}.
     *
     * @param player the authenticated voice player that sent the packet
     * @param packet the already validated proximity end packet
     * @return {@code true} only for a legacy implementation that handled it
     */
    default boolean routeProximityEndAsGroup(@NotNull VoicePlayer player, @NotNull PlayerAudioEndPacket packet) {
        return false;
    }
}
