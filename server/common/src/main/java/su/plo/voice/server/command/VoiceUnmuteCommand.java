package su.plo.voice.server.command;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.plo.slib.api.chat.component.McTextComponent;
import su.plo.slib.api.command.McCommand;
import su.plo.slib.api.command.McCommandSource;
import su.plo.slib.api.entity.player.McGameProfile;
import su.plo.slib.api.server.McServerLib;
import su.plo.slib.api.server.entity.player.McServerPlayer;
import su.plo.voice.api.server.mute.MuteManager;
import su.plo.voice.server.BaseVoiceServer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public final class VoiceUnmuteCommand implements McCommand {

    private final BaseVoiceServer voiceServer;
    private final McServerLib minecraftServer;

    @Override
    public void execute(@NotNull McCommandSource source, @NotNull String[] arguments) {
        if (arguments.length == 0) {
            source.sendMessage(McTextComponent.translatable("pv.command.unmute.usage"));
            return;
        }

        if ("@a".equals(arguments[0])) {
            unmuteOnlinePlayers(source);
            return;
        }

        McGameProfile player;
        try {
            player = minecraftServer.getGameProfile(UUID.fromString(arguments[0]));
        } catch (Exception e) {
            player = minecraftServer.getGameProfile(arguments[0]);
        }

        if (player == null) {
            source.sendMessage(McTextComponent.translatable("pv.error.player_not_found"));
            return;
        }

        MuteManager muteManager = voiceServer.getMuteManager();

        if (!muteManager.unmute(player.getId(), false).isPresent()) {
            source.sendMessage(
                    McTextComponent.translatable("pv.command.unmute.not_muted", player.getName())
            );
            return;
        }

        source.sendMessage(
                McTextComponent.translatable("pv.command.unmute.unmuted", player.getName())
        );
    }

    private void unmuteOnlinePlayers(@NotNull McCommandSource source) {
        MuteManager muteManager = voiceServer.getMuteManager();
        int unmuted = 0;
        int notMuted = 0;

        for (McServerPlayer player : resolveTargetPlayers(source)) {
            if (muteManager.unmute(player.getUuid(), false).isPresent()) {
                unmuted++;
            } else {
                notMuted++;
            }
        }

        source.sendMessage(
                McTextComponent.literal("Unmuted " + unmuted + " players. Not muted: " + notMuted + ".")
        );
    }

    private @NotNull List<McServerPlayer> resolveTargetPlayers(@NotNull McCommandSource source) {
        List<McServerPlayer> players = new ArrayList<>();
        minecraftServer.getPlayers()
                .stream()
                .filter(player -> !(source instanceof McServerPlayer) || ((McServerPlayer) source).canSee(player))
                .forEach(players::add);
        return players;
    }

    @Override
    public boolean hasPermission(@NotNull McCommandSource source, @Nullable String[] arguments) {
        return source.hasPermission("pv.unmute");
    }

    @Override
    public @NotNull List<String> suggest(@NotNull McCommandSource source, @NotNull String[] arguments) {
        if (arguments.length <= 1) {
            String argument = arguments.length > 0 ? arguments[0] : "";

            List<String> suggestions = new ArrayList<>();
            if ("@a".startsWith(argument)) {
                suggestions.add("@a");
            }

            suggestions.addAll(voiceServer.getMuteManager()
                    .getMuteStorage()
                    .getMutedPlayers()
                    .stream()
                    .map((muteInfo) -> {
                        McGameProfile player = minecraftServer.getGameProfile(muteInfo.getPlayerUUID());
                        if (player == null) return muteInfo.getPlayerUUID().toString();
                        return player.getName();
                    })
                    .filter((player) -> player.regionMatches(true, 0, argument, 0, argument.length()))
                    .collect(Collectors.toList()));

            return suggestions;
        }

        return McCommand.super.suggest(source, arguments);
    }
}
