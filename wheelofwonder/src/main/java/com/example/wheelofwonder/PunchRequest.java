package com.example.wheelofwonder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: START = the uppercut on entityId, STRIKE = the punch at the top of the leap. */
public record PunchRequest(int action, int entityId) implements CustomPacketPayload {
    public static final int START = 0, STRIKE = 1, CANCEL = 2, RELEASE = 3, DONE = 4;
    public static final Type<PunchRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "punch_request"));
    public static final StreamCodec<ByteBuf, PunchRequest> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PunchRequest::action,
            ByteBufCodecs.VAR_INT, PunchRequest::entityId,
            PunchRequest::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
