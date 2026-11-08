package com.example.wheelofwonder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> caster, Strange's Hammer: START (actId = the sorcerer's act, targetId), CAPTURE (the hammer struck,
 * pos = the body), TRACK (the flying body every tick from then on), END (refused or over).
 */
public record StrangeState(int phase, int actId, int targetId, double x, double y, double z) implements CustomPacketPayload {
    public static final int START = 0, CAPTURE = 1, TRACK = 2, END = 3;
    public static final Type<StrangeState> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "strange_state"));
    public static final StreamCodec<ByteBuf, StrangeState> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(p.phase);
                b.writeVarInt(p.actId);
                b.writeVarInt(p.targetId);
                b.writeDouble(p.x);
                b.writeDouble(p.y);
                b.writeDouble(p.z);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new StrangeState(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
