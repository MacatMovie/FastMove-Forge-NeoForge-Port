package io.github.beeebea.fastmove.network;

import net.minecraft.network.FriendlyByteBuf;

public record ConfigSyncPayload(String configJson) {
    public static void encode(ConfigSyncPayload payload, FriendlyByteBuf buffer) {
        buffer.writeUtf(payload.configJson());
    }

    public static ConfigSyncPayload decode(FriendlyByteBuf buffer) {
        return new ConfigSyncPayload(buffer.readUtf(32767));
    }
}
