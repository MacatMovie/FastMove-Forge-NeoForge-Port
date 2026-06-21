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
import io.github.beeebea.fastmove.network.MoveStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;

public final class FastMoveClient {
    private static final Map<String, KeyframeAnimation> ANIMATIONS = new HashMap<>();

    private FastMoveClient() {
    }

    public static void init(IEventBus modEventBus) {
        FastMove.LOGGER.info("initializing FastMove Client :3");

        FastMoveInput input = new FastMoveInput();
        FastMove.INPUT = input;

        modEventBus.addListener(FastMoveInput::registerKeyMappings);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(FastMoveClient::onClientTick);

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

                var fade = AbstractFadeModifier.standardFadeIn(10, Ease.INOUTQUAD);
                var anim = ANIMATIONS.get(moveState.name);
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

    private static void onClientTick(ClientTickEvent.Post event) {
        if (FastMove.INPUT instanceof FastMoveInput input) input.onEndTick();
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
