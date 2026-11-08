package com.example.wheelofwonder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Player -> server, Strange's Hammer: START = summon the sorcerer over this NPC (side: +1/-1, the side the camera
 * looks from); DONE = the camera is back (Ctrl).
 */
public record StrangeRequest(int action, int entityId, int side) implements CustomPacketPayload {
    public static final int START = 0, DONE = 1;
    public static final Type<StrangeRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "strange_request"));
    public static final StreamCodec<ByteBuf, StrangeRequest> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, StrangeRequest::action,
            ByteBufCodecs.VAR_INT, StrangeRequest::entityId,
            ByteBufCodecs.VAR_INT, StrangeRequest::side,
            StrangeRequest::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
