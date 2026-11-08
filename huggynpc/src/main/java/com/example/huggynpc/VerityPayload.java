package com.example.huggynpc;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client, Verity:
 *  CRUMBLE    - the Verity entityId fell apart (ragdoll or death): its cubes fly from pos with vel (m/s); it is hidden,
 *  RESTORE    - it stands again (revived): shown again,
 *  HIDE       - it lies crumbled already (a player starts seeing it): hidden, no cubes,
 *  MERGED     - entityId wears Verity as its head,
 *  HEAD_BURST - that head fell apart (the NPC went down): cubes from pos, the NPC's own head is back.
 * scale: the ball's size (1 = Verity's own), yaw: its facing (degrees).
 */
public record VerityPayload(int action, int entityId, double x, double y, double z, float vx, float vy, float vz, float scale, float yaw)
        implements CustomPacketPayload {
    public static final int CRUMBLE = 0, RESTORE = 1, HIDE = 2, MERGED = 3, HEAD_BURST = 4, SIZE = 5;
    public static final Type<VerityPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(HuggyNpcMod.MOD_ID, "verity"));
    public static final StreamCodec<ByteBuf, VerityPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(p.action);
                b.writeVarInt(p.entityId);
                b.writeDouble(p.x);
                b.writeDouble(p.y);
                b.writeDouble(p.z);
                b.writeFloat(p.vx);
                b.writeFloat(p.vy);
                b.writeFloat(p.vz);
                b.writeFloat(p.scale);
                b.writeFloat(p.yaw);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new VerityPayload(b.readVarInt(), b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble(),
                        b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
