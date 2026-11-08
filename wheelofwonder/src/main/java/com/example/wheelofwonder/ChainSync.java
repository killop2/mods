package com.example.wheelofwonder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> clients, every tick while chains exist: every chain as its members in order (leader first), each with
 * its entity id, where the chain is fastened (0 hand, 1 leg), whether it lies as a ragdoll and where its body is.
 * The rope length is sent too, so the client draws the right sag.
 */
public record ChainSync(float length, List<List<Member>> chains) implements CustomPacketPayload {
    public record Member(int id, int part, boolean ragdoll, double x, double y, double z) {
    }

    public static final Type<ChainSync> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "chain_sync"));
    public static final StreamCodec<ByteBuf, ChainSync> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeFloat(p.length);
                b.writeVarInt(p.chains.size());
                for (List<Member> c : p.chains) {
                    b.writeVarInt(c.size());
                    for (Member m : c) {
                        b.writeVarInt(m.id);
                        b.writeByte(m.part | (m.ragdoll ? 2 : 0));
                        b.writeFloat((float) m.x);
                        b.writeFloat((float) m.y);
                        b.writeFloat((float) m.z);
                    }
                }
            },
            buf -> {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                float len = b.readFloat();
                int n = b.readVarInt();
                List<List<Member>> chains = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    int k = b.readVarInt();
                    List<Member> c = new ArrayList<>(k);
                    for (int j = 0; j < k; j++) {
                        int id = b.readVarInt();
                        int flags = b.readByte();
                        c.add(new Member(id, flags & 1, (flags & 2) != 0, b.readFloat(), b.readFloat(), b.readFloat()));
                    }
                    chains.add(c);
                }
                return new ChainSync(len, chains);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
