package com.example.wheelofwonder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> clients, Whirlwind Whip (Sinister Chain). Seen by every player around:
 * START   - the caster starts whirling these NPCs (ids, in their order round the ring),
 * RELEASE - he lets go: the chains break, the NPCs fly off (pos = the caster).
 * Only to the caster (his camera): LAUNCH (entityId = the NPC the camera follows), TRACK (its body every tick),
 * END (refused or over).
 */
public record WhirlState(int phase, int casterId, int entityId, double x, double y, double z, int[] ids) implements CustomPacketPayload {
    public static final int START = 0, RELEASE = 1, LAUNCH = 2, TRACK = 3, END = 4;
    public static final Type<WhirlState> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "whirl_state"));
    public static final StreamCodec<ByteBuf, WhirlState> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(p.phase);
                b.writeVarInt(p.casterId);
                b.writeVarInt(p.entityId);
                b.writeDouble(p.x);
                b.writeDouble(p.y);
                b.writeDouble(p.z);
                b.writeVarIntArray(p.ids);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new WhirlState(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble(), b.readVarIntArray(64));
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
