package io.github.beeebea.fastmove.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.beeebea.fastmove.IFastPlayer;
import io.github.beeebea.fastmove.MoveState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void fastmove_disableViewBobbingWhileSliding(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null
                && ((IFastPlayer) client.player).fastmove_getMoveState() == MoveState.SLIDING) {
            ci.cancel();
        }
    }
}
