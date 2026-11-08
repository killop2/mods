package com.example.wheelofwonder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> players around, Telekinesis Orb:
 * GRABBED - the hand took this NPC (pos = the point it lifts it to, where the hand hangs above),
 * TRACK   - its body every tick (pos),
 * FLING   - it was thrown (pos = where it let go),
 * END     - refused, or it got away.
 */
public record TeleState(int phase, int entityId, double x, double y, double z) implements CustomPacketPayload {
    public static final int GRABBED = 0, END = 1, TRACK = 2, FLING = 3;
    public static final Type<TeleState> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "tele_state"));
    public static final StreamCodec<ByteBuf, TeleState> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(p.phase);
                b.writeVarInt(p.entityId);
                b.writeDouble(p.x);
                b.writeDouble(p.y);
                b.writeDouble(p.z);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new TeleState(b.readVarInt(), b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
