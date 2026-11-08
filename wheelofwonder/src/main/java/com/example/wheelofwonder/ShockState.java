package com.example.wheelofwonder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> clients for Shockwave!. Seen by every player around (the visuals):
 * SLAM     - the spear hits the ground at pos; the rift starts along (dx, dz),
 * FRONT    - the rift's front is at pos now (every tick),
 * WAVE_END - the rift stopped at pos,
 * VORTEX   - NPCs are being pulled into pos,
 * BURST    - they are thrown out of pos.
 * Only to the caster (his camera): CATCH (entityId = the NPC the camera moves to, pos = vortex centre),
 * LAUNCH (that NPC is a ragdoll now), TRACK (its body every tick), END (refused or over).
 */
public record ShockState(int phase, int casterId, int entityId, double x, double y, double z, float dx, float dz) implements CustomPacketPayload {
    public static final int SLAM = 0, FRONT = 1, WAVE_END = 2, VORTEX = 3, BURST = 4, CATCH = 5, LAUNCH = 6, TRACK = 7, END = 8;
    public static final Type<ShockState> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "shock_state"));
    public static final StreamCodec<ByteBuf, ShockState> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(p.phase);
                b.writeVarInt(p.casterId);
                b.writeVarInt(p.entityId);
                b.writeDouble(p.x);
                b.writeDouble(p.y);
                b.writeDouble(p.z);
                b.writeFloat(p.dx);
                b.writeFloat(p.dz);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new ShockState(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readFloat());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
