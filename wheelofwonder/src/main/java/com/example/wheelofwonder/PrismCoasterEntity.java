package com.example.wheelofwonder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * PRISM SPLITTER - a ragdoll coaster of mana crystals. A station with crystal towers, neon rails on pillars that
 * swerve, then curve up a ramp through three glowing rings over a see-through rainbow bridge - and simply end in the
 * air. The cart is an open gondola of 24 crystal pieces.
 *  IDLE    - the cart waits at the station,
 *  LOADING - an NPC hops in (numpad 7, like the other rides),
 *  CHARGE  - the crystals light up, energy gathers, prism sparks swirl round the cart, the rails light up one after
 *            another,
 *  RIDE    - the cart shoots off, faster and faster, up the ramp,
 *  FLIGHT  - where the rails end the cart bursts into its crystals: each keeps the cart's speed and the NPC flies on
 *            as a ragdoll. The crystals catch up, circle it and fire beams of energy at random parts of its body,
 *            each beam a push in another direction - a living projectile steered by the crystals,
 *  FALL    - near the ground (or blocks) the steering stops: the body and the crystals just fall,
 *  REFORM  - the crystals fly back to the station and build the cart again.
 * The track and the cart on it are pure functions of (state, age); the free crystals are simulated on the server
 * and sent to the client every tick (PrismSync).
 */
public class PrismCoasterEntity extends Entity implements NpcLauncher {
    public static final int IDLE = 0, LOADING = 1, CHARGE = 2, RIDE = 3, FLIGHT = 4, FALL = 5, REFORM = 6;
    /**
     * The track: a smooth curve through these points (local: x along the ride, y up, z aside - negative z is away
     * from whoever placed it). The raised station, a dive turning away, a climb into an elevated hairpin, a dive back
     * under, a rise that crosses over the first dip, a run of waves and the final ramp up into the air. The first and
     * last points only shape the ends.
     */
    private static final double[][] CTRL = {
            {-6.0D, 3.5D, 0.0D}, {-2.0D, 3.5D, 0.0D}, {2.0D, 3.5D, 0.0D}, {6.0D, 3.3D, 0.0D},
            {10.0D, 1.8D, -1.5D}, {14.0D, 1.6D, -4.5D}, {17.0D, 3.0D, -7.5D}, {18.0D, 5.0D, -11.0D},
            {16.0D, 6.0D, -14.5D}, {12.0D, 5.0D, -16.0D}, {8.0D, 3.0D, -15.0D}, {5.0D, 1.8D, -12.0D},
            {6.0D, 2.6D, -8.0D}, {10.0D, 5.5D, -6.5D}, {14.0D, 7.5D, -3.5D}, {20.5D, 3.2D, -2.0D},
            {24.0D, 1.8D, -2.0D}, {28.0D, 3.4D, -0.5D}, {32.0D, 1.9D, 1.0D}, {36.0D, 2.6D, 1.5D},
            {39.5D, 5.0D, 1.5D}, {42.0D, 8.0D, 1.5D}, {44.0D, 11.0D, 1.5D}};
    private static final int SAMPLES = 32;
    private static final Vec3[] PTS;
    private static final double[] CUM;
    /** Track length, where the cart waits, the station's rail height, half the gauge, where the final ramp starts. */
    public static final double L, S0 = 3.0D, STATION_Y = 3.5D, GAUGE = 0.5D, RAMP_FROM;
    public static final int CHARGE_T = 50, RIDE_T, FALL_MAX = 70, REFORM_T = 90;
    /** The NPC's feet are this far above the rail line (on the cart's floor). */
    public static final double FEET = 0.32D;
    /** Glowing rings the cart flies through. */
    public static final double[] RINGS;

    static {
        int segs = CTRL.length - 3;
        PTS = new Vec3[segs * SAMPLES + 1];
        CUM = new double[PTS.length];
        int k = 0;
        for (int s = 0; s < segs; s++) {
            for (int j = 0; j < SAMPLES; j++) PTS[k++] = catmull(s + 1, j / (double) SAMPLES);
        }
        PTS[k] = catmull(segs, 1.0D);
        for (int i = 1; i < PTS.length; i++) CUM[i] = CUM[i - 1] + PTS[i].distanceTo(PTS[i - 1]);
        L = CUM[CUM.length - 1];
        // where the last climb begins (the fourth point from the end)
        RAMP_FROM = CUM[(CTRL.length - 5) * SAMPLES];
        // a kick off the station, then faster and faster: about 17 m/s where the rails end
        RIDE_T = (int) Math.round((L - S0) * 1.6D / 0.85D);
        RINGS = new double[]{CUM[7 * SAMPLES], CUM[13 * SAMPLES], L - 9.0D, L - 5.5D, L - 2.0D};
    }

    private static Vec3 catmull(int k, double t) {
        double[] p0 = CTRL[k - 1], p1 = CTRL[k], p2 = CTRL[k + 1], p3 = CTRL[k + 2];
        double t2 = t * t, t3 = t2 * t;
        double[] o = new double[3];
        for (int a = 0; a < 3; a++) {
            o[a] = 0.5D * (2.0D * p1[a] + (-p0[a] + p2[a]) * t + (2.0D * p0[a] - 5.0D * p1[a] + 4.0D * p2[a] - p3[a]) * t2
                    + (-p0[a] + 3.0D * p1[a] - 3.0D * p2[a] + p3[a]) * t3);
        }
        return new Vec3(o[0], o[1], o[2]);
    }
    public static final int N = 24, FLOATS = 11;
    public static final int[] PALETTE = {0x9B4DFF, 0xFF5FD2, 0x5FD8FF, 0xFF3B5C, 0xFFC34A};

    // ---- the cart's pieces: base point and axis in the cart frame (x side, y up, z forward), length, radius, kind
    public static final Vec3[] SLOT_POS = new Vec3[N], SLOT_AXIS = new Vec3[N];
    public static final float[] SLOT_LEN = new float[N], SLOT_RAD = new float[N];
    /** 0 = crystal, 1 = keel (the cart's dark metal runners) */
    public static final int[] SLOT_KIND = new int[N];

    static {
        int i = 0;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int k = -1; k <= 1; k++) slot(i++, sx * 0.33D, 0.24D, k * 0.6D - 0.3D, 0, 0, 1, 0.6F, 0.19F, 0);   // floor
        }
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int k = 0; k < 4; k++) slot(i++, sx * 0.72D, 0.22D, -0.72D + k * 0.48D, sx * 0.16D, 1, 0, 0.82F + 0.12F * (k % 2), 0.17F, 0);   // side walls
        }
        for (int sz = -1; sz <= 1; sz += 2) {
            for (int sx = -1; sx <= 1; sx += 2) slot(i++, sx * 0.3D, 0.22D, sz * 0.95D, 0, 1, sz * 0.16D, 0.74F, 0.17F, 0);   // front, back
        }
        for (int sz = -1; sz <= 1; sz += 2) {
            for (int sx = -1; sx <= 1; sx += 2) slot(i++, sx * 0.78D, 0.18D, sz * 0.98D, sx * 0.42D, 1, sz * 0.42D, 1.3F, 0.25F, 0);   // corners
        }
        for (int sx = -1; sx <= 1; sx += 2) slot(i++, sx * 0.45D, 0.02D, -0.86D, 0, 0, 1, 1.72F, 0.14F, 1);   // keels
    }

    private static void slot(int i, double x, double y, double z, double ax, double ay, double az, float len, float rad, int kind) {
        SLOT_POS[i] = new Vec3(x, y, z);
        SLOT_AXIS[i] = new Vec3(ax, ay, az).normalize();
        SLOT_LEN[i] = len;
        SLOT_RAD[i] = rad;
        SLOT_KIND[i] = kind;
    }

    private static final EntityDataAccessor<Integer> DATA_STATE = SynchedEntityData.defineId(PrismCoasterEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_RIDER = SynchedEntityData.defineId(PrismCoasterEntity.class, EntityDataSerializers.INT);

    /** Client: NPC id -> the coaster whose cart it sits in (drawn glued into the cart every frame). */
    private static final java.util.Map<Integer, PrismCoasterEntity> CLIENT_RIDERS = new java.util.HashMap<>();

    public static void clearClient() {
        CLIENT_RIDERS.clear();
    }

    public static PrismCoasterEntity rideOf(Entity e) {
        PrismCoasterEntity r = CLIENT_RIDERS.get(e.getId());
        if (r == null) return null;
        int s = r.viewState();
        if (r.isRemoved() || (s != CHARGE && s != RIDE) || r.entityData.get(DATA_RIDER) != e.getId() || r.level() != e.level()) {
            CLIENT_RIDERS.remove(e.getId());
            return null;
        }
        return r;
    }

    // ---- server
    private int stateAge;
    private LivingEntity rider, flyer;
    private boolean riderNoGravity, rigidDone, ragdoll;
    private Vec3 jumpFrom, launchVel = Vec3.ZERO, launchSpin = Vec3.ZERO, lastC;
    private int jumpTicks = 14;
    private UUID shooter;
    private final Vec3[] p = new Vec3[N], v = new Vec3[N], axis = new Vec3[N], beamTo = new Vec3[N], from = new Vec3[N], axisFrom = new Vec3[N];
    private final float[] beam = new float[N];
    private final int[] beamCol = new int[N];
    private final boolean[] landed = new boolean[N];
    private int charging = -1, chargeLeft, chargePart, nextBeam;
    private Vec3 chargeDir = new Vec3(0.0D, 1.0D, 0.0D);
    /** The flight's smoothed centre and velocity (blocks, blocks per tick): what the escort keeps to. */
    private Vec3 sc, sv;

    // ---- client
    private int stateC = -1, ageC;
    private float[] syncPrev, syncCur;
    private int syncState = -1;
    /** Client: ground under each pillar and decoration (looked up once). */
    public double[] groundCache;

    public PrismCoasterEntity(EntityType<? extends PrismCoasterEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(DATA_STATE, IDLE);
        b.define(DATA_RIDER, -1);
    }

    public int state() {
        return this.entityData.get(DATA_STATE);
    }

    public int viewState() {
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
        return WheelConfig.PRISM_RADIUS.get();
    }

    // ---------------------------------------------------------------- the track (local: x along it, y up, z aside)

    /** The point of the track s blocks along it (beyond its ends it runs straight on). */
    public static Vec3 trackLocal(double s) {
        if (s <= 0.0D) return PTS[0].add(PTS[1].subtract(PTS[0]).normalize().scale(s));
        if (s >= L) {
            int n = PTS.length;
            return PTS[n - 1].add(PTS[n - 1].subtract(PTS[n - 2]).normalize().scale(s - L));
        }
        int lo = 0, hi = CUM.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (CUM[mid] <= s) lo = mid;
            else hi = mid;
        }
        double seg = CUM[hi] - CUM[lo];
        double u = seg < 1.0E-9D ? 0.0D : (s - CUM[lo]) / seg;
        return PTS[lo].lerp(PTS[hi], u);
    }

    /** The track's direction at s (about unit length: s is the distance along it), smoothed over a short stretch. */
    public static Vec3 trackDeriv(double s) {
        double h = 0.35D;
        double a = Math.max(0.0D, s - h), b = Math.min(L, s + h);
        if (b - a < 1.0E-4D) a = b - h;
        return trackLocal(b).subtract(trackLocal(a)).scale(1.0D / (b - a));
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

    /** The track frame at s in the world: point, forward, side, up. */
    public Vec3[] frame(double s) {
        Vec3 t = dirToWorld(trackDeriv(s)).normalize();
        Vec3 side = t.cross(new Vec3(0.0D, 1.0D, 0.0D));
        side = side.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : side.normalize();
        Vec3 up = side.cross(t).normalize();
        return new Vec3[]{toWorld(trackLocal(s)), t, side, up};
    }

    /** Where the cart is along the track (s) in the current state, partial ticks in. */
    public double cartS(float partial) {
        if (viewState() != RIDE) return S0;
        return cartSAt(age() + partial);
    }

    /** A kick off the station, then faster and faster. */
    public static double cartSAt(double rideAge) {
        double u = Mth.clamp(rideAge / RIDE_T, 0.0D, 1.0D);
        return S0 + (L - S0) * (0.4D * u + 0.6D * u * u);
    }

    /** ds per tick at the end of the ride (where the rails end). */
    static double endSpeed() {
        return (L - S0) * (0.4D + 1.2D) / RIDE_T;
    }

    /** World position of piece i on the cart standing at s, and its axis. */
    public Vec3 slotWorld(int i, double s) {
        Vec3[] f = frame(s);
        Vec3 q = SLOT_POS[i];
        return f[0].add(f[2].scale(q.x)).add(f[3].scale(q.y)).add(f[1].scale(q.z));
    }

    public Vec3 slotAxis(int i, double s) {
        Vec3[] f = frame(s);
        Vec3 a = SLOT_AXIS[i];
        return f[2].scale(a.x).add(f[3].scale(a.y)).add(f[1].scale(a.z)).normalize();
    }

    /** Client: the cart (rail point) in this frame. */
    public Vec3 cartWorld(float partial) {
        return frame(cartS(partial))[0];
    }

    /** Client: where the rider's body centre is drawn (the camera's target before the split). */
    public Vec3 riderCentre(float partial) {
        Vec3[] f = frame(cartS(partial));
        return f[0].add(f[3].scale(FEET + 0.9D));
    }

    /** 0..1: how far the rails are lit (CHARGE lights them up front to back). */
    public float railLit(double s, float partial) {
        double a = age() + partial;
        return switch (viewState()) {
            case CHARGE -> (float) Mth.clamp((a - 8.0D) / 30.0D * (L + 2.0D) - s + 1.0D, 0.0D, 1.0D);
            case RIDE -> 1.0F;
            case FLIGHT, FALL -> (float) Math.max(0.25D, 1.0D - a / 60.0D);
            default -> 0.25F;
        };
    }

    /** How brightly the cart's crystals burn (CHARGE gathers the energy). */
    public float charge(float partial) {
        double a = age() + partial;
        return switch (viewState()) {
            case CHARGE -> (float) (0.35D + 0.65D * Mth.clamp(a / CHARGE_T, 0.0D, 1.0D));
            case RIDE, FLIGHT, FALL -> 1.0F;
            case REFORM -> (float) (1.0D - 0.65D * Mth.clamp(a / REFORM_T, 0.0D, 1.0D));
            default -> 0.35F;
        };
    }

    // ---------------------------------------------------------------- loading

    private Vec3 riderFeet(double s) {
        Vec3[] f = frame(s);
        return f[0].add(f[3].scale(FEET));
    }

    @Override
    public void load(ServerPlayer player, LivingEntity mob) {
        if (mob.isPassenger()) mob.stopRiding();
        this.rider = mob;
        this.entityData.set(DATA_RIDER, mob.getId());
        this.riderNoGravity = mob.isNoGravity();
        this.shooter = player.getUUID();
        this.jumpFrom = mob.position();
        double dist = this.jumpFrom.distanceTo(riderFeet(S0));
        this.jumpTicks = Mth.clamp((int) Math.round(10 + dist * 0.9D), 10, 36);
        mob.setNoGravity(true);
        WheelMod.hold(mob);
        if (mob instanceof Mob m) m.getNavigation().stop();
        setState(LOADING);
        Vec3 q = mob.position();
        level().playSound(null, q.x, q.y, q.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.5F, 0.8F);
        level().playSound(null, q.x, q.y, q.z, SoundEvents.EVOKER_CAST_SPELL, SoundSource.NEUTRAL, 1.0F, 1.4F);
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
        this.stateAge++;
        ServerLevel level = (ServerLevel) level();
        switch (state()) {
            case IDLE -> {
                if (isVehicle()) ejectPassengers();
            }
            case LOADING -> loadingTick(level);
            case CHARGE -> chargeTick(level);
            case RIDE -> rideTick(level);
            case FLIGHT -> flightTick(level);
            case FALL -> fallTick(level);
            case REFORM -> reformTick(level);
            default -> setState(IDLE);
        }
        if (state() >= FLIGHT) sync();
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
        Vec3 to = riderFeet(S0);
        double hop = Math.sin(Math.PI * t) * (1.8D + this.jumpFrom.distanceTo(to) * 0.12D);
        Vec3 q = this.jumpFrom.lerp(to, e).add(0.0D, hop, 0.0D);
        m.setPos(q.x, q.y, q.z);
        m.setDeltaMovement(Vec3.ZERO);
        m.fallDistance = 0.0F;
        if (m instanceof Mob mob) mob.getNavigation().stop();
        Vec3 c = q.add(0.0D, m.getBbHeight() * 0.5D, 0.0D);
        level.sendParticles(WheelEntity.dust(PALETTE[this.stateAge % PALETTE.length], 1.1F), c.x, c.y, c.z, 3, 0.3D, 0.3D, 0.3D, 0.0D);
        if (t >= 1.0F) {
            m.startRiding(this, true);
            setState(CHARGE);
            Vec3 s = riderFeet(S0);
            level.playSound(null, s.x, s.y, s.z, SoundEvents.AMETHYST_BLOCK_PLACE, SoundSource.NEUTRAL, 1.4F, 0.7F);
            level.playSound(null, s.x, s.y, s.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.NEUTRAL, 1.0F, 1.2F);
        }
    }

    private void chargeTick(ServerLevel level) {
        if (!riderValid()) {
            dropRider();
            setState(IDLE);
            return;
        }
        keepRider(S0);
        int a = this.stateAge;
        Vec3 c = riderFeet(S0).add(0.0D, 0.6D, 0.0D);
        if (a == 2) play(level, c, SoundEvents.BEACON_AMBIENT, 1.4F, 0.8F);
        if (a % 6 == 0) play(level, c, SoundEvents.AMETHYST_BLOCK_CHIME, 1.2F, 0.6F + 1.2F * a / CHARGE_T);
        if (a % 4 == 0) {
            double s = Mth.clamp((a - 8.0D) / 30.0D * (L + 2.0D), 0.0D, L);
            Vec3 r = frame(s)[0];
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, r.x, r.y + 0.1D, r.z, 3, 0.3D, 0.1D, 0.3D, 0.05D);
        }
        // prism sparks swirling round the cart
        RandomSource rnd = level.random;
        for (int k = 0; k < 2; k++) {
            double ang = rnd.nextDouble() * Math.PI * 2.0D;
            double rad = 1.3D + rnd.nextDouble() * 0.6D;
            level.sendParticles(WheelEntity.dust(PALETTE[rnd.nextInt(PALETTE.length)], 0.9F), c.x + Math.cos(ang) * rad, c.y + rnd.nextDouble() * 1.4D - 0.4D,
                    c.z + Math.sin(ang) * rad, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        if (a % 5 == 0) level.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 2, 0.6D, 0.5D, 0.6D, 0.02D);
        if (a >= CHARGE_T) {
            setState(RIDE);
            play(level, c, SoundEvents.BEACON_ACTIVATE, 1.6F, 1.4F);
            play(level, c, SoundEvents.FIREWORK_ROCKET_LAUNCH, 1.4F, 0.6F);
            play(level, c, SoundEvents.TRIDENT_RIPTIDE_2.value(), 1.4F, 1.0F);
            level.sendParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    private void keepRider(double s) {
        LivingEntity m = this.rider;
        if (m.getVehicle() != this) {
            Vec3 q = riderFeet(s);
            m.setPos(q.x, q.y, q.z);
            m.setDeltaMovement(Vec3.ZERO);
        }
        m.fallDistance = 0.0F;
        if (m instanceof Mob mob) mob.getNavigation().stop();
    }

    private void rideTick(ServerLevel level) {
        boolean has = riderValid();
        if (!has && this.rider != null) dropRider();
        double s = cartSAt(this.stateAge);
        if (has) keepRider(s);
        Vec3[] f = frame(s);
        if (this.stateAge % 2 == 0) {
            for (int sd = -1; sd <= 1; sd += 2) {
                Vec3 w = f[0].add(f[2].scale(sd * GAUGE));
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, w.x, w.y, w.z, 1, 0.05D, 0.05D, 0.05D, 0.08D);
            }
            level.sendParticles(WheelEntity.dust(PALETTE[this.stateAge % PALETTE.length], 1.2F), f[0].x, f[0].y + 0.5D, f[0].z, 2, 0.3D, 0.3D, 0.3D, 0.0D);
        }
        if (this.stateAge % 8 == 0) play(level, f[0], SoundEvents.AMETHYST_BLOCK_RESONATE, 1.2F, 0.8F + this.stateAge / (float) RIDE_T);
        if (this.stateAge % 10 == 5) play(level, f[0], SoundEvents.MINECART_INSIDE, 0.5F, 1.4F);
        if (this.stateAge >= RIDE_T) split(level);
    }

    /** The rails end: the cart bursts into its crystals, all keeping its speed; the NPC flies on as a ragdoll. */
    private void split(ServerLevel level) {
        Vec3 vel = dirToWorld(trackDeriv(L)).scale(endSpeed());   // blocks per tick
        Vec3[] f = frame(L);
        Vec3 centre = f[0].add(f[3].scale(0.6D));
        RandomSource r = level.random;
        for (int i = 0; i < N; i++) {
            this.p[i] = slotWorld(i, L);
            this.axis[i] = slotAxis(i, L);
            Vec3 out = this.p[i].subtract(centre);
            out = out.lengthSqr() < 1.0E-4D ? Vec3.ZERO : out.normalize();
            this.v[i] = vel.add(out.scale(0.06D + r.nextDouble() * 0.05D)).add(r.nextGaussian() * 0.03D, r.nextGaussian() * 0.03D, r.nextGaussian() * 0.03D);
            this.beam[i] = 0.0F;
            this.beamTo[i] = this.p[i];
            this.landed[i] = false;
        }
        this.charging = -1;
        this.nextBeam = 14;
        this.sc = this.sv = null;
        this.flyer = null;
        this.rigidDone = true;
        LivingEntity m = this.rider;
        this.rider = null;
        this.entityData.set(DATA_RIDER, -1);
        if (m != null && m.isAlive() && !m.isRemoved()) {
            if (m.getVehicle() == this) m.stopRiding();
            m.setNoGravity(this.riderNoGravity);
            Vec3 c = f[0].add(f[3].scale(FEET + m.getBbHeight() * 0.5D));
            m.setPos(c.x, c.y - m.getBbHeight() * 0.5D, c.z);
            this.launchVel = vel.scale(20.0D);
            Vec3 axisSpin = f[2].scale(-1.5D);
            this.launchSpin = axisSpin.add(r.nextGaussian() * 0.6D, r.nextGaussian() * 0.6D, r.nextGaussian() * 0.6D);
            ServerPlayer player = this.shooter != null ? level.getServer().getPlayerList().getPlayer(this.shooter) : null;
            BallistaServer.launch(level, player, m, this.launchVel, this.launchSpin, false);
            this.flyer = m;
            this.ragdoll = WheelRagdolls.isRagdolled(m) || WheelRagdolls.isPending(m);
            this.rigidDone = false;
            this.lastC = c;
        }
        play(level, centre, SoundEvents.AMETHYST_CLUSTER_BREAK, 2.0F, 0.7F);
        play(level, centre, SoundEvents.GLASS_BREAK, 1.6F, 0.8F);
        play(level, centre, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1.8F, 0.8F);
        play(level, centre, SoundEvents.BEACON_DEACTIVATE, 1.4F, 1.6F);
        level.sendParticles(ParticleTypes.FLASH, centre.x, centre.y, centre.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.END_ROD, centre.x, centre.y, centre.z, 30, 0.4D, 0.4D, 0.4D, 0.25D);
        for (int k = 0; k < 30; k++) {
            level.sendParticles(WheelEntity.dust(PALETTE[k % PALETTE.length], 1.5F), centre.x, centre.y, centre.z, 1, 0.6D, 0.6D, 0.6D, 0.0D);
        }
        setState(FLIGHT);
    }

    // ---------------------------------------------------------------- the flight: crystals steering the body

    private Vec3 bodyCentre(LivingEntity m) {
        Vec3 c = WheelRagdolls.rootPosition(m);
        if (c == null) c = WheelRagdolls.isRagdolled(m) && this.lastC != null ? this.lastC : m.position().add(0.0D, m.getBbHeight() * 0.5D, 0.0D);
        if (this.lastC != null && c.distanceTo(this.lastC) > 8.0D) c = this.lastC;
        return c;
    }

    /**
     * After the split: every crystal flies its own way with the speed it got from the cart and falls freely; the
     * NPC flies on as a ragdoll on its own. Once all the crystals lie on the ground they go back and rebuild the cart.
     */
    private void flightTick(ServerLevel level) {
        LivingEntity m = this.flyer;
        boolean alive = m != null && m.isAlive() && !m.isRemoved() && m.level() == level();
        if (alive && !this.rigidDone && WheelRagdolls.isRagdolled(m)) {
            this.rigidDone = WheelRagdolls.throwRigid(m, this.launchVel, this.launchSpin);
            this.ragdoll = true;
        }
        if (alive && this.stateAge < 50) {
            // a short trail of prism dust behind the body
            Vec3 c = bodyCentre(m);
            this.lastC = c;
            level.sendParticles(WheelEntity.dust(PALETTE[this.stateAge % PALETTE.length], 1.3F), c.x, c.y, c.z, 2, 0.15D, 0.15D, 0.15D, 0.0D);
            if (this.stateAge % 3 == 0) level.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 1, 0.1D, 0.1D, 0.1D, 0.01D);
        }
        boolean all = true;
        for (int i = 0; i < N; i++) {
            if (this.landed[i]) continue;
            all = false;
            fallStep(level, i);
        }
        if (all || this.stateAge > 140) startReform(level);
    }

    private void fallTick(ServerLevel level) {
        boolean all = true;
        for (int i = 0; i < N; i++) {
            this.beam[i] *= 0.72F;
            if (this.beam[i] < 0.04F) this.beam[i] = 0.0F;
            if (this.landed[i]) continue;
            all = false;
            fallStep(level, i);
        }
        if (all || this.stateAge > FALL_MAX) startReform(level);
    }

    /** A loose crystal falls (keeping its speed), tumbling, and lies where it lands. */
    private void fallStep(ServerLevel level, int i) {
        if (this.landed[i]) return;
        {
            this.v[i] = this.v[i].add(0.0D, -0.05D, 0.0D).scale(0.985D);
            Vec3 next = this.p[i].add(this.v[i]);
            BlockPos bp = BlockPos.containing(next);
            VoxelShape s = level.getBlockState(bp).getCollisionShape(level, bp);
            if (!s.isEmpty() || next.y < level.getMinBuildHeight()) {
                double top = s.isEmpty() ? next.y : bp.getY() + s.max(Direction.Axis.Y);
                this.p[i] = new Vec3(next.x, top + SLOT_RAD[i] + 0.02D, next.z);
                Vec3 h = new Vec3(this.axis[i].x, 0.0D, this.axis[i].z);
                this.axis[i] = h.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : h.normalize();
                this.v[i] = Vec3.ZERO;
                this.landed[i] = true;
                if (i % 4 == 0) play(level, this.p[i], SoundEvents.AMETHYST_BLOCK_FALL, 1.0F, 0.8F + i * 0.03F);
            } else {
                this.p[i] = next;
                Vec3 spin = new Vec3(Math.sin(i * 1.7D), Math.cos(i * 2.3D), Math.sin(i * 3.1D)).normalize();
                this.axis[i] = this.axis[i].add(this.axis[i].cross(spin).scale(0.12D)).normalize();
            }
        }
    }

    private void startReform(ServerLevel level) {
        for (int i = 0; i < N; i++) {
            this.from[i] = this.p[i];
            this.axisFrom[i] = this.axis[i];
            this.beam[i] = 0.0F;
        }
        Vec3 s = frame(S0)[0];
        play(level, s, SoundEvents.BEACON_POWER_SELECT, 1.2F, 0.7F);
        setState(REFORM);
    }

    private void reformTick(ServerLevel level) {
        double a = this.stateAge;
        for (int i = 0; i < N; i++) {
            double u = Mth.clamp((a - i * 1.0D) / (REFORM_T - 26.0D), 0.0D, 1.0D);
            double e = u * u * (3.0D - 2.0D * u);
            Vec3 to = slotWorld(i, S0);
            double lift = Math.sin(Math.PI * e) * (2.5D + this.from[i].distanceTo(to) * 0.12D);
            this.p[i] = this.from[i].lerp(to, e).add(0.0D, lift, 0.0D);
            this.axis[i] = this.axisFrom[i].lerp(slotAxis(i, S0), e);
            if (this.axis[i].lengthSqr() < 1.0E-4D) this.axis[i] = slotAxis(i, S0);
            this.axis[i] = this.axis[i].normalize();
            if (u >= 1.0D && a - i * 1.0D - (REFORM_T - 26.0D) < 1.0D) {
                if (i % 3 == 0) play(level, to, SoundEvents.AMETHYST_BLOCK_PLACE, 0.9F, 1.0F + i * 0.04F);
            }
        }
        if (a % 10 == 0) {
            Vec3 s = frame(S0)[0];
            level.sendParticles(ParticleTypes.END_ROD, s.x, s.y + 0.6D, s.z, 2, 0.5D, 0.4D, 0.5D, 0.01D);
        }
        if (a >= REFORM_T) {
            Vec3 s = frame(S0)[0];
            play(level, s, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.4F, 1.2F);
            play(level, s, SoundEvents.BEACON_ACTIVATE, 0.8F, 1.6F);
            setState(IDLE);
        }
    }

    private void sync() {
        float[] d = new float[N * FLOATS];
        for (int i = 0; i < N; i++) {
            int o = i * FLOATS;
            Vec3 q = this.p[i] != null ? this.p[i] : position(), ax = this.axis[i] != null ? this.axis[i] : new Vec3(0.0D, 1.0D, 0.0D);
            Vec3 bt = this.beamTo[i] != null ? this.beamTo[i] : q;
            d[o] = (float) q.x;
            d[o + 1] = (float) q.y;
            d[o + 2] = (float) q.z;
            d[o + 3] = (float) ax.x;
            d[o + 4] = (float) ax.y;
            d[o + 5] = (float) ax.z;
            d[o + 6] = this.beam[i];
            d[o + 7] = (float) bt.x;
            d[o + 8] = (float) bt.y;
            d[o + 9] = (float) bt.z;
            d[o + 10] = this.beamCol[i];
        }
        PacketDistributor.sendToPlayersTrackingEntity(this, new PrismSync(getId(), state(), d));
    }

    private void play(ServerLevel level, Vec3 at, SoundEvent s, float vol, float pitch) {
        level.playSound(null, at.x, at.y, at.z, s, SoundSource.NEUTRAL, vol, pitch);
    }

    // ---------------------------------------------------------------- rider

    @Override
    protected void positionRider(Entity passenger, MoveFunction move) {
        if (!hasPassenger(passenger)) return;
        Vec3 s = riderFeet(cartS(0.0F));
        move.accept(passenger, s.x, s.y, s.z);
        Vec3 t = dirToWorld(trackDeriv(cartS(0.0F)));
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
        Vec3 c = frame(S0)[0].add(0.0D, 1.0D, 0.0D);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.NEUTRAL, 1.4F, 0.8F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.NEUTRAL, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 40, 2.0D, 1.5D, 2.0D, 0.04D);
        for (int i = 0; i < 30; i++) {
            level.sendParticles(WheelEntity.dust(PALETTE[i % PALETTE.length], 1.2F), c.x, c.y, c.z, 1, 2.0D, 1.5D, 2.0D, 0.0D);
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
        return getBoundingBox().inflate(L + 6.0D, 20.0D, L + 6.0D);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    // ---------------------------------------------------------------- client

    /** Client: the crystals' state from the server (PrismSync). */
    public void onSync(int st, float[] data) {
        if (data.length != N * FLOATS) return;
        this.syncPrev = this.syncCur != null && this.syncState >= FLIGHT ? this.syncCur : data;
        this.syncCur = data;
        this.syncState = st;
    }

    /** Client: the free crystals, interpolated (null while the cart is whole). */
    public float[] freeCrystals(float partial) {
        int s = viewState();
        if (s < FLIGHT || this.syncCur == null) return null;
        float[] out = new float[N * FLOATS];
        float[] a = this.syncPrev != null ? this.syncPrev : this.syncCur, b = this.syncCur;
        for (int k = 0; k < out.length; k++) {
            int f = k % FLOATS;
            out[k] = f == 6 || f == 10 ? b[k] : (f >= 7 && f <= 9 ? b[k] : Mth.lerp(partial, a[k], b[k]));
        }
        return out;
    }

    private void clientTick() {
        int s = state();
        if (s != this.stateC) {
            this.stateC = s;
            this.ageC = 0;
            if (s < FLIGHT) {
                this.syncPrev = this.syncCur = null;
                this.syncState = -1;
            }
        } else {
            this.ageC++;
        }
        int id = this.entityData.get(DATA_RIDER);
        if (id >= 0 && (s == CHARGE || s == RIDE)) CLIENT_RIDERS.put(id, this);
        CLIENT_RIDERS.values().removeIf(r -> r == this && ((r.viewState() != CHARGE && r.viewState() != RIDE) || this.entityData.get(DATA_RIDER) < 0));
        PrismFx.ambient(this);
    }
}
