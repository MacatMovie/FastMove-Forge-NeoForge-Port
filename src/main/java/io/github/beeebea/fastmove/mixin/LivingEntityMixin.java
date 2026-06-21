package io.github.beeebea.fastmove.mixin;

import io.github.beeebea.fastmove.IFastPlayer;
import io.github.beeebea.fastmove.MoveState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public class LivingEntityMixin {
    @Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true)
    public void fastmove_isClimbing(CallbackInfoReturnable<Boolean> cir) {
        if (((LivingEntity) (Object) this) instanceof Player) {
            IFastPlayer fastPlayer = (IFastPlayer) (Object) this;
            MoveState moveState = fastPlayer.fastmove_getMoveState();
            if (moveState == MoveState.WALLRUNNING_LEFT || moveState == MoveState.WALLRUNNING_RIGHT) {
                cir.setReturnValue(false);
                cir.cancel();
            }
        }
    }
}
