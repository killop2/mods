package com.example.wheelofwonder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> thrower:
 * SPIN   - accepted; the daggers circle the thrower for {@code ticks} ticks (pos = NPC centre),
 * THROW  - the daggers fly now and reach the NPC in {@code ticks} ticks (pos = NPC centre),
 * HIT    - they are in the NPC (pos = NPC centre); a short hit-stop follows,
 * LAUNCH - the NPC flies off as a ragdoll (pos = body centre),
 * TRACK  - body position every tick after the launch,
 * END    - refused or aborted.
 */
public record DaggerState(int phase, int entityId, double x, double y, double z, int ticks) implements CustomPacketPayload {
    public static final int SPIN = 0, THROW = 1, HIT = 2, LAUNCH = 3, TRACK = 4, END = 5;
    public static final Type<DaggerState> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "dagger_state"));
    public static final StreamCodec<ByteBuf, DaggerState> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(p.phase);
                b.writeVarInt(p.entityId);
                b.writeDouble(p.x);
                b.writeDouble(p.y);
                b.writeDouble(p.z);
                b.writeVarInt(p.ticks);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new DaggerState(b.readVarInt(), b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble(), b.readVarInt());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
