package com.example.wheelofwonder;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
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
 * Dark Carnival rocket ride: a tower with four arms carrying rocket cars. It stays until a player hits it.
 * IDLE    - waiting, arms low,
 * LOADING - an NPC hops into rocket car 0,
 * LOADED  - sits still for spinDelay, then the ride spins faster and faster (the arms lift); at launchTime
 *           car 0 breaks off and flies away with the NPC (RocketEntity),
 * RELOAD  - the ride spins down and a new rocket appears on the empty arm.
 * The spin angle is a pure function of (state, ticks in state, synced start angle), so the client draws it
 * smoothly without a packet per tick.
 */
public class RocketRideEntity extends Entity implements NpcLauncher {
    public static final int IDLE = 0, LOADING = 1, LOADED = 2, RELOAD = 3;
    public static final float ARM_Y = 1.72F, ARM_LEN = 3.2F, HUB_R = 0.62F, CAR_UP = 0.22F, SEAT_UP = 0.5F;
    public static final int CARS = 4;
    /** Top spin, radians per tick (~1 turn per second). */
    public static final float OMEGA = 0.3F;
    public static final int SPIN_DOWN = 80, REGROW = 60;

    private static final EntityDataAccessor<Integer> DATA_STATE = SynchedEntityData.defineId(RocketRideEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_BASE = SynchedEntityData.defineId(RocketRideEntity.class, EntityDataSerializers.FLOAT);

    // ---- server ----
    private int stateAge;
    private LivingEntity rider;
    private boolean riderNoGravity;
    private Vec3 jumpFrom;
    private int jumpTicks = 14;
    private UUID shooter;

    // ---- client ----
    private int stateC = -1, ageC;

    public RocketRideEntity(EntityType<? extends RocketRideEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(DATA_STATE, IDLE);
        b.define(DATA_BASE, 0.0F);
    }

    public int state() {
        return this.entityData.get(DATA_STATE);
    }

    private int viewState() {
        return level().isClientSide() && this.stateC >= 0 ? this.stateC : state();
    }

    private void setState(int s) {
        // the spin continues from where it is: the new state starts at the current angle
        float cur = angle(0.0F);
        this.entityData.set(DATA_BASE, Mth.wrapDegrees(cur * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD);
        this.entityData.set(DATA_STATE, s);
        this.stateAge = 0;
    }

    public int age() {
        return level().isClientSide() ? this.ageC : this.stateAge;
    }

    @Override
    public boolean isIdle() {
        return state() == IDLE && !isRemoved();
    }

    @Override
    public double loadRadius() {
        return WheelConfig.ROCKET_RADIUS.get();
    }

    public static int spinStart() {
        return (int) Math.round(WheelConfig.ROCKET_SPIN_DELAY.get() * 20.0D);
    }

    public static int launchAt() {
        return Math.max(spinStart() + 10, (int) Math.round(WheelConfig.ROCKET_LAUNCH_TIME.get() * 20.0D));
    }

    // ---------------------------------------------------------------- spin geometry

    /** Spin angle of the carousel (radians). */
    public float angle(float partial) {
        float a = age() + partial;
        float base = this.entityData.get(DATA_BASE);
        switch (viewState()) {
            case LOADED -> {
                float t = a - spinStart();
                if (t <= 0.0F) return base;
                float ramp = launchAt() - spinStart();
                // spin-up: omega grows with t^2, so the angle grows with t^3
                if (t <= ramp) return base + OMEGA * t * t * t / (3.0F * ramp * ramp);
                return base + OMEGA * ramp / 3.0F + OMEGA * (t - ramp);
            }
            case RELOAD -> {
                if (a < SPIN_DOWN) return base + OMEGA * (a - a * a / (2.0F * SPIN_DOWN));
                return base + OMEGA * SPIN_DOWN * 0.5F;
            }
            default -> {
                return base;
            }
        }
    }

    /** Current spin speed, 0..OMEGA. */
    public float omega(float partial) {
        float a = age() + partial;
        return switch (viewState()) {
            case LOADED -> {
                float t = a - spinStart();
                float ramp = launchAt() - spinStart();
                yield t <= 0.0F ? 0.0F : OMEGA * Math.min(1.0F, (t / ramp) * (t / ramp));
            }
            case RELOAD -> OMEGA * Math.max(0.0F, 1.0F - a / SPIN_DOWN);
            default -> 0.0F;
        };
    }

    /** The arms lift as the ride speeds up. */
    public float tilt(float partial) {
        return 0.12F + 0.42F * omega(partial) / OMEGA;
    }

    /** Centre of rocket car k (entity space). */
    public static Vec3 carLocal(int k, float angle, float tilt) {
        double a = angle + k * Math.PI * 0.5D;
        double h = HUB_R + ARM_LEN * Math.cos(tilt);
        return new Vec3(Math.cos(a) * h, ARM_Y + ARM_LEN * Math.sin(tilt) + CAR_UP, Math.sin(a) * h);
    }

    /** Direction car k is moving in (entity space, horizontal). */
    public static Vec3 tangentLocal(int k, float angle) {
        double a = angle + k * Math.PI * 0.5D;
        return new Vec3(-Math.sin(a), 0.0D, Math.cos(a));
    }

    public Vec3 toWorld(Vec3 local) {
        double yaw = Math.toRadians(getYRot());
        double cs = Math.cos(yaw), sn = Math.sin(yaw);
        return position().add(local.x * cs - local.z * sn, local.y, local.x * sn + local.z * cs);
    }

    public Vec3 dirToWorld(Vec3 local) {
        double yaw = Math.toRadians(getYRot());
        double cs = Math.cos(yaw), sn = Math.sin(yaw);
        return new Vec3(local.x * cs - local.z * sn, local.y, local.x * sn + local.z * cs);
    }

    /** Top of the hull of car 0 (the seat surface), entity space height above the car centre. */
    private static final double HULL_TOP = 0.34D;

    /**
     * Where the rider's feet go: on top of the hull, minus the rider's own vehicle attachment point - the vanilla
     * rule, so every model (players, Custom NPCs, Huggy's own sit animation) sits on the rocket the way it sits in
     * a boat, instead of sinking into it.
     */
    private Vec3 riderFeet(Entity passenger, float partial) {
        Vec3 top = toWorld(carLocal(0, viewAngle(partial), tilt(partial)).add(0.0D, HULL_TOP, 0.0D));
        return top.subtract(0.0D, passenger.getVehicleAttachmentPoint(this).y, 0.0D);
    }

    private Vec3 seatWorld(float partial) {
        return toWorld(carLocal(0, viewAngle(partial), tilt(partial)).add(0.0D, SEAT_UP, 0.0D));
    }

    /** Car 0 is gone (flying) until a new rocket appears. */
    public boolean car0Missing() {
        return viewState() == RELOAD && age() < REGROW;
    }

    // ---------------------------------------------------------------- loading

    @Override
    public void load(ServerPlayer player, LivingEntity mob) {
        if (mob.isPassenger()) mob.stopRiding();
        this.rider = mob;
        this.riderNoGravity = mob.isNoGravity();
        this.shooter = player.getUUID();
        this.jumpFrom = mob.position();
        double dist = this.jumpFrom.distanceTo(seatWorld(0.0F));
        this.jumpTicks = Mth.clamp((int) Math.round(10 + dist * 0.9D), 10, 36);
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
            case RELOAD -> {
                if (this.stateAge == REGROW) {
                    Vec3 c = toWorld(carLocal(0, angle(0.0F), tilt(0.0F)));
                    level.sendParticles(ParticleTypes.POOF, c.x, c.y, c.z, 12, 0.4D, 0.2D, 0.4D, 0.03D);
                    level.sendParticles(ParticleTypes.WITCH, c.x, c.y, c.z, 12, 0.5D, 0.3D, 0.5D, 0.0D);
                    level.playSound(null, c.x, c.y, c.z, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.NEUTRAL, 1.0F, 1.2F);
                }
                if (this.stateAge >= SPIN_DOWN) setState(IDLE);
            }
            default -> setState(IDLE);
        }
    }

    private void physics() {
        Vec3 dm = getDeltaMovement();
        if (onGround() && dm.lengthSqr() < 1.0E-6D) return;
        dm = dm.add(0.0D, -0.04D, 0.0D);
        move(MoverType.SELF, dm);
        setDeltaMovement(onGround() ? Vec3.ZERO : dm.scale(0.98D));
    }

    private void loadingTick(ServerLevel level) {
        if (!riderValid() || this.jumpFrom == null) {
            dropRider();
            setState(IDLE);
            return;
        }
        LivingEntity m = this.rider;
        float t = Math.min(1.0F, this.stateAge / (float) this.jumpTicks);
        double e = t * t * (3.0D - 2.0D * t);
        Vec3 to = riderFeet(m, 0.0F);
        double hop = Math.sin(Math.PI * t) * (1.6D + this.jumpFrom.distanceTo(to) * 0.12D);
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
            Vec3 s = seatWorld(0.0F);
            level.playSound(null, s.x, s.y, s.z, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.NEUTRAL, 0.8F, 0.8F);
            level.playSound(null, s.x, s.y, s.z, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.NEUTRAL, 1.0F, 0.6F);
            level.sendParticles(ParticleTypes.POOF, s.x, s.y, s.z, 8, 0.3D, 0.1D, 0.3D, 0.02D);
        }
    }

    private void loadedTick(ServerLevel level) {
        if (!riderValid()) {
            dropRider();
            setState(RELOAD);
            return;
        }
        LivingEntity m = this.rider;
        if (m.getVehicle() != this) {
            Vec3 s = riderFeet(m, 0.0F);
            m.setPos(s.x, s.y, s.z);
            m.setDeltaMovement(Vec3.ZERO);
        }
        if (m instanceof Mob mob) mob.getNavigation().stop();
        int start = spinStart(), launch = launchAt();
        int a = this.stateAge;
        if (a == start) {
            playAt(level, SoundEvents.BEACON_ACTIVATE, 1.0F, 1.6F);
            playAt(level, SoundEvents.NOTE_BLOCK_BELL.value(), 1.0F, 0.5F);
        }
        if (a > start) {
            float k = (a - start) / (float) (launch - start);
            // a faster and faster carnival tick-tock, rising in pitch
            int period = Math.max(2, Math.round(10 - 8 * k));
            if (a % period == 0) playAt(level, SoundEvents.NOTE_BLOCK_HAT.value(), 0.7F, 0.6F + 1.2F * k);
            if (a % 20 == 0) playAt(level, SoundEvents.NOTE_BLOCK_BELL.value(), 0.9F, 0.6F + 1.0F * k);
        }
        if (a == launch - 30) playAt(level, SoundEvents.FIRECHARGE_USE, 1.0F, 0.6F);
        if (a >= launch) {
            release(level);
            setState(RELOAD);
        }
    }

    /** Car 0 breaks off and flies away with the NPC. */
    private void release(ServerLevel level) {
        LivingEntity m = this.rider;
        this.rider = null;
        if (m == null || !m.isAlive() || m.isRemoved()) return;
        float ang = angle(0.0F), tl = tilt(0.0F);
        Vec3 car = toWorld(carLocal(0, ang, tl));
        Vec3 tan = dirToWorld(tangentLocal(0, ang)).normalize();
        Vec3 tanH = new Vec3(tan.x, 0.0D, tan.z).normalize();

        if (m.getVehicle() == this) m.stopRiding();
        Vec3 seat = car.add(0.0D, SEAT_UP, 0.0D);
        m.setPos(seat.x, seat.y - m.getBbHeight() * 0.5D, seat.z);
        RocketEntity rocket = RocketEntity.spawn(level, car, tanH, m, this.riderNoGravity);
        Vec3 vel = rocket.velocity();
        ServerPlayer player = this.shooter != null ? level.getServer().getPlayerList().getPlayer(this.shooter) : null;
        BallistaServer.launch(level, player, m, vel, Vec3.ZERO, false);

        level.playSound(null, car.x, car.y, car.z, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.NEUTRAL, 2.0F, 0.5F);
        level.playSound(null, car.x, car.y, car.z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.NEUTRAL, 1.6F, 0.7F);
        level.playSound(null, car.x, car.y, car.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, 0.7F, 1.6F);
        level.sendParticles(ParticleTypes.FLASH, car.x, car.y, car.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.EXPLOSION, car.x, car.y, car.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, car.x, car.y, car.z, 14, 0.4D, 0.3D, 0.4D, 0.04D);
        for (int i = 0; i < 30; i++) {
            int col = WheelEntity.BULB_COLORS[i % WheelEntity.BULB_COLORS.length];
            level.sendParticles(WheelEntity.dust(col, 1.3F), car.x, car.y, car.z, 1, 0.6D, 0.4D, 0.6D, 0.0D);
        }
    }

    private void playAt(ServerLevel level, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, getX(), getY() + 2.0D, getZ(), sound, SoundSource.NEUTRAL, volume, pitch);
    }

    // ---------------------------------------------------------------- rider

    @Override
    protected void positionRider(Entity passenger, MoveFunction move) {
        if (!hasPassenger(passenger)) return;
        Vec3 s = riderFeet(passenger, 0.0F);
        move.accept(passenger, s.x, s.y, s.z);
        Vec3 t = dirToWorld(tangentLocal(0, viewAngle(0.0F)));
        float yaw = (float) (Mth.atan2(-t.x, t.z) * Mth.RAD_TO_DEG);
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

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide() || isRemoved()) return false;
        if (!(source.getEntity() instanceof Player)) return false;
        ServerLevel level = (ServerLevel) level();
        dropRider();
        ejectPassengers();
        Vec3 c = position().add(0.0D, 2.0D, 0.0D);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.ANVIL_DESTROY, SoundSource.NEUTRAL, 0.6F, 1.3F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.NEUTRAL, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.POOF, c.x, c.y, c.z, 30, 1.6D, 1.2D, 1.6D, 0.04D);
        level.sendParticles(ParticleTypes.WITCH, c.x, c.y, c.z, 30, 1.6D, 1.2D, 1.6D, 0.0D);
        for (int i = 0; i < 30; i++) {
            level.sendParticles(WheelEntity.dust(WheelEntity.BULB_COLORS[i % WheelEntity.BULB_COLORS.length], 1.2F), c.x, c.y, c.z, 1, 1.6D, 1.2D, 1.6D, 0.0D);
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
        return d < 160.0D * 160.0D;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    // ---------------------------------------------------------------- client

    /**
     * Client: the state change may arrive a tick early or late; at full speed that would be a visible jump of the
     * carousel. The difference between the old and the new formula is kept as an offset that fades out.
     */
    private float offC, offPrevC;

    /** Angle to draw (client: with the fading offset). */
    public float viewAngle(float partial) {
        if (!level().isClientSide()) return angle(partial);
        return angle(partial) + Mth.lerp(partial, this.offPrevC, this.offC);
    }

    private void clientTick() {
        int s = state();
        this.offPrevC = this.offC;
        if (s != this.stateC) {
            boolean had = this.stateC >= 0;
            this.ageC++;
            float before = angle(0.0F) + this.offC;
            this.stateC = s;
            this.ageC = 0;
            if (had) {
                this.offC = Mth.wrapDegrees((before - angle(0.0F)) * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD;
                this.offPrevC = this.offC;
            }
        } else {
            this.ageC++;
        }
        this.offC *= 0.8F;
        RocketFx.rideAmbient(this);
    }
}
