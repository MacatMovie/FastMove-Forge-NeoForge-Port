package io.github.beeebea.fastmove.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerGamePacketListenerImpl.class)
public class ServerPlayHandlerMixin {
    @ModifyVariable(at = @At("STORE"), method = "handleMovePlayer", ordinal = 2)
    private boolean modifyCheatDetector(boolean var) {
        return false;
    }
}
