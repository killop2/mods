package com.example.huggynpc;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: "I hit this NPC". Player hits on NPCs go this way instead of the vanilla attack packet, which the
 * server drops when the NPC's server-side hitbox does not match what the player sees (Sable shrinks the box of mobs
 * it thinks are ragdolls, Custom NPCs changes it with its animations).
 */
public record NpcHitPayload(int entityId) implements CustomPacketPayload {
    public static final Type<NpcHitPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(HuggyNpcMod.MOD_ID, "hit"));
    public static final StreamCodec<ByteBuf, NpcHitPayload> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(NpcHitPayload::new, NpcHitPayload::entityId);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
