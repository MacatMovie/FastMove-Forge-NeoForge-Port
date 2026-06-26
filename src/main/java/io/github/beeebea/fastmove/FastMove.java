package io.github.beeebea.fastmove;

import io.github.beeebea.fastmove.config.FMConfig;
import io.github.beeebea.fastmove.network.MoveStatePayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;
import java.util.function.Supplier;

@Mod(FastMove.MOD_ID)
public class FastMove {
    public static final String MOD_ID = "fastmove";
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel NETWORK = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .clientAcceptedVersions(PROTOCOL_VERSION::equals)
            .serverAcceptedVersions(PROTOCOL_VERSION::equals)
            .simpleChannel();

    public static final FMConfig CONFIG = FMConfig.createAndLoad();
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Object QUEUE_LOCK = new Object();
    private static final Queue<Runnable> ACTION_QUEUE = new LinkedList<>();

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

        registerPackets();
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        initClient(modEventBus);
    }

    public static FMConfig getConfig() {
        return CONFIG;
    }

    private static void registerPackets() {
        int id = 0;
        NETWORK.messageBuilder(MoveStatePayload.class, id++)
                .encoder(MoveStatePayload::encode)
                .decoder(MoveStatePayload::decode)
                .consumerMainThread(FastMove::handleMoveState)
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

        ServerPlayer source = receiver.server.getPlayerList().getPlayer(payload.uuid());
        MoveState moveState = MoveState.STATE(payload.moveStateInt());
        if (source != null) {
            ((IFastPlayer) source).fastmove_setMoveState(moveState);
            sendToClients(source, payload.uuid(), payload.moveStateInt());
        }
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
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
                        NETWORK.send(PacketDistributor.PLAYER.with(() -> target), new MoveStatePayload(uuid, moveStateInt));
                    }
                }
            });
        }
    }

    public static boolean UseCombatRoll() {
        // Fabric-only compat in the original mod. Kept disabled in the Forge port so vanilla hunger stamina is used.
        return false;
    }

    public static boolean UseParaglider() {
        // Fabric-only compat in the original mod. Kept disabled in the Forge port so vanilla hunger stamina is used.
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
