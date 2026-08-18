package su.plo.voice.server;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import su.plo.config.provider.ConfigurationProvider;
import su.plo.config.provider.toml.TomlConfiguration;
import su.plo.slib.api.command.McCommand;
import su.plo.slib.api.command.McCommandManager;
import su.plo.slib.api.language.ServerTranslator;
import su.plo.slib.api.permission.PermissionDefault;
import su.plo.slib.api.permission.PermissionManager;
import su.plo.slib.api.server.McServerLib;
import su.plo.slib.api.server.channel.McServerChannelManager;
import su.plo.slib.api.server.event.command.McServerCommandsRegisterEvent;
import su.plo.voice.BaseVoice;
import su.plo.voice.BuildConstants;
import su.plo.voice.api.addon.ServerAddonsLoader;
import su.plo.voice.api.audio.codec.AudioDecoder;
import su.plo.voice.api.audio.codec.AudioEncoder;
import su.plo.voice.api.encryption.Encryption;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.audio.capture.ServerActivationManager;
import su.plo.voice.api.server.audio.line.ServerSourceLineManager;
import su.plo.voice.api.server.connection.TcpServerPacketManager;
import su.plo.voice.api.server.connection.UdpServerConnectionManager;
import su.plo.voice.api.server.event.config.VoiceServerConfigReloadedEvent;
import su.plo.voice.api.server.event.socket.UdpServerCreateEvent;
import su.plo.voice.api.server.event.socket.UdpServerStartedEvent;
import su.plo.voice.api.server.event.socket.UdpServerStoppedEvent;
import su.plo.voice.api.server.mute.MuteManager;
import su.plo.voice.api.server.mute.storage.MuteStorage;
import su.plo.voice.api.server.player.VoicePlayer;
import su.plo.voice.api.server.player.VoiceServerPlayer;
import su.plo.voice.api.server.socket.UdpServer;
import su.plo.voice.api.server.socket.UdpServerConnection;
import su.plo.voice.proto.data.audio.codec.opus.OpusDecoderInfo;
import su.plo.voice.proto.data.audio.codec.opus.OpusEncoderInfo;
import su.plo.voice.proto.data.audio.codec.opus.OpusMode;
import su.plo.voice.proto.data.audio.capture.VoiceActivation;
import su.plo.voice.proto.packets.tcp.serverbound.PlayerAudioEndPacket;
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket;
import net.mytrix.voice.api.VoiceChannelId;
import su.plo.voice.server.audio.capture.GroupServerActivation;
import su.plo.voice.server.audio.capture.ProximityServerActivation;
import su.plo.voice.server.audio.capture.VoiceServerActivationManager;
import su.plo.voice.server.audio.line.VoiceServerSourceLineManager;
import su.plo.voice.server.command.*;
import su.plo.voice.server.config.VoiceServerConfig;
import su.plo.voice.server.connection.ModRequiredKickHandler;
import su.plo.voice.server.connection.PlayerInfoRequestScheduler;
import su.plo.voice.server.connection.ServerChannelHandler;
import su.plo.voice.server.connection.ServerServiceChannelHandler;
import su.plo.voice.server.connection.VoiceTcpServerConnectionManager;
import su.plo.voice.server.connection.VoiceUdpServerConnectionManager;
import su.plo.voice.server.dynamic.DynamicVoiceService;
import su.plo.voice.server.group.MytrixVoiceGroupService;
import su.plo.voice.server.language.VoiceServerLanguages;
import su.plo.voice.server.mute.VoiceMuteManager;
import su.plo.voice.server.mute.storage.MuteStorageFactory;
import su.plo.voice.server.player.LuckPermsListener;
import su.plo.voice.server.player.VoiceServerPlayerManagerImpl;
import su.plo.voice.server.socket.NettyUdpServer;
import su.plo.voice.util.version.PlatformLoader;
import su.plo.voice.util.version.ModrinthVersion;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public abstract class BaseVoiceServer extends BaseVoice implements PlasmoVoiceServer {

    public static final String CHANNEL_STRING = "plasmo:voice/v2";
    public static final String FLAG_CHANNEL_STRING = "plasmo:voice/v2/installed";
    public static final String SERVICE_CHANNEL_STRING = "plasmo:voice/v2/service";

    public static final int BSTATS_PROJECT_ID = 10928;

    protected static final ConfigurationProvider TOML = ConfigurationProvider.getProvider(TomlConfiguration.class);

    @Getter
    protected final TcpServerPacketManager tcpPacketManager = new VoiceTcpServerConnectionManager(this);
    @Getter
    protected final UdpServerConnectionManager udpConnectionManager = new VoiceUdpServerConnectionManager(this);

    protected UdpServer udpServer;
    @Getter
    protected VoiceServerPlayerManagerImpl playerManager;
    @Getter
    protected ServerActivationManager activationManager;
    protected final ProximityServerActivation proximityActivation = new ProximityServerActivation(this);
    protected final GroupServerActivation groupActivation = new GroupServerActivation(this);
    @Getter
    protected ServerSourceLineManager sourceLineManager;

    @Getter
    protected MuteStorage muteStorage;
    @Getter
    protected MuteManager muteManager;

    protected LuckPermsListener luckPermsListener;

    @Getter
    protected VoiceServerConfig config;
    @Getter
    protected VoiceServerLanguages languages;
    @Getter
    protected String clientControlYaml = "";
    @Getter
    protected DynamicVoiceService dynamicVoiceService;
    @Getter
    protected MytrixVoiceGroupService groupService;

    @Getter
    private Encryption defaultEncryption;

    private final ServerChannelHandler channelHandler = new ServerChannelHandler(this);
    private final ServerServiceChannelHandler serviceChannelHandler = new ServerServiceChannelHandler(this);
    private PlayerInfoRequestScheduler requestScheduler;
    private ModRequiredKickHandler modRequiredKickHandler;

    protected BaseVoiceServer(@NotNull PlatformLoader loader) {
        super(loader);

        ServerAddonsLoader.INSTANCE.setAddonManager(getAddonManager());
        McServerCommandsRegisterEvent.INSTANCE.registerListener(this::registerDefaultCommandsAndPermissions);

        this.dynamicVoiceService = new DynamicVoiceService(this);
        this.dynamicVoiceService.registerService();
        this.groupService = new MytrixVoiceGroupService(this);
    }

    @Override
    protected void onInitialize() {
        super.onInitialize();

        McServerChannelManager channelManager = getMinecraftServer().getChannelManager();
        channelManager.registerChannelHandler(CHANNEL_STRING, channelHandler);
        channelManager.registerChannelHandler(SERVICE_CHANNEL_STRING, serviceChannelHandler);

        eventBus.register(this, udpConnectionManager);
        eventBus.register(this, getMinecraftServer());
        eventBus.register(this, proximityActivation);
        eventBus.register(this, groupActivation);

        this.playerManager = new VoiceServerPlayerManagerImpl(this, getMinecraftServer());
        playerManager.registerPermission("pv.allow_freecam");
        eventBus.register(this, playerManager);
        this.requestScheduler = new PlayerInfoRequestScheduler(this);
        eventBus.register(this, requestScheduler);
        this.modRequiredKickHandler = new ModRequiredKickHandler(this);
        eventBus.register(this, modRequiredKickHandler);

        this.activationManager = new VoiceServerActivationManager(
                this,
                tcpPacketManager,
                (activationName) -> config.voice().weights().getActivationWeight(activationName)
        );
        eventBus.register(this, activationManager);
        this.sourceLineManager = new VoiceServerSourceLineManager(this);

        eventBus.register(this, dynamicVoiceService);
        dynamicVoiceService.initialize();
        eventBus.register(this, groupService);
        groupService.initialize();

        // mutes
        MuteStorageFactory muteStorageFactory = new MuteStorageFactory(this, backgroundExecutor);
        this.muteStorage = muteStorageFactory.createStorage("json");

        try {
            this.muteStorage.init();
        } catch (Exception e) {
            LOGGER.error("Failed to initialize mute storage: {}", e.toString());
            e.printStackTrace();
            return;
        }

        this.muteManager = new VoiceMuteManager(this, this.muteStorage, backgroundExecutor);

        if (LuckPermsListener.Companion.hasLuckPerms()) {
            this.luckPermsListener = new LuckPermsListener(this, backgroundExecutor);
            luckPermsListener.subscribe();
            LOGGER.info("LuckPerms permissions listener attached");
        }

        this.languages = new VoiceServerLanguages(getMinecraftServer().getServerTranslator(), true);
        loadConfig(false);

        // check for updates
        checkForUpdates();
    }

    @Override
    protected void onShutdown() {
        if (luckPermsListener != null) {
            luckPermsListener.unsubscribe();
            this.luckPermsListener = null;
        }

        if (muteStorage != null) {
            try {
                muteStorage.close();
            } catch (Exception e) {
                LOGGER.error("Failed to close mute storage: {}", e.toString());
                e.printStackTrace();
            }
        }

        stopUdpServer();

        if (groupService != null) {
            groupService.shutdown();
            this.groupService = null;
        }

        if (dynamicVoiceService != null) {
            dynamicVoiceService.shutdown();
            this.dynamicVoiceService = null;
        }

        // cleanup
        requestScheduler.clear();
        modRequiredKickHandler.clear();
        getMinecraftServer().getChannelManager().clear();
        sourceLineManager.clear();
        activationManager.clear();
        playerManager.clear();
        channelHandler.clear();

        this.config = null;

        eventBus.unregister(this);
        super.onShutdown();
    }

    public void loadConfig(boolean reload) {
        boolean restartUdpServer = true;

        try {
            File configFile = new File(getConfigFolder(), "config.toml");
            VoiceServerConfig oldConfig = config;

            this.config = TOML.load(VoiceServerConfig.class, configFile, false);
            TOML.save(config, configFile);
            loadClientControlConfig();

            if (oldConfig != null) {
                restartUdpServer = !config.host().equals(oldConfig.host());
            }

            ServerTranslator serverTranslator = getMinecraftServer().getServerTranslator();
            languages.setCrowdinEnabled(config.useCrowdinTranslations());
            languages.register(
                    URI.create(BuildConstants.GITHUB_CROWDIN_URL).toURL(),
                    "server.toml",
                    this::getResource,
                    new File(getConfigFolder(), "languages")
            );
            if (config.forcedLanguage() != null) {
                serverTranslator.setDefaultLanguage(config.forcedLanguage());
                serverTranslator.setForcedLanguage(config.forcedLanguage());
            } else {
                serverTranslator.setDefaultLanguage(config.defaultLanguage());
                serverTranslator.setForcedLanguage(null);
            }

            // load forwarding secret
            File forwardingSecretFile = System.getenv().containsKey("PLASMO_VOICE_FORWARDING_SECRET_FILE")
                    ? new File(System.getenv("PLASMO_VOICE_FORWARDING_SECRET_FILE"))
                    : new File(getConfigFolder(), "forwarding-secret");
            try {
                if (System.getenv("PLASMO_VOICE_FORWARDING_SECRET") != null) {
                    UUID forwardingSecret = UUID.fromString(System.getenv("PLASMO_VOICE_FORWARDING_SECRET"));
                    config.host().forwardingSecret(forwardingSecret);
                } else if (forwardingSecretFile.exists()) {
                    UUID forwardingSecret = UUID.fromString(new String(Files.readAllBytes(forwardingSecretFile.toPath())));
                    config.host().forwardingSecret(forwardingSecret);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to read secret", e);
            }

            // load server id from ENV
            if (System.getenv("PLASMO_VOICE_SERVER_ID") != null) {
                config.serverId(System.getenv("PLASMO_VOICE_SERVER_ID"));
            }

            try {
                UUID.fromString(config.serverId());
            } catch (IllegalArgumentException ignored) {
                config.serverId(UUID.randomUUID().toString());
            }

            // load AES key
            byte[] aesKey;
            if (oldConfig != null && oldConfig.voice().aesEncryptionKey() != null) {
                aesKey = oldConfig.voice().aesEncryptionKey();
            } else {
                UUID aesEncryptionKey = UUID.randomUUID();
                ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeLong(aesEncryptionKey.getMostSignificantBits());
                out.writeLong(aesEncryptionKey.getLeastSignificantBits());

                aesKey = out.toByteArray();
            }

            updateAesEncryptionKey(aesKey);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config", e);
        }

        DEBUG_LOGGER.enabled(config.debug() || System.getProperty("plasmovoice.debug") != null);

        // register proximity activation
        proximityActivation.register(config);
        groupActivation.register(config);

        if (reload) eventBus.fire(new VoiceServerConfigReloadedEvent(this, config));
        else addons.initializeLoadedAddons();

        if (restartUdpServer) startUdpServer();
    }

    public synchronized void updateProximityDistances(@NotNull List<Integer> distances, int defaultDistance) {
        if (config == null) {
            throw new IllegalStateException("Voice config is not loaded");
        }

        List<Integer> normalized = distances.stream()
                .filter(distance -> distance != null && distance > 0)
                .distinct()
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new));
        if (defaultDistance <= 0) {
            throw new IllegalArgumentException("Default proximity distance must be greater than 0");
        }
        if (!normalized.contains(defaultDistance)) {
            normalized.add(defaultDistance);
            normalized.sort(Integer::compareTo);
        }

        config.voice().proximity().distances(List.copyOf(normalized));
        config.voice().proximity().defaultDistance(defaultDistance);

        try {
            TOML.save(config, new File(getConfigFolder(), "config.toml"));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save proximity distances", exception);
        }

        proximityActivation.register(config);
        eventBus.fire(new VoiceServerConfigReloadedEvent(this, config));
    }

    private void loadClientControlConfig() throws IOException {
        File controlFile = new File(getConfigFolder(), "client-control.yml");
        if (!controlFile.exists()) {
            File parent = controlFile.getParentFile();
            if (parent != null) parent.mkdirs();
            Files.write(controlFile.toPath(), DEFAULT_CLIENT_CONTROL_YAML.getBytes(StandardCharsets.UTF_8));
        }

        String yaml = new String(Files.readAllBytes(controlFile.toPath()), StandardCharsets.UTF_8);
        if (yaml.length() > 60_000) {
            LOGGER.warn("client-control.yml is too large; truncating to 60000 characters");
            yaml = yaml.substring(0, 60_000);
        }

        this.clientControlYaml = yaml;
    }

    private static final String DEFAULT_CLIENT_CONTROL_YAML =
            "# Mytrix Voice server-controlled client settings.\n" +
            "# visible: false hides a tab/control from players.\n" +
            "# enabled: false locks it. If show_restricted_controls is false, locked controls are hidden.\n" +
            "# force: optional value applied to matching local client options where supported.\n" +
            "# For per-activation rules, use activation.<activation_name>.type, .toggle_button, or .distance.\n" +
            "# For per-source rules, use overlay.source.<source_name> or volume.source.<source_name>.\n" +
            "\n" +
            "version: 1\n" +
            "\n" +
            "settings:\n" +
            "  show_restricted_controls: true\n" +
            "  show_lock_tooltip: true\n" +
            "  locked_tooltip_key: gui.mytrixvoice.server_controlled\n" +
            "\n" +
            "tabs:\n" +
            "  devices: { visible: true, enabled: true }\n" +
            "  volume: { visible: true, enabled: true }\n" +
            "  activation: { visible: true, enabled: true }\n" +
            "  overlay: { visible: false, enabled: true }\n" +
            "  advanced: { visible: false, enabled: true }\n" +
            "  hotkeys: { visible: true, enabled: true }\n" +
            "  addons: { visible: true, enabled: true }\n" +
            "\n" +
            "controls:\n" +
            "  toggles.microphone: { visible: true, enabled: true }\n" +
            "  toggles.voice: { visible: true, enabled: true }\n" +
            "\n" +
            "  devices.activation_threshold: { visible: true, enabled: true }\n" +
            "  devices.microphone: { visible: true, enabled: true }\n" +
            "  devices.microphone_volume: { visible: true, enabled: true }\n" +
            "  devices.noise_suppression: { visible: true, enabled: true }\n" +
            "  devices.stereo_capture: { visible: false, enabled: true }\n" +
            "  devices.disable_input_device: { visible: false, enabled: true }\n" +
            "  devices.output_device: { visible: true, enabled: true }\n" +
            "  devices.output_volume: { visible: true, enabled: true }\n" +
            "  devices.sound_occlusion: { visible: false, enabled: true }\n" +
            "  devices.directional_sources: { visible: false, enabled: true }\n" +
            "  devices.hrtf: { visible: false, enabled: true }\n" +
            "\n" +
            "  volume.sources: { visible: true, enabled: true }\n" +
            "  volume.players: { visible: true, enabled: true }\n" +
            "  volume.search: { visible: true, enabled: true }\n" +
            "\n" +
            "  activation.type: { visible: true, enabled: true }\n" +
            "  activation.toggle_button: { visible: true, enabled: true }\n" +
            "  activation.distance: { visible: false, enabled: true }\n" +
            "  activation.group.type: { visible: false, enabled: false }\n" +
            "  activation.group.toggle_button: { visible: false, enabled: false }\n" +
            "  activation.group.distance: { visible: false, enabled: false }\n" +
            "\n" +
            "  overlay.hud_icon: { visible: true, enabled: true }\n" +
            "  overlay.hud_position: { visible: true, enabled: true }\n" +
            "  overlay.entity_icons: { visible: false, enabled: true }\n" +
            "  overlay.static_source_icons: { visible: true, enabled: true }\n" +
            "  overlay.enable: { visible: true, enabled: true }\n" +
            "  overlay.position: { visible: false, enabled: true }\n" +
            "  overlay.style: { visible: true, enabled: true }\n" +
            "  overlay.sources: { visible: false, enabled: true }\n" +
            "\n" +
            "  advanced.visualize_voice_distance: { visible: true, enabled: true }\n" +
            "  advanced.visualize_on_join: { visible: true, enabled: true }\n" +
            "  advanced.directional_sources_angle: { visible: true, enabled: true }\n" +
            "  advanced.mono_stereo_sources: { visible: true, enabled: true }\n" +
            "  advanced.stereo_positioning: { visible: true, enabled: true }\n" +
            "  advanced.source_types_overlap: { visible: true, enabled: true }\n" +
            "  advanced.adaptive_jitter_buffer: { visible: true, enabled: true }\n" +
            "  advanced.volume_sliders: { visible: true, enabled: true }\n" +
            "  advanced.distance_gain: { visible: true, enabled: true }\n" +
            "\n" +
            "  hotkeys.general: { visible: true, enabled: true }\n" +
            "  hotkeys.distance: { visible: false, enabled: true }\n";

    public synchronized void updateAesEncryptionKey(byte[] aesKey) {
        config.voice().aesEncryptionKey(aesKey);

        if (this.defaultEncryption == null) {
            this.defaultEncryption = encryption.create("AES/CBC/PKCS5Padding", aesKey);
        } else if (defaultEncryption.getName().equals("AES/CBC/PKCS5Padding")) {
            defaultEncryption.updateKeyData(aesKey);
        }
    }

    public void startUdpServer() {
        Collection<VoiceServerPlayer> connectedPlayers = null;
        if (this.udpServer != null) {
            connectedPlayers = udpConnectionManager.getConnections()
                    .stream()
                    .map(UdpServerConnection::getPlayer)
                    .collect(Collectors.toList());
            stopUdpServer();
        }

        UdpServer server = new NettyUdpServer(this);

        UdpServerCreateEvent createUdpServerEvent = new UdpServerCreateEvent(server);
        eventBus.fire(createUdpServerEvent);

        server = createUdpServerEvent.getServer();

        try {
            int port = config.host().port();
            if (port == 0) {
                port = getMinecraftServer().getPort();
                if (port <= 0) port = 0;
            }

            server.start(config.host().ip(), port);
            this.udpServer = server;
            eventBus.fire(new UdpServerStartedEvent(server));

            if (connectedPlayers != null) {
                connectedPlayers.forEach(tcpPacketManager::requestPlayerInfo);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to start the udp server", e);
        }
    }

    private void stopUdpServer() {
        if (this.udpServer != null) {
            this.udpServer.stop();
            eventBus.fire(new UdpServerStoppedEvent(udpServer));
            this.udpServer = null;
        }
    }

    private void checkForUpdates() {
        if (config.checkForUpdates()) {
            backgroundExecutor.execute(() -> {
                try {
                    ModrinthVersion.checkForUpdates(getVersion(), getMinecraftServer().getVersion(), loader)
                            .ifPresent(version -> LOGGER.warn(
                                    "New version available {}: {}",
                                    version.version(),
                                    version.downloadLink())
                            );
                } catch (IOException e) {
                    LOGGER.error("Failed to check for updates", e);
                }
            });
        }
    }

    protected void registerDefaultCommandsAndPermissions(
            @NotNull McCommandManager<McCommand> commandManager,
            @NotNull McServerLib minecraftServer
    ) {
        // register permissions
        PermissionManager permissions = minecraftServer.getPermissionManager();

        permissions.register("pv.list", PermissionDefault.TRUE);
        permissions.register("pv.reconnect", PermissionDefault.TRUE);

        permissions.register("pv.allow_freecam", PermissionDefault.TRUE);
        permissions.register("mytrixvoice.admin", PermissionDefault.OP);
        permissions.register("mytrixvoice.group", PermissionDefault.OP);
        permissions.register("mytrixvoice.groups", PermissionDefault.TRUE);
        permissions.register("mytrixvoice.groups.manage", PermissionDefault.OP);
        permissions.register("mytrixvoice.distance", PermissionDefault.OP);

        // register commands
        commandManager.register("vlist", new VoiceListCommand(this));
        commandManager.register("vrc", new VoiceReconnectCommand(this));
        commandManager.register("vreload", new VoiceReloadCommand(this));

        commandManager.register("vmute", new VoiceMuteCommand(this, getMinecraftServer()));
        commandManager.register("vunmute", new VoiceUnmuteCommand(this, getMinecraftServer()));
        commandManager.register("vmutelist", new VoiceMuteListCommand(this, getMinecraftServer()));
        commandManager.register("mytrixvoice", new MytrixVoiceCommand(this));
        commandManager.register("vcgroup", new MytrixVoiceCommand(this, true), "vgroup");
        commandManager.register("groups", new VoiceGroupsCommand(this), "group", "party", "voicegroup");
        commandManager.register("vcdistance", new VoiceDistanceCommand(this), "vdistance", "proximitydistance");
    }

    public boolean removeProximitySourceOwnedBy(@NotNull UUID playerId) {
        return proximityActivation.removePlayerSource(playerId);
    }

    public boolean removeGroupSourcesOwnedBy(@NotNull UUID playerId) {
        return groupActivation.removePlayerSources(playerId);
    }

    public boolean removeGroupSourcesForChannel(@NotNull VoiceChannelId channelId) {
        return groupActivation.removeChannelSources(channelId);
    }

    public boolean removeGroupRecipientFromChannel(@NotNull VoiceChannelId channelId, @NotNull UUID playerId) {
        return groupActivation.removeChannelRecipient(channelId, playerId);
    }

    /**
     * Legacy compatibility hook.
     *
     * Group replacement is now performed on the client by sending the captured
     * frame with {@link VoiceActivation#GROUP_ID}. Proximity packets must never
     * be reinterpreted or forwarded as group packets on the server.
     */
    @Override
    public boolean routeProximityPacketAsGroup(@NotNull VoicePlayer player, @NotNull PlayerAudioPacket packet) {
        return false;
    }

    /**
     * Legacy compatibility hook matching {@link #routeProximityPacketAsGroup}.
     */
    @Override
    public boolean routeProximityEndAsGroup(@NotNull VoicePlayer player, @NotNull PlayerAudioEndPacket packet) {
        return false;
    }

    @Override
    public Optional<UdpServer> getUdpServer() {
        return Optional.ofNullable(udpServer);
    }

    @Override
    public @NotNull AudioEncoder createOpusEncoder(boolean stereo) {
        if (config == null) throw new IllegalStateException("server is not initialized yet");

        int sampleRate = config.voice().sampleRate();

        return codecs.createEncoder(
                new OpusEncoderInfo(
                        OpusMode.valueOf(config.voice().opus().mode()),
                        config.voice().opus().bitrate()
                ),
                sampleRate,
                stereo,
                config.voice().mtuSize()
        );
    }

    @Override
    public @NotNull AudioDecoder createOpusDecoder(boolean stereo) {
        if (config == null) throw new IllegalStateException("server is not initialized yet");

        int sampleRate = config.voice().sampleRate();
        return codecs.createDecoder(
                new OpusDecoderInfo(),
                sampleRate,
                stereo,
                (sampleRate / 1_000) * 20
        );
    }
}
