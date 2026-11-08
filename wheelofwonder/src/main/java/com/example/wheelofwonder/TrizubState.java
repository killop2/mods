package com.example.wheelofwonder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> caster, Trident: START (shipId = the ghost ship, targetId), CAPTURE (the target is a bound ragdoll now,
 * pos = its body), TRACK (its body every tick from then on), END (refused or over).
 */
public record TrizubState(int phase, int shipId, int targetId, double x, double y, double z) implements CustomPacketPayload {
    public static final int START = 0, CAPTURE = 1, TRACK = 2, END = 3;
    public static final Type<TrizubState> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "trizub_state"));
    public static final StreamCodec<ByteBuf, TrizubState> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(p.phase);
                b.writeVarInt(p.shipId);
                b.writeVarInt(p.targetId);
                b.writeDouble(p.x);
                b.writeDouble(p.y);
                b.writeDouble(p.z);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new TrizubState(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
