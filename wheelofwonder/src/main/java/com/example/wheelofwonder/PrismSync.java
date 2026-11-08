package com.example.wheelofwonder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> players watching a Prism Splitter, every tick while its crystals are loose: per crystal its position,
 * axis, beam strength, beam target and beam colour (PrismCoasterEntity.FLOATS floats each).
 */
public record PrismSync(int entityId, int state, float[] data) implements CustomPacketPayload {
    public static final Type<PrismSync> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "prism_sync"));
    public static final StreamCodec<ByteBuf, PrismSync> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(p.entityId);
                b.writeVarInt(p.state);
                b.writeVarInt(p.data.length);
                for (float f : p.data) b.writeFloat(f);
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                int id = b.readVarInt(), st = b.readVarInt(), n = Math.min(b.readVarInt(), 4096);
                float[] d = new float[n];
                for (int i = 0; i < n; i++) d[i] = b.readFloat();
                return new PrismSync(id, st, d);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
