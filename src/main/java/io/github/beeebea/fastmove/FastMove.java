package io.github.beeebea.fastmove;

import io.github.beeebea.fastmove.config.FMConfig;
import io.github.beeebea.fastmove.network.MoveStatePayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

@Mod(FastMove.MOD_ID)
public class FastMove {
    public static final String MOD_ID = "fastmove";
    public static final FMConfig CONFIG = FMConfig.createAndLoad();
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Object QUEUE_LOCK = new Object();
    private static final Queue<Runnable> ACTION_QUEUE = new LinkedList<>();

    public static IMoveStateUpdater moveStateUpdater;
    public static IFastMoveInput INPUT;

    public FastMove(IEventBus modEventBus) {
        LOGGER.info("initializing FastMove :3");

        moveStateUpdater = new IMoveStateUpdater();
        INPUT = new IFastMoveInput() {
            @Override public boolean ismoveUpKeyPressed() { return false; }
            @Override public boolean ismoveDownKeyPressed() { return false; }
            @Override public boolean ismoveUpKeyPressedLastTick() { return false; }
            @Override public boolean ismoveDownKeyPressedLastTick() { return false; }
        };

        modEventBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        initClient(modEventBus);
    }

    public static FMConfig getConfig() {
        return CONFIG;
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playBidirectional(
                MoveStatePayload.TYPE,
                MoveStatePayload.STREAM_CODEC,
                new DirectionalPayloadHandler<>(
                        FastMove::handleMoveStateClient,
                        FastMove::handleMoveStateServer
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

        ServerPlayer source = receiver.server.getPlayerList().getPlayer(payload.uuid());
        MoveState moveState = MoveState.STATE(payload.moveStateInt());
        if (source != null) {
            ((IFastPlayer) source).fastmove_setMoveState(moveState);
            sendToClients(source, payload.uuid(), payload.moveStateInt());
        }
    }

    private void onServerTick(ServerTickEvent.Post event) {
        synchronized (QUEUE_LOCK) {
            while (!ACTION_QUEUE.isEmpty()) {
                ACTION_QUEUE.poll().run();
            }
        }
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

    public static boolean UseCombatRoll() {
        // Fabric-only compat in the original mod. Kept disabled in the NeoForge port so vanilla hunger stamina is used.
        return false;
    }

    public static boolean UseParaglider() {
        // Fabric-only compat in the original mod. Kept disabled in the NeoForge port so vanilla hunger stamina is used.
        return false;
    }

    private static void initClient(IEventBus modEventBus) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        try {
            Class.forName("io.github.beeebea.fastmove.client.FastMoveClient")
                    .getMethod("init", IEventBus.class)
                    .invoke(null, modEventBus);
        } catch (ClassNotFoundException ignored) {
            // Dedicated server.
        } catch (Throwable throwable) {
            LOGGER.error("Failed to initialize FastMove client hooks", throwable);
        }
    }
}
