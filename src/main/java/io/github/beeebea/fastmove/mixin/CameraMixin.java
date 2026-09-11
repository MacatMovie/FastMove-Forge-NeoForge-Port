package io.github.beeebea.fastmove.mixin;

import io.github.beeebea.fastmove.client.FastMoveClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies FastMove's client-only first-person camera effects directly to the camera rotation. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setRotation(float yRot, float xRot, float roll);

    @Inject(method = "setup", at = @At("TAIL"))
    private void fastmove_applyCameraEffects(
            BlockGetter level,
            Entity entity,
            boolean detached,
            boolean thirdPersonReverse,
            float partialTick,
            CallbackInfo ci
    ) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null
                || entity != client.player
                || !client.options.getCameraType().isFirstPerson()) {
            return;
        }

        Camera camera = (Camera) (Object) this;
        float roll = FastMoveClient.getCameraRoll(partialTick);
        float pitchOffset = FastMoveClient.getCameraPitchOffset(partialTick);
        float diveRollPitch = FastMoveClient.getDiveRollCameraPitch(partialTick);
        if (Math.abs(roll) < 0.001F
                && Math.abs(pitchOffset) < 0.001F
                && Math.abs(diveRollPitch) < 0.001F) {
            return;
        }

        // Camera#setRotation rebuilds its quaternion/up/left vectors, allowing full 3-axis effects.
        // The dive roll deliberately uses a complete 360-degree pitch rotation, so it returns to the
        // exact original orientation when the animation ends rather than leaving the view tilted.
        setRotation(
                camera.getYRot(),
                camera.getXRot() + pitchOffset + diveRollPitch,
                camera.getRoll() + roll
        );
    }
}
