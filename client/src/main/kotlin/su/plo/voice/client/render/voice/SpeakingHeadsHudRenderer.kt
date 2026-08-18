package su.plo.voice.client.render.voice

import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import su.plo.lib.mod.client.render.Colors
import su.plo.lib.mod.client.render.Colors.withAlpha
import su.plo.lib.mod.client.render.LazyGlState
import su.plo.lib.mod.client.render.RenderUtil
import su.plo.lib.mod.client.render.gui.GuiRenderContext
import su.plo.lib.mod.client.render.pipeline.RenderPipelines
import su.plo.lib.mod.client.render.texture.ModPlayerSkins
import su.plo.slib.api.chat.component.McTextComponent
import su.plo.slib.api.entity.player.McGameProfile
import su.plo.voice.api.client.PlasmoVoiceClient
import su.plo.voice.client.config.VoiceClientConfig
import su.plo.voice.client.event.HudRenderEvent
import su.plo.voice.proto.data.audio.source.DirectSourceInfo
import su.plo.voice.proto.data.audio.source.PlayerSourceInfo
import su.plo.voice.proto.data.audio.source.SourceInfo
import java.awt.Color
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

class SpeakingHeadsHudRenderer(
    private val voiceClient: PlasmoVoiceClient,
    private val config: VoiceClientConfig,
) : HudRenderEvent.Callback {

    private val glState = LazyGlState()

    override fun onRender(context: GuiRenderContext, delta: Float) {
        val minecraft = Minecraft.getInstance()
        val localPlayer = minecraft.player ?: return
        if (!voiceClient.serverInfo.isPresent ||
            !voiceClient.udpClientManager.client.isPresent ||
            config.voice.disabled.value() ||
            minecraft.options.hideGui
        ) return

        val speakers = LinkedHashMap<UUID, SpeakerInfo>()
        for (source in voiceClient.sourceManager.sources) {
            if (!source.isActivated() || !source.canHear()) continue

            val speaker = speakerInfo(source.sourceInfo) ?: continue
            if (speaker.id == localPlayer.uuid) continue

            speakers.putIfAbsent(speaker.id, speaker)
            if (speakers.size >= MAX_SPEAKERS) break
        }

        if (speakers.isEmpty()) return

        val window = minecraft.window
        val entries = speakers.values.toList()
        val totalHeight = entries.size * ENTRY_HEIGHT + (entries.size - 1) * ENTRY_GAP
        val startY = ((window.guiScaledHeight - totalHeight) / 2).coerceAtLeast(4)

        glState.withState {
            //#if MC<12106
            context.stack.pushPose()
            context.stack.translate(0.0, 0.0, 1000.0)
            //#endif

            entries.forEachIndexed { index, speaker ->
                renderSpeaker(context, HUD_X, startY + index * (ENTRY_HEIGHT + ENTRY_GAP), speaker)
            }

            //#if MC<12106
            context.stack.popPose()
            //#endif
        }
    }

    private fun renderSpeaker(context: GuiRenderContext, x: Int, y: Int, speaker: SpeakerInfo) {
        val textWidth = RenderUtil.getTextWidth(speaker.name) + TEXT_PADDING * 2
        val width = HEAD_SIZE + TEXT_GAP + textWidth
        val backgroundColor = Color.BLACK.withAlpha(0.35f)

        context.fill(
            x - BACKGROUND_PADDING,
            y - BACKGROUND_PADDING,
            x + width + BACKGROUND_PADDING,
            y + HEAD_SIZE + BACKGROUND_PADDING,
            backgroundColor,
            RenderPipelines.GUI_COLOR_OVERLAY
        )

        val skin = loadSkin(speaker.profile)
        context.blit(skin, x, y, HEAD_SIZE, HEAD_SIZE, 8f, 8f, 8, 8, 64, 64, RenderPipelines.GUI_TEXTURE_OVERLAY)
        context.blit(skin, x, y, HEAD_SIZE, HEAD_SIZE, 40f, 8f, 8, 8, 64, 64, RenderPipelines.GUI_TEXTURE_OVERLAY)

        val textX = x + HEAD_SIZE + TEXT_GAP + TEXT_PADDING
        val textY = y + ((HEAD_SIZE - 8) / 2)
        context.drawString(speaker.name, textX, textY, Colors.WHITE, false)
    }

    private fun speakerInfo(sourceInfo: SourceInfo): SpeakerInfo? =
        when (sourceInfo) {
            is DirectSourceInfo -> sourceInfo.sender?.let {
                SpeakerInfo(it.id, displayName(sourceInfo, it.id, it.name), it)
            }

            is PlayerSourceInfo -> {
                val playerId = sourceInfo.playerInfo.playerId
                val playerName = sourceInfo.playerInfo.playerNick
                SpeakerInfo(
                    playerId,
                    displayName(sourceInfo, playerId, playerName),
                    McGameProfile(playerId, playerName, Collections.emptyList())
                )
            }

            else -> null
        }

    private fun displayName(sourceInfo: SourceInfo, playerId: UUID, fallbackName: String): McTextComponent {
        val name = sourceInfo.name
            ?: voiceClient.serverConnection.getOrNull()
                ?.getPlayerById(playerId)
                ?.getOrNull()
                ?.playerNick
            ?: Minecraft.getInstance().connection
                ?.getPlayerInfo(playerId)
                ?.profile
                ?.name
            ?: fallbackName

        return McTextComponent.literal(name.take(MAX_NAME_LENGTH))
    }

    private fun loadSkin(gameProfile: McGameProfile): ResourceLocation {
        ModPlayerSkins.loadSkin(gameProfile)
        return ModPlayerSkins.getSkin(gameProfile.id, gameProfile.name)
    }

    private data class SpeakerInfo(
        val id: UUID,
        val name: McTextComponent,
        val profile: McGameProfile,
    )

    companion object {
        private const val HUD_X = 8
        private const val HEAD_SIZE = 20
        private const val ENTRY_HEIGHT = 20
        private const val ENTRY_GAP = 4
        private const val TEXT_GAP = 5
        private const val TEXT_PADDING = 4
        private const val BACKGROUND_PADDING = 2
        private const val MAX_SPEAKERS = 8
        private const val MAX_NAME_LENGTH = 16
    }
}
