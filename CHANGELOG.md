# Changelog

## 3.1.11

- Replaced the puzzle voice mode's server-side `PROXIMITY_ID -> GROUP_ID` reinterpretation with native client-side `VoiceActivation.GROUP_ID` transmission.
- The normal microphone activation now emits exactly one group stream while `/vcgroup replace on` is active and a group channel is selected.
- Suppressed all proximity activation streams during native group replacement to prevent duplicate or overlapping audio.
- Added a clientbound routing-state packet with reconnect and channel-selection resynchronization.
- Closed stale proximity/group sources when routing mode or selected channels change.
- Removed the redundant server interception path and disabled the legacy proximity-to-group compatibility hooks.
- Added defensive handling for already-cancelled voice events and a protocol round-trip test.
- Improved `/vcgroup` administration with permission-aware TAB suggestions, scoreboard team selectors, direct force-join/add, clear bulk-operation counters, and Spanish error messages.

## 3.1.10

- Added the first public Mytrix Voice API surface.
- Added public server API discovery through `VoiceChatApi.server()`.
- Added custom channel registration with owner namespaces.
- Added managed membership handles with typed results.
- Added distance-free non-spatial group channel configuration.
- Added cross-dimension group routing support for API channels.
- Added server-authoritative channel selection.
- Added public channel, membership, transmission, and rejection events.
- Added a compile-only party integration example.
- Enabled Javadoc jar generation for published Java API artifacts.
- Added built-in `/groups` player voice groups inspired by pv-addon-groups, backed by Mytrix Voice dynamic channels.
- Improved group voice source updates so HUD/player indicators and recipient changes are synchronized more reliably.
