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
 * Dark Carnival "Ranger": a tall tower with a long swing arm on a pivot at its top: a caged gondola at one end, a
 * giant mallet as the counterweight at the other. It stays until a player hits it.
 * IDLE    - the gondola waits at the bottom,
 * LOADING - an NPC hops into the gondola (numpad 7, like the ballista and the rocket ride),
 * LOADED  - a moment's pause, then the arm swings higher and higher, goes over the top in full loops, turns round
 *           and loops the other way; on the last upswing the NPC is flung far away as a ragdoll,
 * RELOAD  - the arm slows down and settles back at the bottom.
 * The arm angle is a pure function of (state, ticks in state) with fixed constants, so the client draws it smoothly
 * without a packet per tick. Angle 0 = gondola straight down; the arm turns in the plane in front of the tower.
 */
public class RangerRideEntity extends Entity implements NpcLauncher {
    public static final int IDLE = 0, LOADING = 1, LOADED = 2, RELOAD = 3;
    /** Pivot height, arm length to the gondola centre and to the mallet, depth of the arm and of the gondola. */
    public static final float PIVOT_Y = 6.6F, ARM = 5.0F, MALLET = 2.5F, ARM_Z = 0.45F, GOND_Z = 1.35F;

    // ---- the ride's program (ticks); W = top speed of the loops, rad/tick
    // a swing period of 2.2 s; the pumping ends after three half swings, passing the bottom at full speed
    static final int HOLD = 20, P1 = 66, P2 = 30, P3 = 26;
    static final double K = Math.PI * 2.0D / 44.0D, AMP = 1.9D, W = AMP * K;
    /** Direction of the first loops (+1 or -1): the way the last pumping swing passes the bottom. */
    static final double DIR = Math.signum(Math.cos(K * P1));
    /**
     * Where the gondola lets go: low on one side and swinging up, so the NPC flies up and out. The last loops turn
     * the other way (-DIR), so the release point is mirrored with them.
     */
    static final double RELEASE = DIR * -0.93D;
    static final int P4;
    static final double TH3;
    public static final int LAUNCH;
    static final double TH_LAUNCH, SETTLE_TO, SETTLE_D, SETTLE_W;

    static {
        TH3 = DIR * W * P2;
        double rem = Mth.positiveModulo(DIR * (TH3 - DIR * 12.0D * W - RELEASE), Math.PI * 2.0D);
        P4 = 12 + (int) Math.round(rem / W);
        LAUNCH = HOLD + P1 + P2 + P3 + P4;
        TH_LAUNCH = loadedAngle(LAUNCH);
        // after the throw it keeps turning the same way, slows down and stops at the bottom
        SETTLE_W = -DIR * W;
        double d = Mth.positiveModulo(-Math.signum(SETTLE_W) * TH_LAUNCH, Math.PI * 2.0D);
        if (d < 1.5D) d += Math.PI * 2.0D;
        SETTLE_TO = TH_LAUNCH + Math.signum(SETTLE_W) * d;
        SETTLE_D = 2.0D * d / W;
    }

    private static final EntityDataAccessor<Integer> DATA_STATE = SynchedEntityData.defineId(RangerRideEntity.class, EntityDataSerializers.INT);
    /** Entity id of the NPC in the gondola (-1 = none): the client draws it glued into the gondola every frame. */
    private static final EntityDataAccessor<Integer> DATA_RIDER = SynchedEntityData.defineId(RangerRideEntity.class, EntityDataSerializers.INT);

    /**
     * Client: NPC id -> the ride it sits in. Its position from the server comes a few ticks late (interpolated), far
     * behind a gondola looping at speed, so it is drawn where the gondola is in this very frame instead.
     */
    private static final java.util.Map<Integer, RangerRideEntity> CLIENT_RIDERS = new java.util.HashMap<>();

    public static void clearClient() {
        CLIENT_RIDERS.clear();
    }

    /** Client: the ride whose gondola this entity sits in right now, or null. */
    public static RangerRideEntity rideOf(Entity e) {
        RangerRideEntity r = CLIENT_RIDERS.get(e.getId());
        if (r == null) return null;
        if (r.isRemoved() || r.viewState() != LOADED || r.entityData.get(DATA_RIDER) != e.getId() || r.level() != e.level()) {
            CLIENT_RIDERS.remove(e.getId());
            return null;
        }
        return r;
    }

    /** Gondola centre in the world. */
    public Vec3 gondolaWorld(float partial) {
        return toWorld(gondolaLocal(viewAngle(partial)));
    }

    // ---- server ----
    private int stateAge;
    private LivingEntity rider;
    private boolean riderNoGravity;
    private Vec3 jumpFrom;
    private int jumpTicks = 14;
    private UUID shooter;

    // ---- client ----
    private int stateC = -1, ageC;
    private float offC, offPrevC;

    public RangerRideEntity(EntityType<? extends RangerRideEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(DATA_STATE, IDLE);
        b.define(DATA_RIDER, -1);
    }

    public int state() {
        return this.entityData.get(DATA_STATE);
    }

    private int viewState() {
        return level().isClientSide() && this.stateC >= 0 ? this.stateC : state();
    }

    private void setState(int s) {
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
        return WheelConfig.RANGER_RADIUS.get();
    }

    // ---------------------------------------------------------------- the program

    /** Arm angle t ticks after the NPC sat down. */
    static double loadedAngle(double t) {
        t -= HOLD;
        if (t <= 0.0D) return 0.0D;
        // swinging higher and higher (passes the bottom at full speed exactly when this part ends)
        if (t < P1) return AMP * Math.pow(t / P1, 1.5D) * Math.sin(K * t);
        t -= P1;
        // over the top: full loops
        if (t < P2) return DIR * W * t;
        t -= P2;
        // brakes, turns round
        if (t < P3) return TH3 + DIR * W * P3 / Math.PI * Math.sin(Math.PI * t / P3);
        t -= P3;
        // and loops the other way until the release
        return TH3 - DIR * W * Math.min(t, P4 + 2.0D);
    }

    /** Arm angle (radians) in the current state. */
    public double angle(float partial) {
        double a = age() + partial;
        return switch (viewState()) {
            case LOADED -> loadedAngle(a);
            case RELOAD -> a < SETTLE_D ? TH_LAUNCH + SETTLE_W * a - SETTLE_W * a * a / (2.0D * SETTLE_D)
                    : SETTLE_TO + 0.22D * Math.exp(-(a - SETTLE_D) / 14.0D) * Math.sin((a - SETTLE_D) * 0.3D);
            default -> 0.0D;
        };
    }

    /** How fast it turns right now, 0..1 (lights, effects). */
    public float speed(float partial) {
        double d = angle(partial + 0.5F) - angle(partial - 0.5F);
        return (float) Math.min(1.0D, Math.abs(d) / W);
    }

    /** Gondola centre in entity space. */
    public static Vec3 gondolaLocal(double ang) {
        return new Vec3(ARM * Math.sin(ang), PIVOT_Y - ARM * Math.cos(ang), GOND_Z);
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

    /** The NPC's feet are this far below the gondola centre: on the floor of the cup, whatever its height. */
    public static final double FEET = 0.88D;

    private Vec3 riderFeet(Entity passenger, float partial) {
        Vec3 c = toWorld(gondolaLocal(viewAngle(partial)));
        return c.subtract(0.0D, FEET, 0.0D);
    }

    // ---------------------------------------------------------------- loading

    @Override
    public void load(ServerPlayer player, LivingEntity mob) {
        if (mob.isPassenger()) mob.stopRiding();
        this.rider = mob;
        this.entityData.set(DATA_RIDER, mob.getId());
        this.riderNoGravity = mob.isNoGravity();
        this.shooter = player.getUUID();
        this.jumpFrom = mob.position();
        double dist = this.jumpFrom.distanceTo(riderFeet(mob, 0.0F));
        this.jumpTicks = Mth.clamp((int) Math.round(10 + dist * 0.9D), 10, 36);
        mob.setNoGravity(true);
        WheelMod.hold(mob);
        if (mob instanceof Mob m) m.getNavigation().stop();
        setState(LOADING);
        Vec3 p = mob.position();
        level().playSound(null, p.x, p.y, p.z, SoundEvents.EVOKER_CAST_SPELL, SoundSource.NEUTRAL, 1.0F, 1.3F);
        level().playSound(null, p.x, p.y, p.z, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.NEUTRAL, 1.0F, 0.7F);
    }

    private boolean riderValid() {
        LivingEntity m = this.rider;
        return m != null && m.isAlive() && !m.isRemoved() && m.level() == level() && !WheelRagdolls.isRagdolled(m);
    }

    private void dropRider() {
        LivingEntity m = this.rider;
        this.rider = null;
        this.entityData.set(DATA_RIDER, -1);
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
                if (this.stateAge >= SETTLE_D + 40) setState(IDLE);
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
        double hop = Math.sin(Math.PI * t) * (1.8D + this.jumpFrom.distanceTo(to) * 0.12D);
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
            Vec3 s = toWorld(gondolaLocal(0.0D));
            level.playSound(null, s.x, s.y, s.z, SoundEvents.IRON_DOOR_CLOSE, SoundSource.NEUTRAL, 1.0F, 0.7F);
            level.playSound(null, s.x, s.y, s.z, SoundEvents.CHAIN_PLACE, SoundSource.NEUTRAL, 1.0F, 0.6F);
            level.sendParticles(ParticleTypes.POOF, s.x, s.y, s.z, 8, 0.4D, 0.2D, 0.4D, 0.02D);
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
        m.fallDistance = 0.0F;
        if (m instanceof Mob mob) mob.getNavigation().stop();
        int a = this.stateAge;
        if (a == HOLD) {
            playAt(level, SoundEvents.BELL_BLOCK, 1.0F, 0.7F);
            playAt(level, SoundEvents.PISTON_EXTEND, 1.0F, 0.5F);
        }
        if (a > HOLD) {
            // the arm whooshes past the bottom; the carnival organ speeds up
            float sp = speed(0.0F);
            if (a % Math.max(3, Math.round(12 - 9 * sp)) == 0) playAt(level, SoundEvents.NOTE_BLOCK_HAT.value(), 0.6F, 0.6F + 1.0F * sp);
            double th = angle(0.0F), thPrev = angle(-1.0F);
            if (Math.floor(th / (Math.PI * 2.0D) + 0.5D) != Math.floor(thPrev / (Math.PI * 2.0D) + 0.5D) && sp > 0.5F) {
                Vec3 g = toWorld(gondolaLocal(th));
                level.playSound(null, g.x, g.y, g.z, SoundEvents.PHANTOM_FLAP, SoundSource.NEUTRAL, 1.4F, 0.6F);
            }
            if (a % 20 == 0) playAt(level, SoundEvents.NOTE_BLOCK_BELL.value(), 0.8F, 0.6F + 0.8F * sp);
        }
        if (a == HOLD + P1 + P2) playAt(level, SoundEvents.ANVIL_LAND, 0.6F, 0.5F);
        if (a == LAUNCH - 14) playAt(level, SoundEvents.FIRECHARGE_USE, 1.0F, 0.6F);
        if (a >= LAUNCH) {
            release(level);
            setState(RELOAD);
        }
    }

    /** The cage door bursts open on the upswing and the NPC is flung out. */
    private void release(ServerLevel level) {
        LivingEntity m = this.rider;
        this.rider = null;
        this.entityData.set(DATA_RIDER, -1);
        if (m == null || !m.isAlive() || m.isRemoved()) return;
        double th = angle(0.0F);
        Vec3 g = toWorld(gondolaLocal(th));
        // the gondola's own motion: along (cos, sin) in the arm plane times the turning direction, a little away
        // from the tower
        double turn = Math.signum(th - angle(-1.0F));
        if (turn == 0.0D) turn = -DIR;
        Vec3 tanLocal = new Vec3(turn * Math.cos(th), turn * Math.sin(th), 0.0D);
        if (tanLocal.y < 0.35D) tanLocal = new Vec3(tanLocal.x, 0.35D, 0.0D);
        Vec3 dir = dirToWorld(tanLocal.normalize().add(0.0D, 0.0D, 0.18D)).normalize();
        Vec3 vel = dir.scale(WheelConfig.RANGER_SPEED.get()).add(0.0D, WheelConfig.RANGER_LIFT.get(), 0.0D);
        Vec3 axis = new Vec3(0.0D, 1.0D, 0.0D).cross(dir);
        Vec3 spin = (axis.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : axis.normalize()).scale(-2.2D)
                .add(level.random.nextGaussian() * 0.5D, level.random.nextGaussian() * 0.5D, level.random.nextGaussian() * 0.5D);

        if (m.getVehicle() == this) m.stopRiding();
        m.setNoGravity(this.riderNoGravity);
        m.setPos(g.x, g.y - m.getBbHeight() * 0.5D, g.z);
        ServerPlayer player = this.shooter != null ? level.getServer().getPlayerList().getPlayer(this.shooter) : null;
        BallistaServer.launch(level, player, m, vel, spin, true);

        level.playSound(null, g.x, g.y, g.z, SoundEvents.IRON_DOOR_OPEN, SoundSource.NEUTRAL, 1.4F, 0.6F);
        level.playSound(null, g.x, g.y, g.z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.NEUTRAL, 1.6F, 0.7F);
        level.playSound(null, g.x, g.y, g.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, 0.7F, 1.5F);
        level.sendParticles(ParticleTypes.FLASH, g.x, g.y, g.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.EXPLOSION, g.x, g.y, g.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, g.x, g.y, g.z, 14, 0.5D, 0.4D, 0.5D, 0.04D);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, g.x, g.y, g.z, 20, 0.5D, 0.5D, 0.5D, 0.08D);
        for (int i = 0; i < 36; i++) {
            int col = WheelEntity.BULB_COLORS[i % WheelEntity.BULB_COLORS.length];
            Vec3 v = dir.scale(0.4D).add(level.random.nextGaussian() * 0.3D, level.random.nextGaussian() * 0.3D, level.random.nextGaussian() * 0.3D);
            level.sendParticles(WheelEntity.dust(col, 1.4F), g.x + v.x, g.y + v.y, g.z + v.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    private void playAt(ServerLevel level, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, getX(), getY() + PIVOT_Y, getZ(), sound, SoundSource.NEUTRAL, volume, pitch);
    }

    // ---------------------------------------------------------------- rider

    @Override
    protected void positionRider(Entity passenger, MoveFunction move) {
        if (!hasPassenger(passenger)) return;
        Vec3 s = riderFeet(passenger, 0.0F);
        move.accept(passenger, s.x, s.y, s.z);
        // facing out of the cage, away from the tower
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

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide() || isRemoved()) return false;
        if (!(source.getEntity() instanceof Player)) return false;
        ServerLevel level = (ServerLevel) level();
        dropRider();
        ejectPassengers();
        Vec3 c = position().add(0.0D, 3.0D, 0.0D);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.ANVIL_DESTROY, SoundSource.NEUTRAL, 0.6F, 1.2F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.NEUTRAL, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.POOF, c.x, c.y, c.z, 40, 1.4D, 2.5D, 1.4D, 0.04D);
        level.sendParticles(ParticleTypes.WITCH, c.x, c.y, c.z, 40, 1.4D, 2.5D, 1.4D, 0.0D);
        for (int i = 0; i < 30; i++) {
            level.sendParticles(WheelEntity.dust(WheelEntity.BULB_COLORS[i % WheelEntity.BULB_COLORS.length], 1.2F), c.x, c.y, c.z, 1, 1.4D, 2.5D, 1.4D, 0.0D);
        }
        discard();
        return true;
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide()) dropRider();
        else CLIENT_RIDERS.values().removeIf(r -> r == this);
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
        return d < 192.0D * 192.0D;
    }

    @Override
    public net.minecraft.world.phys.AABB getBoundingBoxForCulling() {
        return getBoundingBox().inflate(ARM + MALLET, PIVOT_Y + ARM, ARM + MALLET);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    // ---------------------------------------------------------------- client

    /** Angle to draw (client: a state change that arrived a tick early or late is smoothed out). */
    public double viewAngle(float partial) {
        if (!level().isClientSide()) return angle(partial);
        return angle(partial) + Mth.lerp(partial, this.offPrevC, this.offC);
    }

    private void clientTick() {
        int s = state();
        this.offPrevC = this.offC;
        if (s != this.stateC) {
            boolean had = this.stateC >= 0;
            this.ageC++;
            double before = angle(0.0F) + this.offC;
            this.stateC = s;
            this.ageC = 0;
            if (had) {
                this.offC = (float) (before - angle(0.0F));
                if (Math.abs(this.offC) > 1.5F) this.offC = 0.0F;
                this.offPrevC = this.offC;
            }
        } else {
            this.ageC++;
        }
        this.offC *= 0.8F;
        int id = this.entityData.get(DATA_RIDER);
        if (id >= 0 && viewState() == LOADED) CLIENT_RIDERS.put(id, this);
        CLIENT_RIDERS.values().removeIf(r -> r == this && (viewState() != LOADED || this.entityData.get(DATA_RIDER) < 0));
        RangerFx.ambient(this);
    }
}
