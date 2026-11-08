package com.example.wheelofwonder;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.Tags;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The thrown wheel. All gameplay runs on the server:
 * ROLL  - rolls on the ground to the target point (NPCs near its path are already mesmerized),
 * RISE  - stops and lifts off,
 * HOVER - levitates and spins, pulls every NPC in range; an NPC that gets close becomes a Sable ragdoll whose torso
 *         is pinned to a spot on the wheel face and spins with it. 4 s without a catch, or 6 s after the first catch: boom,
 * BLOWN - explosion: pieces and ragdolls fly away; the invisible entity lives a moment longer for the camera.
 *
 * The entity position is the lowest point of the wheel (where it touches the ground while rolling).
 * The wheel stands upright; its plane contains the travel direction, its axle is horizontal.
 */
public class WheelEntity extends Entity {
    public static final float RADIUS = 1.5F;
    public static final float THICK = 0.38F;
    /** Tag of NPCs that are currently mesmerized/held by a wheel (no fall damage). Removed when they are released. */
    public static final String PULL_TAG = "wheelofwonder_pulled";

    public static final int ROLL = 0, RISE = 1, HOVER = 2, BLOWN = 3;
    private static final int RISE_TICKS = 18;
    private static final int BLOWN_TICKS = 50;
    private static final double HOVER_SPIN = 0.17D; // rad per tick (~0.55 turns per second)

    private static final EntityDataAccessor<Integer> DATA_PHASE = SynchedEntityData.defineId(WheelEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_ANGLE = SynchedEntityData.defineId(WheelEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SPIN = SynchedEntityData.defineId(WheelEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_FUSE = SynchedEntityData.defineId(WheelEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_CAUGHT = SynchedEntityData.defineId(WheelEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER = SynchedEntityData.defineId(WheelEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    /** Bulb colours, shared with the renderer and the particles. */
    public static final int[] BULB_COLORS = {0xFF3B3B, 0xFFD93B, 0x45FF6A, 0x3BB8FF, 0xC84BFF, 0xFF8A1F};

    private static final int[] MELODY = {12, 16, 19, 24, 23, 19, 16, 12, 14, 17, 21, 23, 21, 17, 14, 11,
            12, 16, 19, 24, 23, 21, 19, 17, 16, 14, 12, 11, 12, -1, 12, -1};

    private static final class Victim {
        final LivingEntity mob;
        final boolean hadNoGravity;
        int slot = -1;
        int launchAge;
        boolean ragdoll;
        boolean fallback;

        Victim(LivingEntity mob) {
            this.mob = mob;
            this.hadNoGravity = mob.isNoGravity();
        }
    }

    // ---- server state ----
    private Vec3 target = Vec3.ZERO;
    private double baseY;
    private int phaseTicks;
    private int rollTicks;
    private double spin;
    private double spinRate;
    private int firstCatch = -1;
    private int nextSlot;
    private double lastClickSpin;
    private final Map<UUID, Victim> victims = new LinkedHashMap<>();

    // ---- client state ----
    private float spinC, prevSpinC;
    private int prevPhaseC = -1;

    public WheelEntity(EntityType<? extends WheelEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.noCulling = true;
    }

    public static WheelEntity create(ServerLevel level, Player owner, Vec3 start, Vec3 target) {
        WheelEntity w = new WheelEntity(WheelMod.WHEEL.get(), level);
        float angle = (float) Math.atan2(target.z - start.z, target.x - start.x);
        w.entityData.set(DATA_ANGLE, angle);
        w.entityData.set(DATA_OWNER, Optional.of(owner.getUUID()));
        w.target = target;
        w.setPos(start.x, start.y, start.z);
        w.xo = w.xOld = start.x;
        w.yo = w.yOld = start.y;
        w.zo = w.zOld = start.z;
        return w;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(DATA_PHASE, ROLL);
        b.define(DATA_ANGLE, 0.0F);
        b.define(DATA_SPIN, 0.0F);
        b.define(DATA_FUSE, -1);
        b.define(DATA_CAUGHT, 0);
        b.define(DATA_OWNER, Optional.empty());
    }

    // ---------------------------------------------------------------- geometry

    public int phase() {
        return this.entityData.get(DATA_PHASE);
    }

    public float travelAngle() {
        return this.entityData.get(DATA_ANGLE);
    }

    /** Ticks until the explosion, or -1 while no countdown runs. */
    public int fuse() {
        return this.entityData.get(DATA_FUSE);
    }

    public int caught() {
        return this.entityData.get(DATA_CAUGHT);
    }

    public Optional<UUID> owner() {
        return this.entityData.get(DATA_OWNER);
    }

    /** Client: interpolated spin angle (radians) for drawing. */
    public float spin(float partial) {
        return Mth.lerp(partial, this.prevSpinC, this.spinC);
    }

    public Vec3 dir() {
        float a = travelAngle();
        return new Vec3(Math.cos(a), 0.0D, Math.sin(a));
    }

    /** Horizontal axle of the wheel (the normal of its faces). */
    public Vec3 axle() {
        float a = travelAngle();
        return new Vec3(-Math.sin(a), 0.0D, Math.cos(a));
    }

    public Vec3 center() {
        return position().add(0.0D, RADIUS, 0.0D);
    }

    /** Point in the wheel's own frame (x along travel, y up, z along axle, rotated by the spin) to world space. */
    private Vec3 toWorld(double lx, double ly, double lz) {
        double s = Math.sin(-this.spin), c = Math.cos(-this.spin);
        double rx = lx * c - ly * s, ry = lx * s + ly * c;
        Vec3 d = dir(), ax = axle();
        Vec3 cen = center();
        return new Vec3(cen.x + d.x * rx + ax.x * lz, cen.y + ry, cen.z + d.z * rx + ax.z * lz);
    }

    /** Spot on the wheel face where caught NPC number i is pinned: golden-angle spread, alternating faces. */
    private Vec3 slotPos(int i) {
        double phi = i * 2.39996D + 0.6D;
        double r = RADIUS * (i < 6 ? 0.62D : 0.42D + 0.12D * ((i * 0.618D) % 1.0D));
        double side = (i % 2 == 0) ? 1.0D : -1.0D;
        double z = side * (THICK * 0.5D + 0.42D);
        return toWorld(Math.cos(phi) * r, Math.sin(phi) * r, z);
    }

    /** Spin of the wheel as an angular velocity vector, rad/s. */
    private Vec3 omega() {
        return axle().scale(-this.spinRate * 20.0D);
    }

    /** Velocity (m/s) of a point that turns with the wheel. */
    private Vec3 pointVelocity(Vec3 p) {
        return omega().cross(p.subtract(center()));
    }

    // ---------------------------------------------------------------- tick

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            clientTick();
            return;
        }
        ServerLevel level = (ServerLevel) level();
        this.phaseTicks++;
        switch (phase()) {
            case ROLL -> tickRoll(level);
            case RISE -> tickRise(level);
            case HOVER -> tickHover(level);
            default -> {
                if (this.phaseTicks > BLOWN_TICKS) discard();
            }
        }
        this.entityData.set(DATA_SPIN, (float) this.spin);
        ratchetClicks(level);
    }

    private void tickRoll(ServerLevel level) {
        Vec3 d = dir();
        double remain = (this.target.x - getX()) * d.x + (this.target.z - getZ()) * d.z;
        this.rollTicks++;
        if (remain <= 0.08D || this.rollTicks > 20 * 10) {
            startRise(level);
            return;
        }
        double speed = Math.min(WheelConfig.ROLL_SPEED.get(), Math.max(1.4D, remain * 2.4D)) / 20.0D;
        double step = Math.min(speed, remain);
        double nx = getX() + d.x * step, nz = getZ() + d.z * step;
        Double ground = findGround(level, nx, getY(), nz);
        if (ground == null) {
            // wall in front or a deep drop: stop here
            startRise(level);
            return;
        }
        setPos(nx, ground, nz);
        this.spinRate = step / RADIUS;
        this.spin += this.spinRate;

        if (this.tickCount % 5 == 0) mesmerize(level, WheelConfig.PATH_RADIUS.get());
        updateVictims(level);
        if (this.tickCount % 2 == 0) {
            RandomSource r = this.random;
            Vec3 back = position().subtract(d.scale(0.6D));
            level.sendParticles(ParticleTypes.CRIT, back.x, back.y + 0.1D, back.z, 2, 0.15D, 0.05D, 0.15D, 0.15D);
            int col = BULB_COLORS[r.nextInt(BULB_COLORS.length)];
            level.sendParticles(dust(col, 1.2F), back.x, back.y + 0.2D, back.z, 2, 0.25D, 0.1D, 0.25D, 0.0D);
        }
    }

    private void startRise(ServerLevel level) {
        this.entityData.set(DATA_PHASE, RISE);
        this.phaseTicks = 0;
        this.baseY = getY();
        Vec3 c = center();
        level.playSound(null, c.x, c.y, c.z, SoundEvents.ILLUSIONER_PREPARE_MIRROR, SoundSource.PLAYERS, 1.6F, 0.8F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 1.4F, 1.5F);
        level.sendParticles(ParticleTypes.CLOUD, getX(), getY() + 0.1D, getZ(), 18, 0.6D, 0.05D, 0.6D, 0.04D);
    }

    private void tickRise(ServerLevel level) {
        float t = Math.min(1.0F, this.phaseTicks / (float) RISE_TICKS);
        float ease = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
        setPos(getX(), this.baseY + WheelConfig.HOVER_HEIGHT.get() * ease, getZ());
        // the roll slows down, then the wheel starts turning on its own
        this.spinRate = this.spinRate * 0.85D + 0.02D * t;
        this.spin += this.spinRate;
        if (this.tickCount % 5 == 0) mesmerize(level, WheelConfig.PATH_RADIUS.get());
        updateVictims(level);
        if (this.phaseTicks >= RISE_TICKS) {
            this.entityData.set(DATA_PHASE, HOVER);
            this.phaseTicks = 0;
            Vec3 c = center();
            level.playSound(null, c.x, c.y, c.z, SoundEvents.EVOKER_PREPARE_WOLOLO, SoundSource.PLAYERS, 1.2F, 1.3F);
            mesmerize(level, WheelConfig.ATTRACT_RADIUS.get());
        }
    }

    private void tickHover(ServerLevel level) {
        double bob = Math.sin(this.tickCount * 0.12D) * 0.12D;
        setPos(getX(), this.baseY + WheelConfig.HOVER_HEIGHT.get() + bob, getZ());
        this.spinRate += Mth.clamp(HOVER_SPIN - this.spinRate, -0.006D, 0.006D);
        this.spin += this.spinRate;

        if (this.tickCount % 4 == 0) mesmerize(level, WheelConfig.ATTRACT_RADIUS.get());
        updateVictims(level);

        int fuse;
        if (this.firstCatch < 0) {
            fuse = (int) Math.round(WheelConfig.EMPTY_FUSE.get() * 20.0D) - this.phaseTicks;
        } else {
            fuse = (int) Math.round(WheelConfig.CAPTURE_FUSE.get() * 20.0D) - (this.tickCount - this.firstCatch);
        }
        this.entityData.set(DATA_FUSE, Math.max(0, fuse));
        hoverSounds(level, fuse);
        if (fuse <= 0) explode(level);
    }

    /** First solid surface under (x, z) the wheel can roll onto, or null for a wall / a drop deeper than 10 blocks. */
    private static Double findGround(ServerLevel level, double x, double y, double z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        // the body of the wheel must have room
        for (double h : new double[]{1.4D, 2.3D}) {
            p.set(Mth.floor(x), Mth.floor(y + h), Mth.floor(z));
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) return null;
        }
        int top = Mth.floor(y + 1.0D);
        for (int by = top; by >= Mth.floor(y) - 10; by--) {
            p.set(Mth.floor(x), by, Mth.floor(z));
            VoxelShape s = level.getBlockState(p).getCollisionShape(level, p);
            if (!s.isEmpty()) {
                double surface = by + s.max(Direction.Axis.Y);
                if (surface > y + 1.05D) return null;
                return surface;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- NPCs

    private boolean canTarget(LivingEntity e) {
        return e.isAlive() && !(e instanceof Player) && !(e instanceof ArmorStand) && !e.isSpectator() && !e.isInvisible()
                && !e.getType().is(Tags.EntityTypes.BOSSES) && !WheelMod.held(e)
                && !WheelRagdolls.isRagdolled(e);
    }

    /** Every NPC in range is mesmerized, whichever way it looks. */
    private void mesmerize(ServerLevel level, double radius) {
        if (radius <= 0.0D) return;
        Vec3 c = center();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(radius),
                e -> !this.victims.containsKey(e.getUUID()) && canTarget(e))) {
            if (e.distanceToSqr(c) > radius * radius) continue;
            if (e.isPassenger()) e.stopRiding();
            this.victims.put(e.getUUID(), new Victim(e));
            WheelMod.hold(e);
            if (e instanceof Mob m) m.getNavigation().stop();
            level.playSound(null, e.getX(), e.getEyeY(), e.getZ(), SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.HOSTILE, 0.7F, 1.4F + this.random.nextFloat() * 0.3F);
            level.sendParticles(ParticleTypes.WITCH, e.getX(), e.getEyeY() + 0.4D, e.getZ(), 10, 0.3D, 0.2D, 0.3D, 0.05D);
        }
    }

    private void updateVictims(ServerLevel level) {
        Vec3 c = center();
        double radius = WheelConfig.ATTRACT_RADIUS.get();
        Iterator<Victim> it = this.victims.values().iterator();
        while (it.hasNext()) {
            Victim v = it.next();
            LivingEntity mob = v.mob;
            if (mob.isRemoved() && !v.ragdoll || mob.level() != level) {
                restore(v);
                it.remove();
                continue;
            }
            if (v.slot >= 0) {
                hold(level, v);
                continue;
            }
            if (!mob.isAlive()) {
                restore(v);
                it.remove();
                continue;
            }
            Vec3 mc = mob.position().add(0.0D, mob.getBbHeight() * 0.5D, 0.0D);
            Vec3 to = c.subtract(mc);
            double d = to.length();
            double reach = RADIUS + 0.6D + mob.getBbWidth() * 0.5D;
            if (phase() == HOVER && d < reach) {
                capture(level, v);
                continue;
            }
            double base = WheelConfig.PULL_SPEED.get() / 20.0D;
            double speed = phase() == HOVER ? base * (1.0D + 1.3D * Math.max(0.0D, 1.0D - d / radius)) : base * 0.55D;
            Vec3 n = d > 1.0E-4D ? to.scale(1.0D / d) : Vec3.ZERO;
            // a slight spiral around the wheel: the NPCs drift in like hypnotized moths
            Vec3 swirl = n.cross(new Vec3(0.0D, 1.0D, 0.0D)).scale(speed * 0.35D * Math.min(1.0D, d / 4.0D));
            Vec3 vel = n.scale(Math.min(speed, Math.max(0.0D, d - reach * 0.8D))).add(swirl);
            mob.setDeltaMovement(vel);
            mob.hurtMarked = true;
            mob.setNoGravity(true);
            mob.fallDistance = 0.0F;
            float yaw = (float) (Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG) - 90.0F;
            mob.setYRot(yaw);
            mob.setYHeadRot(yaw);
            mob.setYBodyRot(yaw);
            if (mob instanceof Mob m) {
                m.getNavigation().stop();
                m.getLookControl().setLookAt(c.x, c.y, c.z);
            }
            if ((this.tickCount + mob.getId()) % 4 == 0) beam(level, mob.getEyePosition(), c);
            if ((this.tickCount + mob.getId()) % 7 == 0) {
                level.sendParticles(ParticleTypes.ENCHANT, mob.getX(), mob.getEyeY() + 0.5D, mob.getZ(), 6, 0.3D, 0.2D, 0.3D, 0.6D);
            }
        }
    }

    /** Hypnotic dotted beam from the NPC's eyes to the wheel. */
    private void beam(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        int n = Math.min(24, (int) (len / 0.7D));
        int col = BULB_COLORS[(this.tickCount / 4) % BULB_COLORS.length];
        for (int i = 1; i < n; i++) {
            Vec3 p = from.add(d.scale(i / (double) n));
            level.sendParticles(dust(i % 2 == 0 ? col : 0xB04BFF, 0.7F), p.x, p.y, p.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    private void capture(ServerLevel level, Victim v) {
        LivingEntity mob = v.mob;
        v.slot = this.nextSlot++;
        if (this.firstCatch < 0) this.firstCatch = this.tickCount;
        this.entityData.set(DATA_CAUGHT, this.entityData.get(DATA_CAUGHT) + 1);
        Vec3 slot = slotPos(v.slot);
        mob.setPos(slot.x, slot.y - mob.getBbHeight() * 0.5D, slot.z);
        mob.setDeltaMovement(Vec3.ZERO);
        mob.setNoGravity(true);
        if (mob instanceof Mob m) m.getNavigation().stop();

        level.playSound(null, slot.x, slot.y, slot.z, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.0F, 0.8F);
        level.playSound(null, slot.x, slot.y, slot.z, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0F, 1.6F + 0.1F * (v.slot % 4));
        level.sendParticles(ParticleTypes.FIREWORK, slot.x, slot.y, slot.z, 14, 0.2D, 0.2D, 0.2D, 0.15D);
        for (int col : BULB_COLORS) level.sendParticles(dust(col, 1.0F), slot.x, slot.y, slot.z, 3, 0.4D, 0.4D, 0.4D, 0.0D);

        if (WheelRagdolls.available()) {
            mob.addTag(WheelMod.RAGDOLL_TAG);
            if (WheelConfig.LIMP.get()) mob.addTag(WheelMod.KICKMOD_LIMP_TAG);
            int secs = WheelConfig.RAGDOLL_SECONDS.get();
            if (secs > 0) secs += (int) Math.ceil(WheelConfig.CAPTURE_FUSE.get());
            v.ragdoll = WheelRagdolls.launch(level, mob, pointVelocity(slot), omega(), secs);
            v.launchAge = this.tickCount;
            if (!v.ragdoll) {
                mob.removeTag(WheelMod.RAGDOLL_TAG);
                mob.removeTag(WheelMod.KICKMOD_LIMP_TAG);
                v.fallback = true;
            }
        } else {
            v.fallback = true;
        }
    }

    /** Keeps a caught NPC on its spot of the spinning face. */
    private void hold(ServerLevel level, Victim v) {
        LivingEntity mob = v.mob;
        Vec3 slot = slotPos(v.slot);
        if (v.ragdoll) {
            if (!WheelRagdolls.isRagdolled(mob)) {
                int age = this.tickCount - v.launchAge;
                if (age < 10 || (WheelRagdolls.isPending(mob) && age < 40)) {
                    // the client is still sending the model: keep the entity on its spot until the body exists
                    if (!mob.isRemoved()) holdEntity(mob, slot, false);
                    return;
                }
                LogUtils.getLogger().info("[wheelofwonder] no ragdoll body for {}, holding it as an entity", mob.getName().getString());
                v.ragdoll = false;
                v.fallback = true;
                mob.removeTag(WheelMod.RAGDOLL_TAG);
                mob.removeTag(WheelMod.KICKMOD_LIMP_TAG);
            } else {
                List<WheelRagdolls.Body> bodies = WheelRagdolls.bodies(mob);
                Vec3 root = WheelRagdolls.rootPosition(mob);
                // Right after the launch Sable may report the body far away in its storage area: skip those ticks.
                if (bodies.isEmpty() || root == null || root.distanceTo(slot) > 12.0D) return;
                Vec3 want = pointVelocity(slot).add(slot.subtract(root).scale(12.0D));
                if (want.length() > 30.0D) want = want.normalize().scale(30.0D);
                WheelRagdolls.setVelocity(bodies.get(0), want, omega());
                return;
            }
        }
        if (v.fallback && mob.isAlive()) holdEntity(mob, slot, true);
    }

    private static void holdEntity(LivingEntity mob, Vec3 slot, boolean twirl) {
        mob.setPos(slot.x, slot.y - mob.getBbHeight() * 0.5D, slot.z);
        mob.setDeltaMovement(Vec3.ZERO);
        mob.setNoGravity(true);
        mob.fallDistance = 0.0F;
        mob.hurtMarked = true;
        if (twirl) {
            float yaw = mob.getYRot() + 14.0F;
            mob.setYRot(yaw);
            mob.setYBodyRot(yaw);
            mob.setYHeadRot(yaw);
        }
        if (mob instanceof Mob m) m.getNavigation().stop();
    }

    private void restore(Victim v) {
        LivingEntity mob = v.mob;
        WheelMod.unhold(mob);
        if (!mob.isRemoved()) mob.setNoGravity(v.hadNoGravity);
    }

    // ---------------------------------------------------------------- explosion

    private void explode(ServerLevel level) {
        Vec3 c = center();
        this.entityData.set(DATA_PHASE, BLOWN);
        this.entityData.set(DATA_FUSE, 0);
        this.phaseTicks = 0;
        RandomSource r = this.random;

        level.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 3.0F, 1.15F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.PLAYERS, 3.0F, 0.9F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 2.5F, 1.0F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.WITCH_CELEBRATE, SoundSource.PLAYERS, 2.0F, 1.25F);
        // big flash for everyone around (the client adds the confetti and the rest when it sees the BLOWN phase)
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);

        spawnPieces(level, c);

        double blast = WheelConfig.BLAST_SPEED.get();
        List<LivingEntity> thrown = new ArrayList<>();
        // the owner's camera follows one of the NPCs that were stuck on the wheel (random), or any thrown one
        List<Victim> stuck = new ArrayList<>();
        for (Victim v : this.victims.values()) if (v.slot >= 0) stuck.add(v);
        Victim follow = !stuck.isEmpty() ? stuck.get(r.nextInt(stuck.size()))
                : (this.victims.isEmpty() ? null : new ArrayList<>(this.victims.values()).get(r.nextInt(this.victims.size())));
        if (follow != null && owner().isPresent()
                && level.getPlayerByUUID(owner().get()) instanceof net.minecraft.server.level.ServerPlayer sp) {
            Vec3 origin = WheelRagdolls.rootPosition(follow.mob);
            if (origin == null || origin.distanceTo(c) > 12.0D) {
                origin = follow.slot >= 0 ? slotPos(follow.slot) : follow.mob.position().add(0.0D, follow.mob.getBbHeight() * 0.5D, 0.0D);
            }
            FollowTracker.start(sp, follow.mob, origin);
        }
        for (Victim v : this.victims.values()) {
            LivingEntity mob = v.mob;
            restore(v);
            Vec3 from = v.slot >= 0 ? slotPos(v.slot) : mob.position().add(0.0D, mob.getBbHeight() * 0.5D, 0.0D);
            Vec3 vel = blastVelocity(c, from, blast, r);
            if (v.slot >= 0 && v.ragdoll && WheelRagdolls.isRagdolled(mob)) {
                // The whole body is thrown as ONE rigid object: one shared tumble, and every part gets exactly the
                // velocity it has as a point of that tumbling body (v + w x r). Giving each part its own random
                // push and spin made the joints fight each other and the body shook in the air.
                List<WheelRagdolls.Body> bodies = WheelRagdolls.bodies(mob);
                Vec3 rootPos = bodies.isEmpty() ? null : WheelRagdolls.bodyPosition(bodies.get(0));
                Vec3 out = vel.multiply(1.0D, 0.0D, 1.0D);
                Vec3 axis = out.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D).cross(out).normalize();
                // tumble mostly head-over-heels away from the wheel, with a little twist
                Vec3 spin = axis.scale(3.0D + r.nextDouble() * 1.5D).add(0.0D, (r.nextDouble() - 0.5D) * 1.2D, 0.0D);
                for (WheelRagdolls.Body b : bodies) {
                    Vec3 p = rootPos != null ? WheelRagdolls.bodyPosition(b) : null;
                    Vec3 pv = vel;
                    if (p != null && p.distanceTo(rootPos) < 4.0D) pv = vel.add(spin.cross(p.subtract(rootPos)));
                    WheelRagdolls.setVelocity(b, pv, spin);
                }
            } else if (mob.isAlive() && !mob.isRemoved() && !(v.ragdoll && WheelRagdolls.isPending(mob))) {
                throwMob(level, mob, vel, r);
            }
            thrown.add(mob);
        }
        this.victims.clear();

        double br = WheelConfig.BLAST_RADIUS.get();
        if (br > 0.0D) {
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(br),
                    e -> !thrown.contains(e) && canTarget(e) && e.distanceToSqr(c) <= br * br)) {
                Vec3 from = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
                double fall = 1.0D - Math.min(1.0D, from.distanceTo(c) / br) * 0.6D;
                throwMob(level, e, blastVelocity(c, from, blast * fall, r), r);
            }
        }
    }

    private static Vec3 blastVelocity(Vec3 c, Vec3 from, double blast, RandomSource r) {
        Vec3 out = from.subtract(c);
        if (out.lengthSqr() < 1.0E-4D) out = new Vec3(r.nextGaussian(), 0.0D, r.nextGaussian());
        out = out.normalize().add(r.nextGaussian() * 0.25D, 0.0D, r.nextGaussian() * 0.25D).normalize();
        double s = blast * (0.8D + 0.4D * r.nextDouble());
        return new Vec3(out.x * s, Math.max(0.0D, out.y) * s * 0.5D + blast * (0.45D + 0.25D * r.nextDouble()), out.z * s);
    }

    /** Not yet a ragdoll: becomes one now with the blast velocity, or gets plain knockback without Sable. */
    private static void throwMob(ServerLevel level, LivingEntity mob, Vec3 vel, RandomSource r) {
        if (WheelRagdolls.available()) {
            mob.addTag(WheelMod.RAGDOLL_TAG);
            if (WheelConfig.LIMP.get()) mob.addTag(WheelMod.KICKMOD_LIMP_TAG);
            Vec3 spin = new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).scale(5.0D);
            if (WheelRagdolls.launch(level, mob, vel, spin, WheelConfig.RAGDOLL_SECONDS.get())) return;
            mob.removeTag(WheelMod.RAGDOLL_TAG);
            mob.removeTag(WheelMod.KICKMOD_LIMP_TAG);
        }
        mob.setDeltaMovement(vel.scale(1.0D / 20.0D));
        mob.hurtMarked = true;
    }

    private void spawnPieces(ServerLevel level, Vec3 c) {
        RandomSource r = this.random;
        int n = 30;
        for (int i = 0; i < n; i++) {
            int kind = i < 12 ? WheelPieceEntity.RIM : (i < 22 ? WheelPieceEntity.BULB : WheelPieceEntity.SHARD);
            double a = i * (Math.PI * 2.0D / 12.0D) + r.nextDouble() * 0.4D;
            Vec3 p = toWorld(Math.cos(a) * RADIUS * (kind == WheelPieceEntity.SHARD ? 0.5D : 0.95D),
                    Math.sin(a) * RADIUS * (kind == WheelPieceEntity.SHARD ? 0.5D : 0.95D), (r.nextDouble() - 0.5D) * THICK);
            Vec3 out = p.subtract(c).normalize();
            Vec3 vel = out.scale(0.45D + r.nextDouble() * 0.5D).add(axle().scale((r.nextDouble() - 0.5D) * 0.6D))
                    .add(0.0D, 0.25D + r.nextDouble() * 0.45D, 0.0D);
            WheelPieceEntity piece = new WheelPieceEntity(WheelMod.PIECE.get(), level);
            piece.setup(kind, i % BULB_COLORS.length, p, vel);
            level.addFreshEntity(piece);
        }
    }

    // ---------------------------------------------------------------- sounds

    /** Prize-wheel ratchet: one click per segment that passes. */
    private void ratchetClicks(ServerLevel level) {
        if (phase() == BLOWN) return;
        double seg = Math.PI * 2.0D / 16.0D;
        if (Math.abs(this.spin - this.lastClickSpin) >= seg) {
            this.lastClickSpin = this.spin;
            Vec3 c = center();
            level.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.PLAYERS, 0.6F, 1.5F + this.random.nextFloat() * 0.2F);
        }
    }

    private void hoverSounds(ServerLevel level, int fuse) {
        Vec3 c = center();
        if (WheelConfig.MUSIC.get() && this.phaseTicks % 3 == 0) {
            int step = (this.phaseTicks / 3) % MELODY.length;
            int note = MELODY[step];
            if (note >= 0) {
                level.playSound(null, c.x, c.y, c.z, SoundEvents.NOTE_BLOCK_FLUTE.value(), SoundSource.RECORDS, 0.9F, pitch(note));
                level.playSound(null, c.x, c.y, c.z, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.RECORDS, 0.35F, pitch(note));
            }
            if (step % 2 == 0) {
                int bass = (step / 2) % 2 == 0 ? 6 : 13;
                level.playSound(null, c.x, c.y, c.z, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.RECORDS, 0.8F, pitch(bass));
            }
        }
        // countdown ticks get faster towards the end
        if (fuse > 0 && fuse <= 50) {
            int every = Math.max(2, fuse / 6);
            if (fuse % every == 0) {
                level.playSound(null, c.x, c.y, c.z, SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 1.2F, 0.8F + (50 - fuse) / 40.0F);
            }
            if (fuse == 30) level.playSound(null, c.x, c.y, c.z, SoundEvents.TNT_PRIMED, SoundSource.PLAYERS, 1.2F, 1.2F);
        }
    }

    private static float pitch(int note) {
        return (float) Math.pow(2.0D, (note - 12) / 12.0D);
    }

    public static DustParticleOptions dust(int rgb, float scale) {
        return new DustParticleOptions(new Vector3f(((rgb >> 16) & 255) / 255.0F, ((rgb >> 8) & 255) / 255.0F, (rgb & 255) / 255.0F), scale);
    }

    // ---------------------------------------------------------------- client effects

    // Client-side smoothing of the server position: vanilla Entity.lerpTo snaps straight to every packet, and with
    // packets arriving unevenly the rolling wheel (and the camera that follows it) stuttered.
    private int lerpSteps;
    private double lerpX, lerpY, lerpZ;

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpSteps = Math.max(3, steps);
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
        this.prevSpinC = this.spinC;
        float s = this.entityData.get(DATA_SPIN);
        if (this.prevPhaseC < 0) {
            this.prevSpinC = s;
            this.spinC = s;
        } else {
            // follow the server spin smoothly instead of jumping when a late update arrives
            this.spinC += (s - this.spinC) * 0.5F;
        }
        int phase = phase();
        if (phase == BLOWN && this.prevPhaseC >= 0 && this.prevPhaseC != BLOWN) WheelFx.explosion(this);
        this.prevPhaseC = phase;
        WheelFx.ambient(this);
        ClientHooks.wheelTick(this);
    }

    /** Client: a bulb position in world space (for sparkles). */
    public Vec3 clientPoint(double lx, double ly, double lz) {
        double sp = this.spinC;
        double s = Math.sin(-sp), c = Math.cos(-sp);
        double rx = lx * c - ly * s, ry = lx * s + ly * c;
        Vec3 d = dir(), ax = axle(), cen = center();
        return new Vec3(cen.x + d.x * rx + ax.x * lz, cen.y + ry, cen.z + d.z * rx + ax.z * lz);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide()) {
            for (Victim v : this.victims.values()) restore(v);
            this.victims.clear();
        }
        super.remove(reason);
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
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
}
