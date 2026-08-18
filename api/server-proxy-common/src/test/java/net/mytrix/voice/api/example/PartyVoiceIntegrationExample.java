package net.mytrix.voice.api.example;

import net.mytrix.voice.api.VoiceChatApi;
import net.mytrix.voice.api.channel.VoiceChannelConfig;
import net.mytrix.voice.api.channel.VoiceChannelContext;
import net.mytrix.voice.api.channel.VoiceChannelDefinition;
import net.mytrix.voice.api.channel.VoiceChannelHandle;
import net.mytrix.voice.api.channel.VoiceChannelId;
import net.mytrix.voice.api.channel.VoiceChannelPermission;
import net.mytrix.voice.api.channel.VoicePlayerContext;
import net.mytrix.voice.api.event.Subscription;
import net.mytrix.voice.api.event.VoiceTransmissionStartedEvent;
import net.mytrix.voice.api.server.ServerVoiceApi;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Compile-only example for an external party mod.
 */
public final class PartyVoiceIntegrationExample {

    private final PartyService parties;
    private final Map<UUID, VoiceChannelHandle> channels = new HashMap<>();
    private ServerVoiceApi voiceApi;
    private Subscription speakingSubscription;

    public PartyVoiceIntegrationExample(PartyService parties) {
        this.parties = parties;
    }

    public void initialize() {
        VoiceChatApi.server().ifPresent(api -> {
            this.voiceApi = api;
            this.speakingSubscription = api.events().subscribe(
                    VoiceTransmissionStartedEvent.class,
                    this::onTransmissionStarted
            );
        });
    }

    public void shutdown() {
        channels.values().forEach(VoiceChannelHandle::close);
        channels.clear();
        if (speakingSubscription != null) speakingSubscription.close();
        voiceApi = null;
    }

    public void onPartyCreated(Party party) {
        if (voiceApi == null) return;

        VoiceChannelId channelId = VoiceChannelId.of("party_mod", "party/" + party.id());
        VoiceChannelDefinition definition = VoiceChannelDefinition.builder()
                .id(channelId)
                .displayName(party.name())
                .config(VoiceChannelConfig.builder()
                        .channelMode(net.mytrix.voice.api.channel.VoiceChannelMode.GROUP)
                        .spatialMode(net.mytrix.voice.api.channel.VoiceSpatialMode.NON_POSITIONAL)
                        .dimensionPolicy(net.mytrix.voice.api.channel.DimensionPolicy.CROSS_DIMENSION)
                        .baseVolume(1.0F)
                        .maxMembers(party.maxMembers())
                        .showSpeakingIndicator(true)
                        .build())
                .permission(new LeaderOnlyVoicePermission(parties))
                .build();

        voiceApi.channels().register(definition).handle().ifPresent(handle -> {
            channels.put(party.id(), handle);
            party.memberIds().forEach(handle::addMember);
        });
    }

    public void onPartyMemberJoined(UUID partyId, UUID playerId) {
        VoiceChannelHandle handle = channels.get(partyId);
        if (handle != null) handle.addMember(playerId);
    }

    public void onPartyMemberLeft(UUID partyId, UUID playerId) {
        VoiceChannelHandle handle = channels.get(partyId);
        if (handle != null) handle.removeMember(playerId);
    }

    public void onPartyDeleted(UUID partyId) {
        VoiceChannelHandle handle = channels.remove(partyId);
        if (handle != null) handle.close();
    }

    public void selectPartyChannel(UUID partyId, UUID playerId) {
        if (voiceApi == null) return;
        VoiceChannelHandle handle = channels.get(partyId);
        if (handle != null) voiceApi.transmissions().selectChannel(playerId, handle.id());
    }

    private void onTransmissionStarted(VoiceTransmissionStartedEvent event) {
        parties.markSpeaking(event.channelId(), event.speakerId());
    }

    public static final class LeaderOnlyVoicePermission implements VoiceChannelPermission {
        private final PartyService parties;

        public LeaderOnlyVoicePermission(PartyService parties) {
            this.parties = parties;
        }

        @Override
        public boolean canJoin(VoicePlayerContext player, VoiceChannelContext channel) {
            return parties.isMember(channel.id(), player.playerId());
        }

        @Override
        public boolean canSpeak(VoicePlayerContext player, VoiceChannelContext channel) {
            return parties.isLeader(channel.id(), player.playerId());
        }

        @Override
        public boolean canListen(VoicePlayerContext listener, VoicePlayerContext speaker, VoiceChannelContext channel) {
            return parties.isMember(channel.id(), listener.playerId());
        }
    }

    public record Party(UUID id, String name, Set<UUID> memberIds, int maxMembers) {
    }

    public interface PartyService {
        boolean isMember(VoiceChannelId channelId, UUID playerId);

        boolean isLeader(VoiceChannelId channelId, UUID playerId);

        void markSpeaking(VoiceChannelId channelId, UUID playerId);
    }
}
