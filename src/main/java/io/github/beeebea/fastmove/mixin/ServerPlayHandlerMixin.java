package io.github.beeebea.fastmove.mixin;

import io.github.beeebea.fastmove.FastMove;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(ServerGamePacketListenerImpl.class)
public class ServerPlayHandlerMixin {
    @Shadow public ServerPlayer player;

    @ModifyConstant(method = "handleMovePlayer", constant = @Constant(doubleValue = 100.0D), require = 0)
    private double fastmove_adjustGroundMovedTooQuicklyThreshold(double vanillaThreshold) {
        return FastMove.adjustMovedTooQuicklyThreshold(player, vanillaThreshold);
    }

    @ModifyConstant(method = "handleMovePlayer", constant = @Constant(doubleValue = 300.0D), require = 0)
    private double fastmove_adjustElytraMovedTooQuicklyThreshold(double vanillaThreshold) {
        return FastMove.adjustMovedTooQuicklyThreshold(player, vanillaThreshold);
    }
}
