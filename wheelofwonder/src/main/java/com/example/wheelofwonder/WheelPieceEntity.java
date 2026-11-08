package com.example.wheelofwonder;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** A flying piece of the exploded wheel: rim segment, light bulb or face shard. Bounces, then vanishes in a puff. */
public class WheelPieceEntity extends Entity {
    public static final int RIM = 0, BULB = 1, SHARD = 2;
    /** Pieces of the burst rocket: a hull plate (navy or red by colour parity), a fin, the black nose cone. */
    public static final int HULL = 3, FIN = 4, NOSE = 5;
    /** A chunk of the burst Chaos Meteor (colour 0 = charred rock, 1 = still glowing). */
    public static final int ROCK = 6;

    private static final EntityDataAccessor<Integer> DATA_KIND = SynchedEntityData.defineId(WheelPieceEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_COLOR = SynchedEntityData.defineId(WheelPieceEntity.class, EntityDataSerializers.INT);

    private int life = 80;
    private boolean bounced;

    public WheelPieceEntity(EntityType<? extends WheelPieceEntity> type, Level level) {
        super(type, level);
    }

    void setup(int kind, int color, Vec3 pos, Vec3 vel) {
        this.entityData.set(DATA_KIND, kind);
        this.entityData.set(DATA_COLOR, color);
        setPos(pos.x, pos.y, pos.z);
        setDeltaMovement(vel);
        this.life = 70 + this.random.nextInt(50);
    }

    public int kind() {
        return this.entityData.get(DATA_KIND);
    }

    public int colorIndex() {
        return this.entityData.get(DATA_COLOR);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(DATA_KIND, RIM);
        b.define(DATA_COLOR, 0);
    }

    @Override
    public void tick() {
        super.tick();
        Vec3 v = getDeltaMovement().add(0.0D, -0.05D, 0.0D).scale(0.985D);
        setDeltaMovement(v);
        move(MoverType.SELF, v);
        if (onGround()) {
            Vec3 n = getDeltaMovement();
            if (Math.abs(v.y) > 0.12D) {
                setDeltaMovement(n.x * 0.65D, -v.y * 0.42D, n.z * 0.65D);
                if (!this.bounced && !level().isClientSide()) {
                    this.bounced = true;
                    level().playSound(null, getX(), getY(), getZ(), kind() == BULB ? SoundEvents.GLASS_BREAK : SoundEvents.CHAIN_FALL,
                            SoundSource.PLAYERS, 0.5F, 1.2F + this.random.nextFloat() * 0.4F);
                }
            } else {
                setDeltaMovement(n.x * 0.7D, 0.0D, n.z * 0.7D);
            }
        }
        if (level().isClientSide()) {
            if (kind() == ROCK) {
                // smoking, glowing meteor chunks
                if (this.tickCount < 50 && this.tickCount % 2 == 0) {
                    level().addParticle(ParticleTypes.SMOKE, getX(), getY() + 0.15D, getZ(), 0.0D, 0.02D, 0.0D);
                    if (colorIndex() == 1) level().addParticle(ParticleTypes.SMALL_FLAME, getX(), getY() + 0.15D, getZ(), 0.0D, 0.01D, 0.0D);
                }
            } else if (this.tickCount < 30 && this.tickCount % 2 == 0) {
                int col = WheelEntity.BULB_COLORS[colorIndex() % WheelEntity.BULB_COLORS.length];
                level().addParticle(kind() == BULB ? WheelEntity.dust(col, 0.9F) : ParticleTypes.SMOKE, getX(), getY() + 0.1D, getZ(), 0.0D, 0.0D, 0.0D);
            }
            return;
        }
        if (--this.life <= 0) {
            ((ServerLevel) level()).sendParticles(ParticleTypes.POOF, getX(), getY() + 0.15D, getZ(), 4, 0.1D, 0.1D, 0.1D, 0.02D);
            discard();
        }
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
