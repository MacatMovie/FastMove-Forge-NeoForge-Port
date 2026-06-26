package io.github.beeebea.fastmove.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

public record MoveStatePayload(UUID uuid, int moveStateInt) {
    public static void encode(MoveStatePayload payload, FriendlyByteBuf buffer) {
        buffer.writeUUID(payload.uuid());
        buffer.writeVarInt(payload.moveStateInt());
    }

    public static MoveStatePayload decode(FriendlyByteBuf buffer) {
        return new MoveStatePayload(buffer.readUUID(), buffer.readVarInt());
    }
}
