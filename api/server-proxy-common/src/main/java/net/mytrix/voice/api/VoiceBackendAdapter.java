package net.mytrix.voice.api;

import java.util.Set;
import java.util.UUID;

public interface VoiceBackendAdapter {

    boolean isAvailable();

    void registerPacketListener(VoicePacketListener listener);

    void cancelDefaultTransmission(VoiceBackendEvent event);

    VoiceSourceHandle createSource(VoiceSourceRequest request);

    void updateRecipients(VoiceSourceHandle source, Set<UUID> recipients);

    void removeSource(VoiceSourceHandle source);

    void removeSourcesOwnedBy(UUID playerId);
}
