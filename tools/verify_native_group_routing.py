#!/usr/bin/env python3
"""Static release checks for MytrixVoice native group routing."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]

checks: list[tuple[str, bool]] = []

def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")

codec = read("protocol/src/main/java/su/plo/voice/proto/packets/tcp/PacketTcpCodec.java")
handler = read("protocol/src/main/java/su/plo/voice/proto/packets/tcp/clientbound/ClientPacketTcpHandler.java")
connection = read("client/src/main/java/su/plo/voice/client/connection/ModServerConnection.java")
capture = read("client/src/main/java/su/plo/voice/client/audio/capture/VoiceAudioCapture.java")
manager = read("server-proxy-common/src/main/kotlin/su/plo/voice/server/audio/capture/VoiceServerActivationManager.kt")
dynamic = read("server/common/src/main/java/su/plo/voice/server/dynamic/DynamicVoiceService.java")
base_server = read("server/common/src/main/java/su/plo/voice/server/BaseVoiceServer.java")
version = read("gradle.properties")

checks.append(("routing packet is registered", "GroupRoutingModePacket.class" in codec))
checks.append(("packet handler remains backward-compatible", "default void handle(@NotNull GroupRoutingModePacket" in handler))
checks.append(("client receives routing state", "handle(@NotNull GroupRoutingModePacket packet)" in connection))
checks.append(("capture emits native GROUP activation", "processActivation(device.get(), groupActivation, parentResult" in capture))
checks.append(("capture suppresses proximity while replacing", "routeParentToGroup && (" in capture and "activation.isProximity()" in capture))
checks.append(("capture does not send parent proximity in replacement mode", "!routeParentToGroup && parentActivation.getId().equals(VoiceActivation.PROXIMITY_ID)" in capture))
checks.append(("server activation manager has no proximity conversion helper", "routeProximityGroupReplacement" not in manager))
checks.append(("already-cancelled speak events are ignored", manager.count("if (event.isCancelled) return") >= 2))
checks.append(("dynamic service has no speak packet interception", "PlayerSpeakEvent" not in dynamic and "PlayerSpeakEndEvent" not in dynamic))
checks.append(("routing state resyncs on UDP connection", "syncGroupRoutingMode(playerId);" in dynamic))
checks.append(("legacy audio conversion hooks are hard-disabled", len(re.findall(r"routeProximity(?:Packet|End)AsGroup[\s\S]{0,300}?return false;", base_server)) == 2))
checks.append(("release version is 3.1.11", "version=3.1.11" in version))

failed = [name for name, passed in checks if not passed]
for name, passed in checks:
    print(f"[{'PASS' if passed else 'FAIL'}] {name}")

if failed:
    print(f"\n{len(failed)} check(s) failed.", file=sys.stderr)
    sys.exit(1)

print(f"\nAll {len(checks)} native group routing checks passed.")
