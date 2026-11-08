package com.example.wheelofwonder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

/**
 * The rocket car that broke off the ride, with the NPC on it. The server flies it (thrust, then a ballistic arc),
 * and steers the NPC's ragdoll torso to the seat on top every tick, so the NPC rides it with flailing limbs.
 * At the first block it hits it bursts into pieces and is gone; the ragdoll is thrown on, the camera stays on it.
 * Not saved: a flight lasts seconds.
 */
public class RocketEntity extends Entity {
    private static final int THRUST_TICKS = 36, MAX_TICKS = 300;
    public static final double SEAT_UP = RocketRideEntity.SEAT_UP;

    private static final EntityDataAccessor<Vector3f> DATA_DIR = SynchedEntityData.defineId(RocketEntity.class, EntityDataSerializers.VECTOR3);

    /** How far below the launch height the arc ends (then it keeps diving until it hits something). */
    private static final double ARC_DROP = 3.0D;

    // ---- server ----
    private LivingEntity rider;
    private boolean riderNoGravity;
    private Vec3 vel = Vec3.ZERO;
    private int age;
    // the arc: from start along dirH, `dist` far and `height` high, in `arcTicks`
    private Vec3 start = Vec3.ZERO, dirH = new Vec3(1.0D, 0.0D, 0.0D);
    private double dist, height;
    private int arcTicks = 80;

    // ---- client ----
    private int lerpSteps;
    private double lerpX, lerpY, lerpZ;

    public RocketEntity(EntityType<? extends RocketEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.noCulling = true;
    }

    /**
     * Launches a rocket from {@code pos} towards the horizontal direction {@code dirH}. The flight is a fixed arc
     * (config: distance, height, time), so it always comes down within range, then dives until it hits a block.
     */
    static RocketEntity spawn(ServerLevel level, Vec3 pos, Vec3 dirH, LivingEntity rider, boolean riderNoGravity) {
        RocketEntity r = new RocketEntity(WheelMod.ROCKET.get(), level);
        r.setPos(pos.x, pos.y, pos.z);
        r.xo = r.xOld = pos.x;
        r.yo = r.yOld = pos.y;
        r.zo = r.zOld = pos.z;
        r.start = pos;
        r.dirH = dirH;
        r.dist = WheelConfig.ROCKET_DISTANCE.get();
        r.height = WheelConfig.ROCKET_HEIGHT.get();
        r.arcTicks = Math.max(20, (int) Math.round(WheelConfig.ROCKET_FLIGHT_TIME.get() * 20.0D));
        r.vel = r.arcVelocity(0.0D);
        r.rider = rider;
        r.riderNoGravity = riderNoGravity;
        Vec3 d = r.vel.normalize();
        r.entityData.set(DATA_DIR, new Vector3f((float) d.x, (float) d.y, (float) d.z));
        level.addFreshEntity(r);
        return r;
    }

    /** Current velocity, m/s. */
    Vec3 velocity() {
        return this.vel;
    }

    private Vec3 arcPoint(double t) {
        double up = 4.0D * this.height * t * (1.0D - t) - ARC_DROP * t;
        return this.start.add(this.dirH.scale(this.dist * t)).add(0.0D, up, 0.0D);
    }

    private Vec3 arcVelocity(double t) {
        double perSecond = 20.0D / this.arcTicks;
        double up = 4.0D * this.height * (1.0D - 2.0D * t) - ARC_DROP;
        return this.dirH.scale(this.dist * perSecond).add(0.0D, up * perSecond, 0.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(DATA_DIR, new Vector3f(0.0F, 0.0F, 1.0F));
    }

    /** Nose direction (unit). */
    public Vector3f dir() {
        return this.entityData.get(DATA_DIR);
    }

    public boolean thrusting() {
        return this.tickCount < THRUST_TICKS;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            clientTick();
            return;
        }
        ServerLevel level = (ServerLevel) level();
        this.age++;
        double dt = 0.05D;
        if (this.age < THRUST_TICKS && this.age % 6 == 0) {
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, 0.6F, 0.5F + this.random.nextFloat() * 0.3F);
        }
        Vec3 pos = position();
        Vec3 next;
        if (this.age <= this.arcTicks) {
            double t = this.age / (double) this.arcTicks;
            next = arcPoint(t);
            this.vel = arcVelocity(t);
        } else {
            // past the arc: keeps diving (with gravity) until it hits the ground
            this.vel = this.vel.add(0.0D, -9.8D * dt, 0.0D);
            next = pos.add(this.vel.scale(dt));
        }
        // keep the chunks here simulated while it flies (it may be far from the player)
        WheelMod.keepLoaded(level, next);
        Vec3 hit = sweep(level, pos, next);
        if (hit != null || this.age > MAX_TICKS || next.y < this.start.y - 96.0D || next.y < level.getMinBuildHeight() - 10) {
            impact(level, hit != null ? hit : pos);
            return;
        }
        setPos(next.x, next.y, next.z);
        Vec3 nd = this.vel.normalize();
        this.entityData.set(DATA_DIR, new Vector3f((float) nd.x, (float) nd.y, (float) nd.z));
        carryRider(next);
    }

    /** First point on the way from a to b that is inside a block's collision box, or null. */
    private static Vec3 sweep(ServerLevel level, Vec3 a, Vec3 b) {
        Vec3 d = b.subtract(a);
        double len = d.length();
        int steps = Math.max(1, (int) Math.ceil(len / 0.25D));
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= steps; i++) {
            Vec3 q = a.add(d.scale(i / (double) steps));
            bp.set(q.x, q.y, q.z);
            if (!level.isLoaded(bp)) return q;
            BlockState st = level.getBlockState(bp);
            if (st.isAir()) continue;
            VoxelShape shape = st.getCollisionShape(level, bp);
            if (!shape.isEmpty() && shape.bounds().move(bp).inflate(0.15D).contains(q)) return q;
        }
        return null;
    }

    /** The NPC's torso is held on the seat; before Sable has built the body, the entity itself is carried. */
    private void carryRider(Vec3 p) {
        LivingEntity m = this.rider;
        if (m == null || m.isRemoved() || !m.isAlive()) return;
        Vec3 seat = p.add(0.0D, SEAT_UP, 0.0D);
        if (WheelRagdolls.isRagdolled(m)) {
            WheelRagdolls.steerRootFast(m, seat, this.vel, 70.0D, 16.0D);
        } else {
            m.setNoGravity(true);
            m.setPos(seat.x, seat.y - m.getBbHeight() * 0.5D, seat.z);
            m.setDeltaMovement(Vec3.ZERO);
            m.fallDistance = 0.0F;
        }
    }

    /** Burst: pieces fly, the ragdoll is thrown on, the rocket is gone. */
    private void impact(ServerLevel level, Vec3 at) {
        RandomSource r = this.random;
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, 1.6F, 1.1F);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.NEUTRAL, 1.6F, 0.6F);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL, 0.6F, 0.7F);
        level.sendParticles(ParticleTypes.FLASH, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 3, 0.5D, 0.3D, 0.5D, 0.0D);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 20, 0.6D, 0.4D, 0.6D, 0.05D);
        level.sendParticles(ParticleTypes.LAVA, at.x, at.y, at.z, 8, 0.3D, 0.2D, 0.3D, 0.0D);
        for (int i = 0; i < 30; i++) {
            Vec3 v = new Vec3(r.nextGaussian(), Math.abs(r.nextGaussian()) + 0.4D, r.nextGaussian()).normalize().scale(0.3D + r.nextDouble() * 0.5D);
            int col = WheelEntity.BULB_COLORS[i % WheelEntity.BULB_COLORS.length];
            level.sendParticles(i % 3 == 0 ? ParticleTypes.SOUL_FIRE_FLAME : WheelEntity.dust(col, 1.4F), at.x, at.y, at.z, 0, v.x, v.y, v.z, 1.0D);
        }
        // pieces: hull plates, fins, the nose cone and the tail lamps
        Vec3 back = this.vel.scale(0.012D);
        int[] kinds = {WheelPieceEntity.HULL, WheelPieceEntity.HULL, WheelPieceEntity.HULL, WheelPieceEntity.HULL, WheelPieceEntity.HULL,
                WheelPieceEntity.FIN, WheelPieceEntity.FIN, WheelPieceEntity.FIN, WheelPieceEntity.NOSE, WheelPieceEntity.BULB, WheelPieceEntity.BULB};
        Vec3 from = at.subtract(this.vel.normalize().scale(0.4D)).add(0.0D, 0.3D, 0.0D);
        for (int i = 0; i < kinds.length; i++) {
            WheelPieceEntity p = new WheelPieceEntity(WheelMod.PIECE.get(), level);
            Vec3 v = new Vec3(r.nextGaussian() * 0.25D, 0.25D + r.nextDouble() * 0.35D, r.nextGaussian() * 0.25D).add(back);
            p.setup(kinds[i], kinds[i] == WheelPieceEntity.BULB ? 0 : i, from, v);
            level.addFreshEntity(p);
        }
        // the NPC flies on, tumbling
        LivingEntity m = this.rider;
        this.rider = null;
        if (m != null && !m.isRemoved() && m.isAlive()) {
            Vec3 on = this.vel.scale(0.4D).add(0.0D, 4.0D, 0.0D);
            if (WheelRagdolls.isRagdolled(m)) {
                Vec3 side = new Vec3(0.0D, 1.0D, 0.0D).cross(this.vel.multiply(1.0D, 0.0D, 1.0D));
                Vec3 spin = side.lengthSqr() < 1.0E-4D ? new Vec3(3.0D, 0.0D, 0.0D) : side.normalize().scale(-3.5D);
                WheelRagdolls.throwRigid(m, on, spin.add(0.0D, (r.nextDouble() - 0.5D) * 2.0D, 0.0D));
            } else {
                m.setNoGravity(this.riderNoGravity);
                m.setDeltaMovement(on.scale(1.0D / 20.0D));
                m.hurtMarked = true;
            }
        }
        discard();
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide() && this.rider != null && !this.rider.isRemoved() && !WheelRagdolls.isRagdolled(this.rider)) {
            this.rider.setNoGravity(this.riderNoGravity);
        }
        this.rider = null;
        super.remove(reason);
    }

    // ---------------------------------------------------------------- client

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpSteps = Math.max(2, steps);
    }

    @Override
    public double lerpTargetX() {
        return this.lerpSteps > 0 ? this.lerpX : getX();
    }

    @Override
    public double lerpTargetY() {
        return this.lerpSteps > 0 ? this.lerpY : getY();
    }

    @Override
    public double lerpTargetZ() {
        return this.lerpSteps > 0 ? this.lerpZ : getZ();
    }

    private void clientTick() {
        if (this.lerpSteps > 0) {
            double f = 1.0D / this.lerpSteps;
            setPos(getX() + (this.lerpX - getX()) * f, getY() + (this.lerpY - getY()) * f, getZ() + (this.lerpZ - getZ()) * f);
            this.lerpSteps--;
        }
        RocketFx.flight(this);
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
    public boolean shouldRenderAtSqrDistance(double d) {
        return d < 256.0D * 256.0D;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
