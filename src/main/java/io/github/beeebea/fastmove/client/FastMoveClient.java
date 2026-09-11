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
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.network.PacketDistributor;

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

    public static void init(IEventBus modEventBus, ModContainer modContainer) {
        FastMove.LOGGER.info("initializing FastMove Client :3");

        FastMoveInput input = new FastMoveInput();
        FastMove.INPUT = input;

        modContainer.registerExtensionPoint(IConfigScreenFactory.class, (minecraft, parent) ->
                new ConfigurationScreen(
                        modContainer,
                        parent,
                        (screen, type, config, title) -> new FastMoveConfigurationSectionScreen(screen, type, config, title)
                )
        );
        modEventBus.addListener(FastMoveInput::registerKeyMappings);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(FastMoveClient::onMovementInputUpdate);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(FastMoveClient::onClientTick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(FastMoveClient::onClientLogin);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(FastMoveClient::onClientLogout);

        FastMove.moveStateUpdater = new IMoveStateUpdater() {
            @Override
            public void setMoveState(Player player, MoveState moveState) {
                PacketDistributor.sendToServer(new MoveStatePayload(player.getUUID(), MoveState.STATE(moveState)));
            }

            @Override
            public void setAnimationState(Player player, MoveState moveState) {
                if (!(player instanceof IAnimatedPlayer animatedPlayer)) return;

                var animationContainer = animatedPlayer.fastmove_getModAnimation();
                var animationBodyContainer = animatedPlayer.fastmove_getModAnimationBody();
                if (animationContainer == null || animationBodyContainer == null) return;

                if (ANIMATIONS.isEmpty()) updateAnimations();

                // Sliding should snap into its low pose almost immediately. The old 10-tick fade
                // made the first half-second feel like the player was slowly ramping into the slide
                // even though the movement boost itself was already being applied immediately.
                int fadeTicks = moveState == MoveState.SLIDING ? 2 : 10;
                var fade = AbstractFadeModifier.standardFadeIn(fadeTicks, Ease.INOUTQUAD);
                // PRONE is FastMove's internal "held crawl" state. Let Minecraft render its native
                // crawling/swimming pose instead of layering the old custom prone animation over it.
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

    private static void onClientTick(ClientTickEvent.Post event) {
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
                // Negative Minecraft pitch angles look upward. This helps sell the idea that the
                // player's body is dropping into a slide instead of the camera merely becoming short.
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
            // A completed 360-degree turn is visually identical to zero degrees. Reset both values
            // together so interpolation cannot briefly animate the camera backwards on the next tick.
            diveRollCameraTicks = 0;
            diveRollProgress = 0.0F;
            previousDiveRollProgress = 0.0F;
        }
        wasDiveRolling = diveRolling;
    }

    /** Used by the Camera mixin because directly applying ViewportEvent rotation proved unreliable on 1.21.1. */
    public static float getCameraRoll(float partialTick) {
        return previousCameraRoll + (cameraRoll - previousCameraRoll) * partialTick;
    }

    public static float getCameraPitchOffset(float partialTick) {
        return previousCameraPitchOffset + (cameraPitchOffset - previousCameraPitchOffset) * partialTick;
    }

    public static float getDiveRollCameraPitch(float partialTick) {
        float progress = previousDiveRollProgress + (diveRollProgress - previousDiveRollProgress) * partialTick;
        // Smoothstep starts and ends gently while still completing one full forward rotation.
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

    public static void handleMoveStatePayload(final MoveStatePayload payload, final net.neoforged.neoforge.network.handling.IPayloadContext context) {
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
            var playable = PlayerAnimationRegistry.getAnimation(
                    ResourceLocation.fromNamespaceAndPath(FastMove.MOD_ID, entry.name)
            );
            if (playable instanceof KeyframeAnimation animation) {
                ANIMATIONS.put(entry.name, animation);
            }
        }
    }
}
