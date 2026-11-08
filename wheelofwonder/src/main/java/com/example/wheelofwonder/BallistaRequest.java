package com.example.wheelofwonder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: LOAD = put NPC entityId into the nearest ballista, DONE = the camera is back on the player. */
public record BallistaRequest(int action, int entityId) implements CustomPacketPayload {
    /** METEOR = cast a Chaos Meteor at the block the player looks at. */
    public static final int LOAD = 0, DONE = 1, METEOR = 2;
    public static final Type<BallistaRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "ballista_request"));
    public static final StreamCodec<ByteBuf, BallistaRequest> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BallistaRequest::action,
            ByteBufCodecs.VAR_INT, BallistaRequest::entityId,
            BallistaRequest::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
