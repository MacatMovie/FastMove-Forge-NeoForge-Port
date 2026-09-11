package io.github.beeebea.fastmove;

import io.github.beeebea.fastmove.config.FMClientConfig;
import io.github.beeebea.fastmove.config.FMConfig;
import io.github.beeebea.fastmove.network.ConfigSyncPayload;
import io.github.beeebea.fastmove.network.MoveStatePayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.function.Supplier;

@Mod(FastMove.MOD_ID)
public class FastMove {
    public static final String MOD_ID = "fastmove";
    private static final String PROTOCOL_VERSION = "3";

    public static final SimpleChannel NETWORK = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .clientAcceptedVersions(PROTOCOL_VERSION::equals)
            .serverAcceptedVersions(PROTOCOL_VERSION::equals)
            .simpleChannel();

    public static final FMConfig CONFIG = FMConfig.LOCAL;
    private static final FMConfig DISABLED_UNTIL_SERVER_SYNC_CONFIG = FMConfig.disabledConfig();
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Object QUEUE_LOCK = new Object();
    private static final Queue<Runnable> ACTION_QUEUE = new LinkedList<>();
    private static final Map<UUID, Integer> FASTMOVE_ANTICHEAT_TICKS = new HashMap<>();

    private static FMConfig syncedServerConfig = null;
    private static boolean waitingForServerConfig = false;

    public static IMoveStateUpdater moveStateUpdater;
    public static IFastMoveInput INPUT;

    public FastMove() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        LOGGER.info("initializing FastMove :3");

        moveStateUpdater = new IMoveStateUpdater();
        INPUT = new IFastMoveInput() {
            @Override public boolean ismoveUpKeyPressed() { return false; }
            @Override public boolean ismoveDownKeyPressed() { return false; }
            @Override public boolean ismoveUpKeyPressedLastTick() { return false; }
            @Override public boolean ismoveDownKeyPressedLastTick() { return false; }
        };

        FMConfig.prepareLegacyMigration();
        FMClientConfig.preparePass1AMigration();
        modEventBus.addListener(FMConfig::onConfigLoading);
        modEventBus.addListener(FMClientConfig::onConfigLoading);
        modEventBus.addListener(this::onConfigReloading);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, FMConfig.SPEC, FMConfig.FILE_NAME);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, FMClientConfig.SPEC, FMClientConfig.FILE_NAME);

        registerPackets();
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        initClient(modEventBus);
    }

    public static FMConfig getConfig() {
        if (syncedServerConfig != null) return syncedServerConfig;
        return waitingForServerConfig ? DISABLED_UNTIL_SERVER_SYNC_CONFIG : CONFIG;
    }

    public static void waitForServerConfig() {
        if (syncedServerConfig == null) {
            waitingForServerConfig = true;
            LOGGER.info("Waiting for FastMove config sync from server.");
        }
    }

    public static void setSyncedServerConfig(FMConfig config) {
        syncedServerConfig = config;
        waitingForServerConfig = false;
        LOGGER.info("Using FastMove config synced from server.");
    }

    public static void clearSyncedServerConfig() {
        if (syncedServerConfig != null || waitingForServerConfig) {
            LOGGER.info("Cleared synced FastMove server config; using local config again.");
        }
        syncedServerConfig = null;
        waitingForServerConfig = false;
    }

    private static void registerPackets() {
        int id = 0;
        NETWORK.messageBuilder(MoveStatePayload.class, id++)
                .encoder(MoveStatePayload::encode)
                .decoder(MoveStatePayload::decode)
                .consumerMainThread(FastMove::handleMoveState)
                .add();
        NETWORK.messageBuilder(ConfigSyncPayload.class, id++)
                .encoder(ConfigSyncPayload::encode)
                .decoder(ConfigSyncPayload::decode)
                .consumerMainThread(FastMove::handleConfigSync)
                .add();
    }

    private static void handleMoveState(final MoveStatePayload payload, final Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            handleMoveStateClient(payload);
        } else {
            handleMoveStateServer(payload, context);
        }
        context.setPacketHandled(true);
    }

    private static void handleConfigSync(final ConfigSyncPayload payload, final Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            handleConfigSyncClient(payload);
        }
        context.setPacketHandled(true);
    }

    private static void handleMoveStateClient(final MoveStatePayload payload) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        try {
            Class.forName("io.github.beeebea.fastmove.client.FastMoveClient")
                    .getMethod("handleMoveStatePayload", MoveStatePayload.class)
                    .invoke(null, payload);
        } catch (Throwable throwable) {
            LOGGER.error("Failed to handle FastMove client payload", throwable);
        }
    }

    private static void handleMoveStateServer(final MoveStatePayload payload, final NetworkEvent.Context context) {
        ServerPlayer receiver = context.getSender();
        if (receiver == null) return;

        MoveState requestedMoveState = MoveState.STATE(payload.moveStateInt());
        MoveState moveState = isMoveStateAllowedByServerConfig(requestedMoveState) ? requestedMoveState : MoveState.NONE;
        MoveState oldMoveState = ((IFastPlayer) receiver).fastmove_getMoveState();

        ((IFastPlayer) receiver).fastmove_setMoveState(moveState);
        if (!moveState.equals(oldMoveState)) {
            applyMovementHungerCost(receiver, oldMoveState, moveState);
            noteAcceptedMoveState(receiver, moveState);
        }
        sendToClients(receiver, receiver.getUUID(), MoveState.STATE(moveState));
        if (!moveState.equals(requestedMoveState)) {
            NETWORK.send(PacketDistributor.PLAYER.with(() -> receiver), new MoveStatePayload(receiver.getUUID(), MoveState.STATE(moveState)));
        }
    }

    private static void applyMovementHungerCost(ServerPlayer player, MoveState oldState, MoveState newState) {
        if (player.getAbilities().instabuild) return;

        int amount = 0;
        if (newState == MoveState.SLIDING && oldState != MoveState.SLIDING) {
            amount = CONFIG.slideHungerCost();
        } else if (newState == MoveState.ROLLING && oldState != MoveState.ROLLING) {
            amount = CONFIG.diveRollHungerCost();
        } else if ((newState == MoveState.WALLRUNNING_LEFT || newState == MoveState.WALLRUNNING_RIGHT)
                && oldState != MoveState.WALLRUNNING_LEFT && oldState != MoveState.WALLRUNNING_RIGHT) {
            amount = CONFIG.wallRunHungerCost();
        }

        if (amount > 0) player.getFoodData().addExhaustion((float) amount);
    }

    private static void handleConfigSyncClient(final ConfigSyncPayload payload) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        try {
            setSyncedServerConfig(FMConfig.fromNetworkJson(payload.configJson()));
        } catch (Throwable throwable) {
            LOGGER.error("Failed to apply FastMove server config sync", throwable);
        }
    }

    private void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != FMConfig.SPEC) return;
        syncGameplayConfigToConnectedPlayers();
        LOGGER.info("Reloaded FastMove gameplay config and synced it to connected players.");
    }

    /** Used by the Forge custom config screen so an integrated server updates immediately. */
    public static void syncGameplayConfigToConnectedPlayers() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        String configJson = CONFIG.toNetworkJson();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            NETWORK.send(PacketDistributor.PLAYER.with(() -> player), new ConfigSyncPayload(configJson));
        }
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            NETWORK.send(PacketDistributor.PLAYER.with(() -> player), new ConfigSyncPayload(CONFIG.toNetworkJson()));
        }
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        synchronized (QUEUE_LOCK) {
            while (!ACTION_QUEUE.isEmpty()) ACTION_QUEUE.poll().run();
        }
        tickFastMoveAnticheatAllowances();
    }

    public static void sendToClients(Player source, UUID uuid, int moveStateInt) {
        if (!(source instanceof ServerPlayer serverSource)) return;
        synchronized (QUEUE_LOCK) {
            ACTION_QUEUE.add(() -> {
                for (ServerPlayer target : serverSource.server.getPlayerList().getPlayers()) {
                    if (target != source && target.distanceToSqr(source) < 6400) {
                        NETWORK.send(PacketDistributor.PLAYER.with(() -> target), new MoveStatePayload(uuid, moveStateInt));
                    }
                }
            });
        }
    }

    public static boolean isMoveStateAllowedByServerConfig(MoveState moveState) {
        if (moveState == null || moveState == MoveState.NONE) return true;
        if (!CONFIG.enableFastMove()) return false;
        if (moveState == MoveState.SLIDING) return CONFIG.slideEnabled();
        if (moveState == MoveState.ROLLING) return CONFIG.diveRollEnabled();
        if (moveState == MoveState.PRONE) return CONFIG.slideEnabled() || CONFIG.diveRollEnabled();
        if (moveState == MoveState.WALLRUNNING_LEFT || moveState == MoveState.WALLRUNNING_RIGHT) return CONFIG.wallRunEnabled();
        return false;
    }

    public static double adjustMovedTooQuicklyThreshold(ServerPlayer player, double vanillaThreshold) {
        if (player == null || !hasFastMoveAnticheatAllowance(player)) return vanillaThreshold;
        MoveState moveState = ((IFastPlayer) player).fastmove_getMoveState();
        if (!isMoveStateAllowedByServerConfig(moveState)) return vanillaThreshold;

        double extraBlocksPerTick = fastMoveExtraBlocksPerTick(moveState);
        if (extraBlocksPerTick <= 0.0D) return vanillaThreshold;
        double vanillaBlocksPerTick = Math.sqrt(vanillaThreshold);
        double adjustedBlocksPerTick = vanillaBlocksPerTick + extraBlocksPerTick + 2.0D;
        return Math.max(vanillaThreshold, adjustedBlocksPerTick * adjustedBlocksPerTick);
    }

    private static double fastMoveExtraBlocksPerTick(MoveState moveState) {
        if (moveState == MoveState.SLIDING) {
            double momentumAllowance = CONFIG.legacyMovementBoosts()
                    ? Math.max(1.0D, CONFIG.legacyMomentumMultiplier()) * Math.max(0.0D, CONFIG.slideSpeedBoostMultiplier())
                    : Math.max(0.0D, CONFIG.slideJumpMomentumMultiplier());
            return 0.2D * Math.max(0.0D, CONFIG.slideSpeedBoostMultiplier()) + 0.2D * momentumAllowance;
        }
        if (moveState == MoveState.ROLLING) {
            double momentumAllowance = CONFIG.legacyMovementBoosts()
                    ? Math.max(1.0D, CONFIG.legacyMomentumMultiplier()) * Math.max(0.0D, CONFIG.diveRollSpeedBoostMultiplier())
                    : Math.max(0.0D, CONFIG.diveRollJumpMomentumMultiplier());
            return 0.1D * Math.max(0.0D, CONFIG.diveRollSpeedBoostMultiplier()) + 0.1D * momentumAllowance;
        }
        if (moveState == MoveState.WALLRUNNING_LEFT || moveState == MoveState.WALLRUNNING_RIGHT) {
            return Math.max(CONFIG.wallRunBoostSpeedCap() + CONFIG.wallRunEntrySpeedBoost(),
                    0.6D * Math.max(0.0D, CONFIG.wallJumpBoostMultiplier()));
        }
        return 0.0D;
    }

    private static void noteAcceptedMoveState(ServerPlayer player, MoveState moveState) {
        int ticks = fastMoveAnticheatTicks(moveState);
        if (ticks > 0) FASTMOVE_ANTICHEAT_TICKS.put(player.getUUID(), ticks);
        else FASTMOVE_ANTICHEAT_TICKS.remove(player.getUUID());
    }

    private static int fastMoveAnticheatTicks(MoveState moveState) {
        if (moveState == MoveState.SLIDING) {
            return Math.max(CONFIG.slideDurationTicks() + 10,
                    (int) Math.ceil(12.0D + 8.0D * Math.max(0.0D, CONFIG.slideSpeedBoostMultiplier())));
        }
        if (moveState == MoveState.ROLLING) {
            return Math.max(12, (int) Math.ceil(12.0D + 8.0D * Math.max(0.0D, CONFIG.diveRollSpeedBoostMultiplier())));
        }
        if (moveState == MoveState.WALLRUNNING_LEFT || moveState == MoveState.WALLRUNNING_RIGHT) {
            return Math.max(12, CONFIG.wallRunDurationTicks() + 10);
        }
        return 0;
    }

    private static boolean hasFastMoveAnticheatAllowance(ServerPlayer player) {
        return FASTMOVE_ANTICHEAT_TICKS.getOrDefault(player.getUUID(), 0) > 0;
    }

    private static void tickFastMoveAnticheatAllowances() {
        Iterator<Map.Entry<UUID, Integer>> iterator = FASTMOVE_ANTICHEAT_TICKS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            int ticksLeft = entry.getValue() - 1;
            if (ticksLeft <= 0) iterator.remove();
            else entry.setValue(ticksLeft);
        }
    }

    public static boolean UseCombatRoll() { return false; }
    public static boolean UseParaglider() { return false; }

    private static void initClient(IEventBus modEventBus) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        try {
            Class.forName("io.github.beeebea.fastmove.client.FastMoveClient")
                    .getMethod("init", IEventBus.class)
                    .invoke(null, modEventBus);
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable throwable) {
            LOGGER.error("Failed to initialize FastMove client hooks", throwable);
        }
    }
}
