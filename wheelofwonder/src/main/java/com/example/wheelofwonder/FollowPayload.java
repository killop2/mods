package com.example.wheelofwonder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> wheel owner: where the chosen thrown ragdoll body is now, for the follow camera after the explosion. */
public record FollowPayload(int entityId, double x, double y, double z) implements CustomPacketPayload {
    public static final Type<FollowPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "follow"));
    public static final StreamCodec<ByteBuf, FollowPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FollowPayload::entityId,
            ByteBufCodecs.DOUBLE, FollowPayload::x,
            ByteBufCodecs.DOUBLE, FollowPayload::y,
            ByteBufCodecs.DOUBLE, FollowPayload::z,
            FollowPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
