package com.example.wheelofwonder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Player -> server, Telekinesis Orb:
 * GRAB    - lift this NPC (entityId),
 * CONTROL - every tick while held: where it should be (x, y, z) and how it turns (sx, sy, sz rad/s),
 * THROW   - let go with this velocity (x, y, z m/s) and spin,
 * DROP    - let go, it just falls.
 */
public record TeleRequest(int action, int entityId, double x, double y, double z, float sx, float sy, float sz) implements CustomPacketPayload {
    public static final int GRAB = 0, CONTROL = 1, THROW = 2, DROP = 3;
    public static final Type<TeleRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "tele_request"));
    public static final StreamCodec<ByteBuf, TeleRequest> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(p.action);
                b.writeVarInt(p.entityId);
                b.writeDouble(p.x);
                b.writeDouble(p.y);
                b.writeDouble(p.z);
                b.writeFloat(p.sx);
                b.writeFloat(p.sy);
                b.writeFloat(p.sz);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new TeleRequest(b.readVarInt(), b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readFloat(), b.readFloat());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
