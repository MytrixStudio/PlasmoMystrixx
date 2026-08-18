package su.plo.voice.client.gui.settings.tab;

import su.plo.slib.api.chat.component.McTextComponent;
import su.plo.voice.api.client.PlasmoVoiceClient;
import su.plo.voice.api.client.audio.device.DeviceManager;
import su.plo.voice.api.client.audio.device.OutputDevice;
import su.plo.voice.api.client.config.OverlappingSourceTypes;
import su.plo.voice.client.config.VoiceClientConfig;
import su.plo.voice.client.gui.settings.VoiceSettingsScreen;

public final class AdvancedTabWidget extends TabWidget {

    private final DeviceManager devices;

    public AdvancedTabWidget(VoiceSettingsScreen parent,
                             PlasmoVoiceClient voiceClient,
                             VoiceClientConfig config) {
        super(parent, voiceClient, config);

        this.devices = voiceClient.getDeviceManager();
    }

    @Override
    public void init() {
        super.init();

        if (isAnyControlVisible("advanced.visualize_voice_distance", "advanced.visualize_on_join")) {
            addEntry(new CategoryEntry(McTextComponent.translatable("gui.plasmovoice.advanced.visual")));
        }
        addControlledEntry(createToggleEntry(
                McTextComponent.translatable("gui.plasmovoice.advanced.visualize_voice_distance"),
                null,
                config.getAdvanced().getVisualizeVoiceDistance()
        ), "advanced.visualize_voice_distance");
        addControlledEntry(createToggleEntry(
                McTextComponent.translatable("gui.plasmovoice.advanced.visualize_voice_distance_on_join"),
                null,
                config.getAdvanced().getVisualizeVoiceDistanceOnJoin()
        ), "advanced.visualize_on_join");

        if (isAnyControlVisible(
                "advanced.directional_sources_angle",
                "advanced.mono_stereo_sources",
                "advanced.stereo_positioning",
                "advanced.source_types_overlap",
                "advanced.adaptive_jitter_buffer"
        )) {
            addEntry(new CategoryEntry(McTextComponent.translatable("gui.plasmovoice.advanced.audio_engine")));
        }
        addControlledEntry(createIntSliderWidget(
                McTextComponent.translatable("gui.plasmovoice.advanced.directional_sources_angle"),
                McTextComponent.translatable("gui.plasmovoice.advanced.directional_sources_angle.tooltip"),
                config.getAdvanced().getDirectionalSourcesAngle(),
                ""
        ), "advanced.directional_sources_angle");
        addControlledEntry(createToggleEntry(
                McTextComponent.translatable("gui.plasmovoice.advanced.stereo_sources_to_mono"),
                McTextComponent.translatable("gui.plasmovoice.advanced.stereo_sources_to_mono.tooltip"),
                config.getAdvanced().getStereoSourcesToMono(),
                toggled -> devices.getOutputDevice().ifPresent(OutputDevice::closeSourcesAsync)
        ), "advanced.mono_stereo_sources");
        addControlledEntry(createToggleEntry(
                McTextComponent.translatable("gui.plasmovoice.advanced.panning"),
                null,
                config.getAdvanced().getPanning()
        ), "advanced.stereo_positioning");
        addControlledEntry(createDropDownEntry(
                McTextComponent.translatable("gui.plasmovoice.advanced.source_types_overlap"),
                McTextComponent.translatable("gui.plasmovoice.advanced.source_types_overlap.tooltip"),
                OverlappingSourceTypes.class,
                OverlappingSourceTypes.getTextElements(),
                config.getAdvanced().getSourceTypesOverlap(),
                false
        ), "advanced.source_types_overlap");
        addControlledEntry(createToggleEntry(
                McTextComponent.translatable("gui.plasmovoice.advanced.adaptive_jitter_buffer"),
                McTextComponent.translatable("gui.plasmovoice.advanced.adaptive_jitter_buffer.tooltip"),
                config.getAdvanced().getAdaptiveJitterBuffer(),
                toggled -> voiceClient.getBackgroundExecutor().execute(voiceClient.getSourceManager()::clear)
        ), "advanced.adaptive_jitter_buffer");

        if (isAnyControlVisible("advanced.volume_sliders", "advanced.distance_gain")) {
            addEntry(new CategoryEntry(McTextComponent.translatable("gui.plasmovoice.advanced.exponential_volume")));
        }
        addControlledEntry(createToggleEntry(
                McTextComponent.translatable("gui.plasmovoice.advanced.exponential_volume.volume_slider"),
                null,
                config.getAdvanced().getExponentialVolumeSlider()
        ), "advanced.volume_sliders");
        addControlledEntry(createToggleEntry(
                McTextComponent.translatable("gui.plasmovoice.advanced.exponential_volume.distance_gain"),
                null,
                config.getAdvanced().getExponentialDistanceGain()
        ), "advanced.distance_gain");

//        addEntry(new CategoryEntry(McTextComponent.translatable("gui.plasmovoice.advanced.compressor")));
//        addEntry(createIntSliderWidget(
//                "gui.plasmovoice.advanced.compressor_threshold",
//                "gui.plasmovoice.advanced.compressor_threshold.tooltip",
//                config.getAdvanced().getCompressorThreshold(),
//                "dB"
//        ));
//        addEntry(createIntSliderWidget(
//                "gui.plasmovoice.advanced.limiter_threshold",
//                "gui.plasmovoice.advanced.limiter_threshold.tooltip",
//                config.getAdvanced().getLimiterThreshold(),
//                "dB"
//        ));
    }
}
