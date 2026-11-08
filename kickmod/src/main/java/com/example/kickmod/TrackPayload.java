package com.example.kickmod;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> kicking player: where the kicked body (ragdoll root) is now, for the follow camera. */
public record TrackPayload(int entityId, double x, double y, double z) implements CustomPacketPayload {
    public static final Type<TrackPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(KickMod.MOD_ID, "track"));
    public static final StreamCodec<ByteBuf, TrackPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TrackPayload::entityId,
            ByteBufCodecs.DOUBLE, TrackPayload::x,
            ByteBufCodecs.DOUBLE, TrackPayload::y,
            ByteBufCodecs.DOUBLE, TrackPayload::z,
            TrackPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
