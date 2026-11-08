package com.example.wheelofwonder;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Dark Carnival ballista (a torsion catapult on wheels). It stays in the world until a player hits it.
 * IDLE    - arm cocked back, waiting,
 * LOADING - an NPC hops in an arc into the bucket,
 * LOADED  - it sits there for a moment while the winch creaks and the bells count down,
 * FIRING  - the arm swings forward against the padded crossbar; at RELEASE_AGE the NPC is let go as a ragdoll,
 * RELOAD  - the arm is winched back.
 * Entity origin = ground centre; local +Z = the shooting direction (the entity's yaw).
 * The arm angle is a pure function of (state, ticks in state), so the client draws it without extra packets.
 */
public class BallistaEntity extends Entity implements NpcLauncher {
    public static final int IDLE = 0, LOADING = 1, LOADED = 2, FIRING = 3, RELOAD = 4;

    // geometry shared with the renderer (blocks, radians)
    public static final float PIVOT_Y = 0.62F, PIVOT_Z = -0.05F, ARM_LEN = 1.75F;
    public static final float COCKED = (float) Math.toRadians(-75.0D), FIRED = (float) Math.toRadians(18.0D);
    /** Seat in arm space: on the bucket floor near the arm's end. */
    public static final float SEAT_Y = ARM_LEN - 0.25F, SEAT_Z = 0.14F;
    /** Rider feet below the bucket floor, so a sitting body has its hips in the bucket. */
    private static final double SIT_DROP = 0.55D;

    public static final int SWING_TICKS = 6;
    private static final int RELEASE_AGE = 5;
    private static final int FIRING_TICKS = 24;
    public static final int RELOAD_TICKS = 40;

    private static final EntityDataAccessor<Integer> DATA_STATE = SynchedEntityData.defineId(BallistaEntity.class, EntityDataSerializers.INT);

    // ---- server ----
    private int stateAge;
    private LivingEntity rider;
    private boolean riderNoGravity;
    private Vec3 jumpFrom;
    private int jumpTicks = 14;
    private UUID shooter;

    // ---- client ----
    private int stateC = -1, ageC;
    private float winch, prevWinch;

    public BallistaEntity(EntityType<? extends BallistaEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(DATA_STATE, IDLE);
    }

    public int state() {
        return this.entityData.get(DATA_STATE);
    }

    private void setState(int s) {
        this.entityData.set(DATA_STATE, s);
        this.stateAge = 0;
    }

    @Override
    public boolean isIdle() {
        return state() == IDLE && !isRemoved();
    }

    @Override
    public double loadRadius() {
        return WheelConfig.BALLISTA_RADIUS.get();
    }

    /** Ticks spent in the current state (client: since the state arrived). */
    public int age() {
        return level().isClientSide() ? this.ageC : this.stateAge;
    }

    // ---------------------------------------------------------------- geometry

    /** Arm angle (radians from straight up, positive = towards the front). */
    public float armAngle(float partial) {
        float a = age() + partial;
        return switch (level().isClientSide() && this.stateC >= 0 ? this.stateC : state()) {
            case FIRING -> {
                if (a < SWING_TICKS) {
                    float t = a / SWING_TICKS;
                    yield COCKED + (FIRED - COCKED) * t * t;
                }
                // slams into the crossbar and wobbles out
                float k = a - SWING_TICKS;
                yield FIRED - 0.12F * Mth.sin(k * 1.6F) * (float) Math.exp(-k * 0.35F);
            }
            case RELOAD -> {
                float t = Mth.clamp(a / RELOAD_TICKS, 0.0F, 1.0F);
                yield FIRED + (COCKED - FIRED) * (t * t * (3.0F - 2.0F * t));
            }
            // the loaded arm trembles more and more under the tension
            case LOADED -> COCKED - 0.03F * Math.min(1.0F, a / 40.0F) * Mth.sin(a * 2.5F);
            default -> COCKED;
        };
    }

    /** Point in arm space -> entity space (rotation about X around the pivot). */
    public static Vec3 armToLocal(float angle, double x, double y, double z) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return new Vec3(x, PIVOT_Y + y * c - z * s, PIVOT_Z + y * s + z * c);
    }

    public Vec3 forward() {
        double yaw = Math.toRadians(getYRot());
        return new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
    }

    /** Entity space -> world. */
    public Vec3 toWorld(Vec3 local) {
        double yaw = Math.toRadians(getYRot());
        double cs = Math.cos(yaw), sn = Math.sin(yaw);
        // right = (cos, 0, sin), forward = (-sin, 0, cos)
        return position().add(local.x * cs - local.z * sn, local.y, local.x * sn + local.z * cs);
    }

    public Vec3 seat(float angle) {
        return toWorld(armToLocal(angle, 0.0D, SEAT_Y, SEAT_Z));
    }

    // ---------------------------------------------------------------- loading

    /** Server: the NPC hops into the bucket; the shot follows by itself. */
    @Override
    public void load(ServerPlayer player, LivingEntity mob) {
        if (mob.isPassenger()) mob.stopRiding();
        this.rider = mob;
        this.riderNoGravity = mob.isNoGravity();
        this.shooter = player.getUUID();
        this.jumpFrom = mob.position();
        double dist = this.jumpFrom.distanceTo(seat(COCKED));
        this.jumpTicks = Mth.clamp((int) Math.round(10 + dist * 1.3D), 10, 26);
        mob.setNoGravity(true);
        WheelMod.hold(mob);
        if (mob instanceof Mob m) m.getNavigation().stop();
        setState(LOADING);
        Vec3 p = mob.position();
        level().playSound(null, p.x, p.y, p.z, SoundEvents.EVOKER_CAST_SPELL, SoundSource.NEUTRAL, 1.0F, 1.5F);
        level().playSound(null, p.x, p.y, p.z, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.NEUTRAL, 1.0F, 0.8F);
    }

    private boolean riderValid() {
        LivingEntity m = this.rider;
        return m != null && m.isAlive() && !m.isRemoved() && m.level() == level() && !WheelRagdolls.isRagdolled(m);
    }

    private void dropRider() {
        LivingEntity m = this.rider;
        this.rider = null;
        if (m == null) return;
        if (m.getVehicle() == this) m.stopRiding();
        if (!m.isRemoved()) {
            m.setNoGravity(this.riderNoGravity);
            WheelMod.unhold(m);
        }
    }

    private void abort() {
        dropRider();
        setState(IDLE);
    }

    // ---------------------------------------------------------------- tick

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            clientTick();
            return;
        }
        physics();
        this.stateAge++;
        ServerLevel level = (ServerLevel) level();
        switch (state()) {
            case IDLE -> {
                if (isVehicle()) ejectPassengers();
            }
            case LOADING -> loadingTick(level);
            case LOADED -> loadedTick(level);
            case FIRING -> {
                if (this.stateAge == RELEASE_AGE) release(level);
                if (this.stateAge >= FIRING_TICKS) setState(RELOAD);
            }
            case RELOAD -> {
                if (this.stateAge % 5 == 0) playAt(level, SoundEvents.WOODEN_BUTTON_CLICK_ON, 0.5F, 0.6F);
                if (this.stateAge >= RELOAD_TICKS) setState(IDLE);
            }
            default -> setState(IDLE);
        }
    }

    /** Stands on the ground; falls if the ground disappears. */
    private void physics() {
        Vec3 dm = getDeltaMovement();
        if (onGround() && dm.lengthSqr() < 1.0E-6D) return;
        dm = dm.add(0.0D, -0.04D, 0.0D);
        move(MoverType.SELF, dm);
        setDeltaMovement(onGround() ? Vec3.ZERO : dm.scale(0.98D));
    }

    private void loadingTick(ServerLevel level) {
        if (!riderValid() || this.jumpFrom == null) {
            abort();
            return;
        }
        LivingEntity m = this.rider;
        float t = Math.min(1.0F, this.stateAge / (float) this.jumpTicks);
        double e = t * t * (3.0D - 2.0D * t);
        Vec3 to = seat(COCKED).subtract(0.0D, SIT_DROP, 0.0D);
        double hop = Math.sin(Math.PI * t) * (1.4D + this.jumpFrom.distanceTo(to) * 0.15D);
        Vec3 p = this.jumpFrom.lerp(to, e).add(0.0D, hop, 0.0D);
        m.setPos(p.x, p.y, p.z);
        m.setDeltaMovement(Vec3.ZERO);
        m.fallDistance = 0.0F;
        if (m instanceof Mob mob) mob.getNavigation().stop();
        Vec3 c = p.add(0.0D, m.getBbHeight() * 0.5D, 0.0D);
        level.sendParticles(ParticleTypes.WITCH, c.x, c.y, c.z, 2, 0.25D, 0.3D, 0.25D, 0.0D);
        level.sendParticles(WheelEntity.dust(this.stateAge % 2 == 0 ? 0xC84BFF : 0xFF8A1F, 1.1F), c.x, c.y, c.z, 2, 0.3D, 0.3D, 0.3D, 0.0D);
        if (t >= 1.0F) {
            m.startRiding(this, true);
            setState(LOADED);
            Vec3 s = seat(COCKED);
            level.playSound(null, s.x, s.y, s.z, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.NEUTRAL, 0.5F, 1.3F);
            level.playSound(null, s.x, s.y, s.z, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.NEUTRAL, 1.0F, 0.6F);
            level.sendParticles(ParticleTypes.POOF, s.x, s.y, s.z, 8, 0.3D, 0.1D, 0.3D, 0.02D);
        }
    }

    private void loadedTick(ServerLevel level) {
        if (!riderValid()) {
            abort();
            return;
        }
        LivingEntity m = this.rider;
        if (m.getVehicle() != this) {
            // a mod refused the mount: hold the NPC in the bucket by hand
            Vec3 s = seat(armAngle(0.0F)).subtract(0.0D, SIT_DROP, 0.0D);
            m.setPos(s.x, s.y, s.z);
            m.setDeltaMovement(Vec3.ZERO);
        }
        if (m instanceof Mob mob) mob.getNavigation().stop();
        int delay = Math.max(4, (int) Math.round(WheelConfig.BALLISTA_DELAY.get() * 20.0D));
        // creaking winch and a rising carnival countdown
        if (this.stateAge % 4 == 0) playAt(level, SoundEvents.WOODEN_BUTTON_CLICK_ON, 0.6F, 0.5F + 0.6F * this.stateAge / delay);
        if (this.stateAge % 10 == 0) playAt(level, SoundEvents.NOTE_BLOCK_BELL.value(), 0.9F, 0.7F + 0.8F * this.stateAge / delay);
        if (this.stateAge >= delay) {
            setState(FIRING);
            playAt(level, SoundEvents.CROSSBOW_SHOOT, 1.6F, 0.5F);
        }
    }

    /** The arm is up: the NPC flies off as a ragdoll. */
    private void release(ServerLevel level) {
        if (!riderValid()) {
            dropRider();
            return;
        }
        LivingEntity m = this.rider;
        float angle = armAngle(0.0F);
        Vec3 from = seat(angle).add(0.0D, 0.25D, 0.0D);
        m.stopRiding();
        m.setNoGravity(this.riderNoGravity);
        m.setPos(from.x, from.y - m.getBbHeight() * 0.5D, from.z);
        this.rider = null;

        double a = Math.toRadians(WheelConfig.BALLISTA_ANGLE.get());
        double speed = WheelConfig.BALLISTA_SPEED.get();
        Vec3 fwd = forward();
        Vec3 vel = fwd.scale(Math.cos(a) * speed).add(0.0D, Math.sin(a) * speed, 0.0D);
        // one slow head-over-heels tumble forward (the whole body as one piece, no shaking)
        Vec3 spin = new Vec3(0.0D, 1.0D, 0.0D).cross(fwd).normalize().scale(-2.2D);
        ServerPlayer player = this.shooter != null ? level.getServer().getPlayerList().getPlayer(this.shooter) : null;
        BallistaServer.launch(level, player, m, vel, spin);

        RandomSource r = this.random;
        level.playSound(null, from.x, from.y, from.z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.NEUTRAL, 1.6F, 0.7F);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.NEUTRAL, 0.9F, 0.6F);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.NEUTRAL, 1.2F, 1.5F);
        level.sendParticles(ParticleTypes.FLASH, from.x, from.y, from.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        for (int i = 0; i < 40; i++) {
            Vec3 v = vel.normalize().add(r.nextGaussian() * 0.45D, r.nextGaussian() * 0.45D, r.nextGaussian() * 0.45D).scale(0.4D + r.nextDouble() * 0.5D);
            level.sendParticles(WheelEntity.dust(WheelEntity.BULB_COLORS[i % WheelEntity.BULB_COLORS.length], 1.3F), from.x, from.y, from.z, 0, v.x, v.y, v.z, 1.0D);
        }
        for (int i = 0; i < 18; i++) {
            Vec3 v = new Vec3(r.nextGaussian(), r.nextGaussian() + 0.8D, r.nextGaussian()).normalize().scale(0.25D + r.nextDouble() * 0.3D);
            level.sendParticles(i % 2 == 0 ? ParticleTypes.FIREWORK : ParticleTypes.SOUL_FIRE_FLAME, from.x, from.y, from.z, 0, v.x, v.y, v.z, 1.0D);
        }
        Vec3 bar = toWorld(new Vec3(0.0D, 1.5D, 0.4D));
        level.sendParticles(ParticleTypes.POOF, bar.x, bar.y, bar.z, 10, 0.5D, 0.1D, 0.1D, 0.03D);
    }

    private void playAt(ServerLevel level, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        level.playSound(null, getX(), getY() + 1.0D, getZ(), sound, SoundSource.NEUTRAL, volume, pitch);
    }

    // ---------------------------------------------------------------- rider

    @Override
    protected void positionRider(Entity passenger, MoveFunction move) {
        if (!hasPassenger(passenger)) return;
        Vec3 s = seat(armAngle(0.0F));
        move.accept(passenger, s.x, s.y - SIT_DROP, s.z);
        float yaw = getYRot();
        passenger.setYRot(yaw);
        if (passenger instanceof LivingEntity le) {
            le.yBodyRot = yaw;
            le.setYHeadRot(yaw);
        }
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty() && !(passenger instanceof Player);
    }

    // ---------------------------------------------------------------- breaking

    /** A player's hit removes the ballista; nothing else hurts it. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide() || isRemoved()) return false;
        if (!(source.getEntity() instanceof Player)) return false;
        ServerLevel level = (ServerLevel) level();
        dropRider();
        ejectPassengers();
        Vec3 c = toWorld(new Vec3(0.0D, 0.9D, -0.25D));
        level.playSound(null, c.x, c.y, c.z, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.NEUTRAL, 0.8F, 1.2F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.NEUTRAL, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.POOF, c.x, c.y, c.z, 20, 0.8D, 0.5D, 0.8D, 0.04D);
        level.sendParticles(ParticleTypes.WITCH, c.x, c.y, c.z, 20, 0.8D, 0.6D, 0.8D, 0.0D);
        for (int i = 0; i < 24; i++) {
            level.sendParticles(WheelEntity.dust(WheelEntity.BULB_COLORS[i % WheelEntity.BULB_COLORS.length], 1.2F), c.x, c.y, c.z, 1, 0.9D, 0.6D, 0.9D, 0.0D);
        }
        discard();
        return true;
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide()) dropRider();
        super.remove(reason);
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double d) {
        return d < 128.0D * 128.0D;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    // ---------------------------------------------------------------- client

    public float winch(float partial) {
        return Mth.lerp(partial, this.prevWinch, this.winch);
    }

    /** Client: ticks since the state arrived, and the state as seen here. */
    private void clientTick() {
        int s = state();
        if (s != this.stateC) {
            if (this.stateC >= 0 && s == FIRING) BallistaFx.fire(this);
            this.stateC = s;
            this.ageC = 0;
        } else {
            this.ageC++;
        }
        this.prevWinch = this.winch;
        if (s == LOADED || s == LOADING) this.winch += 0.3F;
        else if (s == RELOAD) this.winch -= 0.22F;
        BallistaFx.ambient(this);
    }
}
