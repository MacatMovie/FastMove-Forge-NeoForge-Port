package io.github.beeebea.fastmove;

import io.github.beeebea.fastmove.config.FMClientConfig;
import io.github.beeebea.fastmove.config.FMConfig;
import io.github.beeebea.fastmove.network.ConfigSyncPayload;
import io.github.beeebea.fastmove.network.MoveStatePayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

@Mod(FastMove.MOD_ID)
public class FastMove {
    public static final String MOD_ID = "fastmove";
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

    public FastMove(IEventBus modEventBus, ModContainer modContainer) {
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
        modContainer.registerConfig(ModConfig.Type.COMMON, FMConfig.SPEC, FMConfig.FILE_NAME);
        modContainer.registerConfig(ModConfig.Type.CLIENT, FMClientConfig.SPEC, FMClientConfig.FILE_NAME);

        modEventBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        initClient(modEventBus, modContainer);
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

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("3");
        registrar.playBidirectional(
                MoveStatePayload.TYPE,
                MoveStatePayload.STREAM_CODEC,
                new DirectionalPayloadHandler<>(
                        FastMove::handleMoveStateClient,
                        FastMove::handleMoveStateServer
                )
        );
        registrar.playBidirectional(
                ConfigSyncPayload.TYPE,
                ConfigSyncPayload.STREAM_CODEC,
                new DirectionalPayloadHandler<>(
                        FastMove::handleConfigSyncClient,
                        FastMove::handleConfigSyncServer
                )
        );
    }

    private static void handleMoveStateClient(final MoveStatePayload payload, final IPayloadContext context) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        try {
            Class.forName("io.github.beeebea.fastmove.client.FastMoveClient")
                    .getMethod("handleMoveStatePayload", MoveStatePayload.class, IPayloadContext.class)
                    .invoke(null, payload, context);
        } catch (Throwable throwable) {
            LOGGER.error("Failed to handle FastMove client payload", throwable);
        }
    }

    private static void handleMoveStateServer(final MoveStatePayload payload, final IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer receiver)) return;

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
            PacketDistributor.sendToPlayer(receiver, new MoveStatePayload(receiver.getUUID(), MoveState.STATE(moveState)));
        }
    }


    private static void applyMovementHungerCost(ServerPlayer player, MoveState oldState, MoveState newState) {
        // Creative mode ignores vanilla hunger/exhaustion, so FastMove must not secretly accumulate
        // exhaustion that becomes visible after the player switches back to survival.
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

        if (amount > 0) {
            player.getFoodData().addExhaustion((float) amount);
        }
    }

    private static void handleConfigSyncClient(final ConfigSyncPayload payload, final IPayloadContext context) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        try {
            setSyncedServerConfig(FMConfig.fromNetworkJson(payload.configJson()));
        } catch (Throwable throwable) {
            LOGGER.error("Failed to apply FastMove server config sync", throwable);
        }
    }

    private static void handleConfigSyncServer(final ConfigSyncPayload payload, final IPayloadContext context) {
        // Config sync is server -> client only. Ignore client-sent config sync packets.
    }


    private void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != FMConfig.SPEC) return;

        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        String configJson = CONFIG.toNetworkJson();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, new ConfigSyncPayload(configJson));
        }
        LOGGER.info("Reloaded FastMove gameplay config and synced it to connected players.");
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player, new ConfigSyncPayload(CONFIG.toNetworkJson()));
        }
    }

    private void onServerTick(ServerTickEvent.Post event) {
        synchronized (QUEUE_LOCK) {
            while (!ACTION_QUEUE.isEmpty()) {
                ACTION_QUEUE.poll().run();
            }
        }
        tickFastMoveAnticheatAllowances();
    }

    public static void sendToClients(Player source, UUID uuid, int moveStateInt) {
        if (!(source instanceof ServerPlayer serverSource)) return;
        synchronized (QUEUE_LOCK) {
            ACTION_QUEUE.add(() -> {
                for (ServerPlayer target : serverSource.server.getPlayerList().getPlayers()) {
                    if (target != source && target.distanceToSqr(source) < 6400) {
                        PacketDistributor.sendToPlayer(target, new MoveStatePayload(uuid, moveStateInt));
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
        if (player == null) return vanillaThreshold;
        if (!hasFastMoveAnticheatAllowance(player)) return vanillaThreshold;

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
        if (ticks > 0) {
            FASTMOVE_ANTICHEAT_TICKS.put(player.getUUID(), ticks);
        } else {
            FASTMOVE_ANTICHEAT_TICKS.remove(player.getUUID());
        }
    }

    private static int fastMoveAnticheatTicks(MoveState moveState) {
        if (moveState == MoveState.SLIDING) {
            // Keep the movement allowance alive for the whole configured glide plus a small
            // transition margin; balanced slides can intentionally last several seconds now.
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
            if (ticksLeft <= 0) {
                iterator.remove();
            } else {
                entry.setValue(ticksLeft);
            }
        }
    }

    public static boolean UseCombatRoll() {
        // Fabric-only compat in the original mod. Kept disabled in the NeoForge port so vanilla hunger/exhaustion is used.
        return false;
    }

    public static boolean UseParaglider() {
        // Fabric-only compat in the original mod. Kept disabled in the NeoForge port so vanilla hunger/exhaustion is used.
        return false;
    }

    private static void initClient(IEventBus modEventBus, ModContainer modContainer) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        try {
            Class.forName("io.github.beeebea.fastmove.client.FastMoveClient")
                    .getMethod("init", IEventBus.class, ModContainer.class)
                    .invoke(null, modEventBus, modContainer);
        } catch (ClassNotFoundException ignored) {
            // Dedicated server.
        } catch (Throwable throwable) {
            LOGGER.error("Failed to initialize FastMove client hooks", throwable);
        }
    }
}
