package com.example.wheelofwonder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Chaos Meteor: a burning rock called down from the sky.
 * FALL  - it comes in on a long slant from far behind the caster (high in the sky) and lands on the aimed spot,
 * IMPACT- a blast: NPCs around are thrown away from it as ragdolls,
 * ROLL  - it rolls on along the ground in the caster's direction, over bumps (up to one block) and down slopes,
 *         smashing leaves, logs, ice, glass, snow and plants in its way and throwing every NPC it meets ahead of
 *         it and up into the air (ragdolls),
 * END   - after its distance (or at a wall) it bursts into glowing chunks and is gone.
 * The caster's free camera starts on the meteor and moves to the first NPC it hits (BallistaCamera).
 */
public class MeteorEntity extends Entity {
    public static final int FALL = 0, ROLL = 1;
    public static final float RADIUS = 1.1F;
    /** It hangs in the sky this long, then falls (speeding up all the way) for FALL_TICKS. */
    public static final int HOVER_TICKS = 40, FALL_TICKS = 70;

    private static final EntityDataAccessor<Integer> DATA_PHASE = SynchedEntityData.defineId(MeteorEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_TX = SynchedEntityData.defineId(MeteorEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_TY = SynchedEntityData.defineId(MeteorEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_TZ = SynchedEntityData.defineId(MeteorEntity.class, EntityDataSerializers.FLOAT);

    private Vec3 start = Vec3.ZERO, target = Vec3.ZERO, dir = new Vec3(1, 0, 0);
    private UUID owner;
    private int age;
    private double rolled, vy;
    private boolean cameraMoved;
    private final Set<UUID> hit = new HashSet<>();

    // client: rotation for drawing and the recent path (the comet tail)
    public float spin, spinO;
    public Vec3 spinAxis = new Vec3(1, 0, 0);
    public final java.util.ArrayDeque<Vec3> trail = new java.util.ArrayDeque<>();

    public MeteorEntity(EntityType<? extends MeteorEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    // ------------------------------------------------------------------ casting

    /** Right click with the item (sent from the client): the meteor falls onto the block the player looks at. */
    static void cast(ServerPlayer p) {
        if (!ChaosMeteorItem.isMeteor(p.getMainHandItem()) || p.getCooldowns().isOnCooldown(p.getMainHandItem().getItem())) return;
        ServerLevel level = p.serverLevel();
        double range = WheelConfig.METEOR_RANGE.get();
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        BlockHitResult bh = level.clip(new ClipContext(eye, eye.add(look.scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 aim = bh.getType() == HitResult.Type.MISS ? eye.add(look.scale(range)) : bh.getLocation();
        double ground = groundBelow(level, aim.x, aim.y + 0.5D, aim.z, aim.y);
        Vec3 flat = new Vec3(look.x, 0, look.z);
        if (flat.lengthSqr() < 1.0E-4D) flat = Vec3.directionFromRotation(0.0F, p.getYRot());
        flat = flat.normalize();
        MeteorEntity m = new MeteorEntity(WheelMod.METEOR.get(), level);
        m.owner = p.getUUID();
        m.dir = flat;
        m.target = new Vec3(aim.x, ground, aim.z);
        // from far behind the caster, high in the sky
        m.start = m.target.subtract(flat.scale(48.0D)).add(0.0D, 64.0D, 0.0D);
        m.entityData.set(DATA_TX, (float) m.target.x);
        m.entityData.set(DATA_TY, (float) m.target.y);
        m.entityData.set(DATA_TZ, (float) m.target.z);
        m.setPos(m.start.x, m.start.y, m.start.z);
        level.addFreshEntity(m);
        level.playSound(null, p.getX(), p.getEyeY(), p.getZ(), SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.PLAYERS, 1.0F, 0.6F);
        level.playSound(null, m.target.x, m.target.y, m.target.z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 2.0F, 0.4F);
        level.playSound(null, m.target.x, m.target.y, m.target.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 2.0F, 0.5F);
        p.getCooldowns().addCooldown(p.getMainHandItem().getItem(), WheelConfig.METEOR_COOLDOWN.get());
        // the caster's camera goes straight onto the meteor
        PacketDistributor.sendToPlayer(p, new BallistaState(BallistaState.FOLLOW, m.getId(), m.start.x, m.start.y, m.start.z));
    }

    // ------------------------------------------------------------------ data

    public int phase() {
        return this.entityData.get(DATA_PHASE);
    }

    public Vec3 target() {
        return new Vec3(this.entityData.get(DATA_TX), this.entityData.get(DATA_TY), this.entityData.get(DATA_TZ));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(DATA_PHASE, FALL);
        b.define(DATA_TX, 0.0F);
        b.define(DATA_TY, 0.0F);
        b.define(DATA_TZ, 0.0F);
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            clientTick();
            return;
        }
        ServerLevel level = (ServerLevel) level();
        this.age++;
        if (this.age > 20 * 20) {
            shatter(level);
            return;
        }
        if (phase() == FALL) {
            if (this.age <= HOVER_TICKS) {
                // hangs in the sky, slowly bobbing, gathering fire
                double bob = Math.sin(this.age * Math.PI / 20.0D) * 0.12D;
                setPos(this.start.x, this.start.y + bob, this.start.z);
                if (this.age % 10 == 0) level.playSound(null, this.start.x, this.start.y, this.start.z, SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, 1.2F, 0.35F);
                return;
            }
            double t = Math.min(1.0D, (this.age - HOVER_TICKS) / (double) FALL_TICKS);
            double e = t * t * (0.5D + 0.5D * t);           // from rest, speeding up all the way down
            Vec3 p = this.start.add(this.target.subtract(this.start).scale(e));
            setPos(p.x, p.y, p.z);
            hitNpcs(level, Vec3.ZERO, false);
            if (this.age % 3 == 0) level.playSound(null, p.x, p.y, p.z, SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, 0.6F, 0.4F);
            if (t >= 1.0D) impact(level);
        } else {
            roll(level);
        }
    }

    /** Touches down: the blast throws everything around away from it. */
    private void impact(ServerLevel level) {
        Vec3 c = position().add(0.0D, RADIUS * 0.6D, 0.0D);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, 3.0F, 0.6F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL, 1.5F, 0.4F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.LAVA_EXTINGUISH, SoundSource.NEUTRAL, 1.5F, 0.6F);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        BlockState ground = level.getBlockState(BlockPos.containing(c.x, c.y - RADIUS, c.z).below());
        if (!ground.isAir()) level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), c.x, c.y - 0.5D, c.z, 120, 1.6D, 0.3D, 1.6D, 0.4D);
        // a ring of fire running out over the ground
        for (int i = 0; i < 48; i++) {
            double a = i * Math.PI * 2 / 48;
            double dx = Math.cos(a), dz = Math.sin(a);
            level.sendParticles(ParticleTypes.FLAME, c.x, c.y - 0.5D, c.z, 0, dx, 0.05D, dz, 0.55D);
            if (i % 2 == 0) level.sendParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y - 0.4D, c.z, 0, dx, 0.08D, dz, 0.35D);
            if (i % 4 == 0) level.sendParticles(ParticleTypes.LAVA, c.x + dx, c.y, c.z + dz, 1, 0, 0, 0, 0);
        }
        hitNpcs(level, Vec3.ZERO, true);
        this.entityData.set(DATA_PHASE, ROLL);
        this.age = 0;
    }

    /** Rolls along the ground: climbs steps up to a block, falls down slopes, stops at walls. */
    private void roll(ServerLevel level) {
        double dist = WheelConfig.METEOR_ROLL.get();
        double u = Math.min(1.0D, this.rolled / dist);
        double speed = Mth.lerp(u, 10.0D, 4.0D) / 20.0D;     // blocks per tick, slowing down
        Vec3 p = position();
        double nx = p.x + this.dir.x * speed, nz = p.z + this.dir.z * speed;
        // Where a ball of this radius rests: the ground is sampled along its way under the whole ball, and each
        // sample holds the centre up by the circle's height there. So it rises onto a step and tips over an edge
        // along a curve instead of popping up or dropping a block at once.
        double support = Double.NEGATIVE_INFINITY;
        for (int i = -8; i <= 8; i++) {
            double s = i * 0.1D * RADIUS;
            double g = groundBelow(level, nx + this.dir.x * s, p.y + 2.5D, nz + this.dir.z * s, Double.NaN);
            if (Double.isNaN(g)) continue;
            if (g > p.y + 1.25D) {
                if (s >= 0.0D) {
                    shatter(level);   // a wall right ahead
                    return;
                }
                continue;
            }
            support = Math.max(support, g + Math.sqrt(RADIUS * RADIUS - s * s) - RADIUS);
        }
        double ny;
        if (support == Double.NEGATIVE_INFINITY || support < p.y - 0.01D) {
            // nothing under it: falls (and lands softly on whatever is below)
            this.vy = Math.max(-1.2D, this.vy - 0.06D);
            ny = p.y + this.vy;
            if (support != Double.NEGATIVE_INFINITY && ny < support) {
                ny = support;
                this.vy = 0.0D;
            }
        } else {
            ny = Math.min(support, p.y + 0.3D);   // rolls up, never jumps
            this.vy = 0.0D;
        }
        setPos(nx, ny, nz);
        this.rolled += speed;
        hitNpcs(level, this.dir, false);
        if (this.age % 2 == 0) {
            BlockState under = level.getBlockState(BlockPos.containing(nx, ny - 0.2D, nz));
            if (!under.isAir()) level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, under), nx, ny + 0.1D, nz, 6, 0.6D, 0.05D, 0.6D, 0.15D);
            level.sendParticles(ParticleTypes.FLAME, nx - this.dir.x * RADIUS, ny + 0.15D, nz - this.dir.z * RADIUS, 3, 0.4D, 0.05D, 0.4D, 0.01D);
        }
        if (this.age % 8 == 0) level.playSound(null, nx, ny, nz, SoundEvents.STONE_BREAK, SoundSource.NEUTRAL, 1.4F, 0.4F);
        if (this.rolled >= dist) shatter(level);
    }

    /**
     * NPCs in the meteor's way fly off as ragdolls, ahead along {@code push} (rolling) or away from it (the blast),
     * and up into the air. The first one hit takes over the caster's camera.
     */
    private void hitNpcs(ServerLevel level, Vec3 push, boolean blast) {
        Vec3 c = position().add(0.0D, RADIUS, 0.0D);
        double r = blast ? RADIUS + 2.6D : RADIUS + 0.35D;
        ServerPlayer p = this.owner != null ? (ServerPlayer) level.getPlayerByUUID(this.owner) : null;
        for (Entity e : level.getEntities(this, new AABB(c, c).inflate(r + 1.5D), x -> x instanceof LivingEntity && !(x instanceof Player)
                && !(x instanceof ArmorStand) && x.isAlive() && !x.isSpectator())) {
            LivingEntity mob = (LivingEntity) e;
            if (this.hit.contains(mob.getUUID()) || WheelRagdolls.isRagdolled(mob) || WheelMod.held(mob)) continue;
            Vec3 mc = mob.position().add(0.0D, mob.getBbHeight() * 0.5D, 0.0D);
            double reach = r + mob.getBbWidth() * 0.5D;
            if (mob.getBoundingBox().distanceToSqr(c) > reach * reach) continue;
            this.hit.add(mob.getUUID());
            Vec3 away = mc.subtract(c).multiply(1, 0, 1);
            Vec3 d = blast || push.lengthSqr() < 1.0E-4D ? (away.lengthSqr() < 1.0E-4D ? this.dir : away.normalize())
                    : push.add(away.lengthSqr() > 1.0E-4D ? away.normalize().scale(0.35D) : Vec3.ZERO).normalize();
            double knock = WheelConfig.METEOR_KNOCK.get() * (blast ? 0.8D : 1.0D);
            Vec3 vel = d.scale(knock).add(0.0D, WheelConfig.METEOR_LIFT.get(), 0.0D);
            Vec3 axis = new Vec3(0, 1, 0).cross(d);
            Vec3 spin = (axis.lengthSqr() < 1.0E-4D ? new Vec3(1, 0, 0) : axis.normalize()).scale(-1.5D)
                    .add(level.random.nextGaussian() * 0.3D, level.random.nextGaussian() * 0.3D, level.random.nextGaussian() * 0.3D);
            mob.invulnerableTime = 0;
            mob.hurt(level.damageSources().source(net.minecraft.world.damagesource.DamageTypes.IN_FIRE), WheelConfig.METEOR_DAMAGE.get().floatValue());
            mob.igniteForSeconds(3.0F);
            if (!mob.isAlive()) continue;
            ServerPlayer viewer = null;
            if (!this.cameraMoved && p != null) {
                // the caster's camera leaves the meteor and follows this NPC
                this.cameraMoved = true;
                viewer = p;
                PacketDistributor.sendToPlayer(p, new BallistaState(BallistaState.RETARGET, mob.getId(), mc.x, mc.y, mc.z));
            }
            BallistaServer.launch(level, viewer, mob, vel, spin, true);
            level.playSound(null, mc.x, mc.y, mc.z, SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.NEUTRAL, 1.4F, 0.6F);
            level.playSound(null, mc.x, mc.y, mc.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, 0.7F, 1.4F);
            level.sendParticles(ParticleTypes.FLAME, mc.x, mc.y, mc.z, 25, 0.3D, 0.4D, 0.3D, 0.15D);
            level.sendParticles(ParticleTypes.LAVA, mc.x, mc.y, mc.z, 6, 0.3D, 0.3D, 0.3D, 0.0D);
        }
    }

    /** The end: the rock bursts into glowing chunks that bounce away and fade. */
    private void shatter(ServerLevel level) {
        Vec3 c = position().add(0.0D, RADIUS, 0.0D);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, 1.6F, 0.9F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.DEEPSLATE_BREAK, SoundSource.NEUTRAL, 2.0F, 0.5F);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.NEUTRAL, 1.2F, 0.7F);
        level.sendParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 3, 0.5D, 0.4D, 0.5D, 0);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, 30, 0.6D, 0.5D, 0.6D, 0.05D);
        level.sendParticles(ParticleTypes.LAVA, c.x, c.y, c.z, 14, 0.6D, 0.4D, 0.6D, 0);
        level.sendParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 40, 0.6D, 0.5D, 0.6D, 0.12D);
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI * 2 / 12 + level.random.nextDouble() * 0.4D;
            Vec3 v = new Vec3(Math.cos(a) * 0.35D, 0.35D + level.random.nextDouble() * 0.35D, Math.sin(a) * 0.35D).add(this.dir.scale(0.15D));
            WheelPieceEntity piece = new WheelPieceEntity(WheelMod.PIECE.get(), level);
            piece.setup(WheelPieceEntity.ROCK, i % 3 == 0 ? 1 : 0, c.add(v.scale(1.5D)), v);
            level.addFreshEntity(piece);
        }
        discard();
    }

    private static double groundBelow(ServerLevel level, double x, double fromY, double z, double fallback) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int by = Mth.floor(fromY); by >= Mth.floor(fromY) - 8; by--) {
            p.set(Mth.floor(x), by, Mth.floor(z));
            BlockState st = level.getBlockState(p);
            VoxelShape s = st.getCollisionShape(level, p);
            if (!s.isEmpty()) return by + s.max(Direction.Axis.Y);
        }
        return fallback;
    }

    // ------------------------------------------------------------------ client

    private Vec3 lastPos;
    private int lerpSteps;
    private double lerpX, lerpY, lerpZ;

    /**
     * Position updates from the server glide in over a few ticks (a plain Entity would jump straight to each one,
     * and uneven packet timing shows as stutter on a big rolling rock).
     */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpSteps = Math.max(1, steps);
    }

    private void clientTick() {
        if (this.lerpSteps > 0) {
            double k = 1.0D / this.lerpSteps;
            setPos(getX() + (this.lerpX - getX()) * k, getY() + (this.lerpY - getY()) * k, getZ() + (this.lerpZ - getZ()) * k);
            this.lerpSteps--;
        }
        Vec3 p = position();
        this.spinO = this.spin;
        if (this.lastPos != null) {
            Vec3 d = p.subtract(this.lastPos);
            Vec3 flat = new Vec3(d.x, 0, d.z);
            if (phase() == ROLL && flat.lengthSqr() > 1.0E-6D) {
                // rolling: turns about the axis across its motion, by the distance over its radius
                this.spinAxis = new Vec3(0, 1, 0).cross(flat.normalize()).scale(-1.0D);
                this.spin += (float) (flat.length() / RADIUS);
            } else {
                this.spin += 0.08F;
            }
        }
        this.lastPos = p;
        Vec3 c = p.add(0.0D, RADIUS, 0.0D);
        this.trail.addLast(c);
        while (this.trail.size() > (phase() == FALL ? 18 : 10)) this.trail.removeFirst();
        Level l = level();
        var r = l.getRandom();
        for (int i = 0; i < (phase() == FALL ? 6 : 3); i++) {
            double ox = r.nextGaussian() * 0.5D, oy = r.nextGaussian() * 0.5D, oz = r.nextGaussian() * 0.5D;
            l.addParticle(i % 2 == 0 ? ParticleTypes.FLAME : ParticleTypes.LARGE_SMOKE, c.x + ox, c.y + oy, c.z + oz, 0.0D, 0.04D, 0.0D);
        }
        if (r.nextFloat() < 0.35F) l.addParticle(ParticleTypes.LAVA, c.x, c.y, c.z, 0, 0, 0);
        if (r.nextFloat() < 0.5F) l.addParticle(WheelEntity.dust(0xFF5A14, 1.6F), c.x + r.nextGaussian() * 0.6D, c.y + r.nextGaussian() * 0.6D, c.z + r.nextGaussian() * 0.6D, 0, 0, 0);
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
        return d < 256 * 256;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
