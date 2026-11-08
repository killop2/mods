package com.example.wheelofwonder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> the player who loaded the ballista: SEATED = the NPC is on its way into the bucket (camera starts),
 * LAUNCH = it was shot, TRACK = where its ragdoll is now (every tick), LANDED = it hit the ground.
 */
public record BallistaState(int phase, int entityId, double x, double y, double z) implements CustomPacketPayload {
    /** REJECTED = no free ballista near the NPC: the camera, already on the NPC, goes back to the player. */
    public static final int SEATED = 0, LAUNCH = 1, TRACK = 2, LANDED = 3, REJECTED = 4;
    /** Chaos Meteor: FOLLOW = start the free camera on this entity (the meteor), RETARGET = move it to this NPC (hit). */
    public static final int FOLLOW = 5, RETARGET = 6;
    public static final Type<BallistaState> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(WheelMod.MOD_ID, "ballista_state"));
    public static final StreamCodec<ByteBuf, BallistaState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BallistaState::phase,
            ByteBufCodecs.VAR_INT, BallistaState::entityId,
            ByteBufCodecs.DOUBLE, BallistaState::x,
            ByteBufCodecs.DOUBLE, BallistaState::y,
            ByteBufCodecs.DOUBLE, BallistaState::z,
            BallistaState::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
