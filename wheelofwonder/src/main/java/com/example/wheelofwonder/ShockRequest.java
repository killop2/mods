package com.example.wheelofwonder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server for Shockwave!: START = right click (wind-up and strike), DONE = the camera is back (Ctrl). */
public record ShockRequest(int action) implements CustomPacketPayload {
    public static final int START = 0, DONE = 1;
    public static final Type<ShockRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "shock_request"));
    public static final StreamCodec<ByteBuf, ShockRequest> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ShockRequest::action,
            ShockRequest::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
