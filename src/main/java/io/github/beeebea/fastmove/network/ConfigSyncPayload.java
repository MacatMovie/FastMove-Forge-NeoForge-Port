package io.github.beeebea.fastmove.network;

import io.github.beeebea.fastmove.FastMove;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ConfigSyncPayload(String configJson) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ConfigSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FastMove.MOD_ID, "config_sync"));

    public static final StreamCodec<ByteBuf, ConfigSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ConfigSyncPayload::configJson,
            ConfigSyncPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
