# Mytrix Voice Public API

This document describes the first public API for mods that want to use Mytrix
Voice routing without depending on internal runtime classes.

## 1. Installation

Use the API artifact as a compile-only dependency. Runtime users still install
the normal Mytrix Voice mod jar.

```gradle
dependencies {
    compileOnly("su.plo.voice.api:server-proxy-common:3.1.10")
}
```

The existing build publishes binary and sources jars for API modules. Javadoc
jars are enabled through the shared Maven publishing plugin.

## 2. Required Dependency

For a required Forge 1.20.1 dependency, declare Mytrix Voice in `mods.toml`:

```toml
[[dependencies.yourmod]]
modId="mytrixvoice"
mandatory=true
versionRange="[3.1.10,4.0.0)"
ordering="NONE"
side="BOTH"
```

## 3. Optional Dependency

For optional integration, keep every direct reference to `net.mytrix.voice.api`
inside a class that your loader only touches when `mytrixvoice` is present.
`Optional` prevents missing services at runtime; it does not prevent Java from
loading a missing API class too early.

```java
VoiceChatApi.server().ifPresent(api -> {
    // Register voice channels.
});
```

## 4. Getting the API

```java
Optional<ServerVoiceApi> api = VoiceChatApi.server();
```

`ServerVoiceApi.state()` reports `INITIALIZING`, `READY`, `STOPPING`, or
`STOPPED`. Register channels during server initialization or later while the
state accepts mutations.

Do not store an implementation instance across server restarts. Obtain a new
instance during every server lifecycle.

## 5. Registering a Distance-Free Group Channel

```java
VoiceChannelId channelId = VoiceChannelId.of("party_mod", "party/15");

VoiceChannelDefinition definition = VoiceChannelDefinition.builder()
        .id(channelId)
        .displayName("Party 15")
        .config(VoiceChannelConfig.distanceFreeGroup())
        .build();

RegistrationResult result = api.channels().register(definition);
VoiceChannelHandle handle = result.handle().orElseThrow();
```

`distanceFreeGroup()` maps to:

- `VoiceChannelMode.GROUP`
- `VoiceSpatialMode.NON_POSITIONAL`
- `DimensionPolicy.CROSS_DIMENSION`
- `VoiceTransmissionPolicy.EXCLUSIVE`
- base volume `1.0`

The runtime uses the existing microphone capture, Opus encoder, UDP transport,
decoder, jitter buffers, and direct non-spatial client source. It does not
increase proximity distance.

## 6. Members

```java
handle.addMember(playerId);
handle.removeMember(playerId);
handle.clearMembers();
handle.close();
```

All membership results are typed with `MembershipStatus`. Returned member sets
are immutable snapshots.

## 7. Channel Selection

The server remains authoritative:

```java
api.transmissions().selectChannel(playerId, channelId);
api.transmissions().clearSelection(playerId);
```

The server validates that the channel exists, that the player belongs to it,
that transmission is allowed, and that permissions allow speaking. The client
does not choose a spoofed speaker UUID or arbitrary recipients.

## 8. Events

Subscribe with an explicit `Subscription` and close it during your mod cleanup:

```java
Subscription subscription = api.events().subscribe(
        VoiceTransmissionStartedEvent.class,
        event -> System.out.println(event.speakerId() + " speaks in " + event.channelId())
);

subscription.close();
```

Events do not expose raw or encoded audio.

## 9. Permissions

Callbacks must be fast and non-blocking. Keep party/team state in memory and
avoid database or network calls inside permission methods.

```java
public final class LeaderOnlyVoicePermission implements VoiceChannelPermission {
    private final PartyCache parties;

    public LeaderOnlyVoicePermission(PartyCache parties) {
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
```

Callback failures are isolated and logged with rate limiting. The affected
operation is rejected safely.

## 10. Threading

- Immutable views and query results are safe snapshots.
- Channel mutations should be made from the logical server thread when possible.
- Audio routing can run from networking/audio worker threads.
- Public listeners should be quick and non-blocking.
- OpenAL, microphone capture, encoders, decoders, and packet internals are not
  part of this API.

## 11. Lifecycle and Cleanup

Closing a `VoiceChannelHandle` unregisters the channel, removes members, clears
selections, stops related group sources, and fires unregister events. Closing is
idempotent.

Dynamic channels are not persisted by Mytrix Voice. The owner mod remains the
source of truth and should re-register channels after restart.

## 12. Common Errors

- `API_NOT_READY`: wait until the server API is initialized.
- `ALREADY_REGISTERED`: do not register the same `namespace:path` twice.
- `NOT_A_MEMBER`: add the player before selecting the channel.
- `CANNOT_SPEAK`: channel config or permission rejected transmission.
- Missing API classes with optional integration: move API references into a
  loader-gated integration class.

## 13. Stability Policy

Public packages:

- `net.mytrix.voice.api`
- `net.mytrix.voice.api.channel`
- `net.mytrix.voice.api.client`
- `net.mytrix.voice.api.event`
- `net.mytrix.voice.api.player`
- `net.mytrix.voice.api.server`
- `net.mytrix.voice.api.threading`
- `net.mytrix.voice.api.transmission`

Packages under `su.plo.voice.server`, `su.plo.voice.client`, mixins, protocol
packet internals, OpenAL wrappers, codec internals, and source managers are not
public integration points.

## 14. Manual Test With A Consumer Mod

1. Start a Forge 1.20.1 server with Mytrix Voice and the consumer mod.
2. The consumer registers `party_mod:party/15`.
3. Add players A and B to the handle; leave C outside.
4. Select the channel for A through `api.transmissions().selectChannel(A, id)`.
5. A speaks with the Mytrix Voice group activation.
6. B hears constant centered audio.
7. C, even standing beside A, receives no group audio.
8. Move B 5000 blocks away and then to the Nether; B still hears the group.
9. B mutes A; B stops hearing A. Remove the mute; B hears A again.
10. Remove A from the party; selection is invalidated and group routing stops.
11. Close the handle; the channel disappears without orphaned members.
12. Verify normal proximity voice still works between nearby players.
