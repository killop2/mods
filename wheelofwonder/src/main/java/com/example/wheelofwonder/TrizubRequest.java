package com.example.wheelofwonder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Player -> server, Trident: START = summon the ghost ship over this NPC; DONE = the camera is back (Ctrl). */
public record TrizubRequest(int action, int entityId) implements CustomPacketPayload {
    public static final int START = 0, DONE = 1;
    public static final Type<TrizubRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "trizub_request"));
    public static final StreamCodec<ByteBuf, TrizubRequest> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TrizubRequest::action,
            ByteBufCodecs.VAR_INT, TrizubRequest::entityId,
            TrizubRequest::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
