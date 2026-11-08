package com.example.wheelofwonder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Thrower -> server: START (throw at this NPC), CANCEL (Shift during the wind-up), DONE (Ctrl: the camera is back). */
public record DaggerRequest(int action, int entityId) implements CustomPacketPayload {
    public static final int START = 0, CANCEL = 1, DONE = 2;
    public static final Type<DaggerRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "dagger_request"));
    public static final StreamCodec<ByteBuf, DaggerRequest> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DaggerRequest::action,
            ByteBufCodecs.VAR_INT, DaggerRequest::entityId,
            DaggerRequest::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
