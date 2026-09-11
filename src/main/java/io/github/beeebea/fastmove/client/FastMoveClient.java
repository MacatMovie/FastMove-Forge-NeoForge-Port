package io.github.beeebea.fastmove.client;

import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import io.github.beeebea.fastmove.FastMove;
import io.github.beeebea.fastmove.IAnimatedPlayer;
import io.github.beeebea.fastmove.IFastPlayer;
import io.github.beeebea.fastmove.IMoveStateUpdater;
import io.github.beeebea.fastmove.MoveState;
import io.github.beeebea.fastmove.config.FMClientConfig;
import io.github.beeebea.fastmove.network.MoveStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;

import java.util.HashMap;
import java.util.Map;

public final class FastMoveClient {
    private static final Map<String, KeyframeAnimation> ANIMATIONS = new HashMap<>();
    private static float previousCameraRoll;
    private static float cameraRoll;
    private static float previousCameraPitchOffset;
    private static float cameraPitchOffset;
    private static float previousDiveRollProgress;
    private static float diveRollProgress;
    private static int diveRollCameraTicks;
    private static boolean wasDiveRolling;

    private FastMoveClient() {
    }

    public static void init(IEventBus modEventBus) {
        FastMove.LOGGER.info("initializing FastMove Client :3");

        FastMoveInput input = new FastMoveInput();
        FastMove.INPUT = input;

        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new FastMoveConfigScreen(parent))
        );

        modEventBus.addListener(FastMoveInput::registerKeyMappings);
        MinecraftForge.EVENT_BUS.addListener(FastMoveClient::onMovementInputUpdate);
        MinecraftForge.EVENT_BUS.addListener(FastMoveClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(FastMoveClient::onClientLogin);
        MinecraftForge.EVENT_BUS.addListener(FastMoveClient::onClientLogout);
        MinecraftForge.EVENT_BUS.addListener(FastMoveClient::onCameraAngles);

        FastMove.moveStateUpdater = new IMoveStateUpdater() {
            @Override
            public void setMoveState(Player player, MoveState moveState) {
                FastMove.NETWORK.sendToServer(new MoveStatePayload(player.getUUID(), MoveState.STATE(moveState)));
            }

            @Override
            public void setAnimationState(Player player, MoveState moveState) {
                if (!(player instanceof IAnimatedPlayer animatedPlayer)) return;

                var animationContainer = animatedPlayer.fastmove_getModAnimation();
                var animationBodyContainer = animatedPlayer.fastmove_getModAnimationBody();
                if (animationContainer == null || animationBodyContainer == null) return;

                if (ANIMATIONS.isEmpty()) updateAnimations();

                int fadeTicks = moveState == MoveState.SLIDING ? 2 : 10;
                var fade = AbstractFadeModifier.standardFadeIn(fadeTicks, Ease.INOUTQUAD);
                var anim = moveState == MoveState.PRONE ? null : ANIMATIONS.get(moveState.name);
                if (anim == null) {
                    animationBodyContainer.replaceAnimationWithFade(fade, null);
                    animationContainer.replaceAnimationWithFade(fade, null);
                    return;
                }

                var bodyLayer = new KeyframeAnimationPlayer(anim);
                var bodyVal = bodyLayer.bodyParts.get("body");
                bodyLayer.bodyParts.clear();
                if (bodyVal != null) bodyLayer.bodyParts.put("body", bodyVal);
                animationBodyContainer.replaceAnimationWithFade(fade, bodyLayer);
                animationContainer.replaceAnimationWithFade(fade, new KeyframeAnimationPlayer(anim));
            }
        };
    }

    private static void onMovementInputUpdate(MovementInputUpdateEvent event) {
        if (FastMove.INPUT instanceof FastMoveInput input) input.onMovementInputUpdate(event);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        previousCameraRoll = cameraRoll;
        previousCameraPitchOffset = cameraPitchOffset;
        previousDiveRollProgress = diveRollProgress;

        Minecraft client = Minecraft.getInstance();
        MoveState state = client.player != null
                ? ((IFastPlayer) client.player).fastmove_getMoveState()
                : MoveState.NONE;

        float rollTarget = 0.0F;
        float pitchTarget = 0.0F;
        if (client.player != null && client.options.getCameraType().isFirstPerson()) {
            if (FMClientConfig.INSTANCE.wallRunCameraTiltEnabled()) {
                float degrees = (float) FMClientConfig.INSTANCE.wallRunCameraTiltDegrees();
                if (state == MoveState.WALLRUNNING_LEFT) rollTarget = degrees;
                if (state == MoveState.WALLRUNNING_RIGHT) rollTarget = -degrees;
            }

            if (FMClientConfig.INSTANCE.slideCameraTiltEnabled() && state == MoveState.SLIDING) {
                pitchTarget = -(float) FMClientConfig.INSTANCE.slideCameraTiltDegrees();
            }
        }

        float wallSmoothing = (float) FMClientConfig.INSTANCE.wallRunCameraTiltSmoothing();
        cameraRoll += (rollTarget - cameraRoll) * wallSmoothing;
        if (Math.abs(cameraRoll) < 0.001F && rollTarget == 0.0F) cameraRoll = 0.0F;

        float slideSmoothing = (float) FMClientConfig.INSTANCE.slideCameraTiltSmoothing();
        cameraPitchOffset += (pitchTarget - cameraPitchOffset) * slideSmoothing;
        if (Math.abs(cameraPitchOffset) < 0.001F && pitchTarget == 0.0F) cameraPitchOffset = 0.0F;

        boolean diveRolling = client.player != null
                && client.options.getCameraType().isFirstPerson()
                && FMClientConfig.INSTANCE.diveRollCameraRollEnabled()
                && state == MoveState.ROLLING;
        if (diveRolling) {
            if (!wasDiveRolling) {
                diveRollCameraTicks = 0;
                previousDiveRollProgress = 0.0F;
            }
            diveRollCameraTicks++;
            int duration = Math.max(1, FastMove.getConfig().rollDurationTicks());
            diveRollProgress = Math.min(1.0F, diveRollCameraTicks / (float) duration);
        } else {
            diveRollCameraTicks = 0;
            diveRollProgress = 0.0F;
            previousDiveRollProgress = 0.0F;
        }
        wasDiveRolling = diveRolling;

    }

    private static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !client.options.getCameraType().isFirstPerson()) return;

        float partialTick = (float) event.getPartialTick();
        event.setRoll(event.getRoll() + getCameraRoll(partialTick));
        event.setPitch(event.getPitch() + getCameraPitchOffset(partialTick) + getDiveRollCameraPitch(partialTick));
    }

    public static float getCameraRoll(float partialTick) {
        return previousCameraRoll + (cameraRoll - previousCameraRoll) * partialTick;
    }

    public static float getCameraPitchOffset(float partialTick) {
        return previousCameraPitchOffset + (cameraPitchOffset - previousCameraPitchOffset) * partialTick;
    }

    public static float getDiveRollCameraPitch(float partialTick) {
        float progress = previousDiveRollProgress + (diveRollProgress - previousDiveRollProgress) * partialTick;
        float eased = progress * progress * (3.0F - 2.0F * progress);
        return 360.0F * eased;
    }

    private static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        FastMove.waitForServerConfig();
    }

    private static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        FastMove.clearSyncedServerConfig();
        previousCameraRoll = 0.0F;
        cameraRoll = 0.0F;
        previousCameraPitchOffset = 0.0F;
        cameraPitchOffset = 0.0F;
        previousDiveRollProgress = 0.0F;
        diveRollProgress = 0.0F;
        diveRollCameraTicks = 0;
        wasDiveRolling = false;
    }

    public static void handleMoveStatePayload(final MoveStatePayload payload) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        MoveState moveState = MoveState.STATE(payload.moveStateInt());
        IFastPlayer fastPlayer = (IFastPlayer) client.level.getPlayerByUUID(payload.uuid());
        if (fastPlayer != null) fastPlayer.fastmove_setMoveState(moveState);
    }

    public static void updateAnimations() {
        ANIMATIONS.clear();
        for (var entry : MoveState.STATES.values()) {
            var name = entry.name;
            if (name.equals("none")) continue;
            KeyframeAnimation animation = PlayerAnimationRegistry.getAnimation(
                    new ResourceLocation(FastMove.MOD_ID, entry.name)
            );
            if (animation != null) ANIMATIONS.put(entry.name, animation);
        }
    }
}
