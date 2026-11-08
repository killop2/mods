package com.example.wheelofwonder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Strange's Hammer ("Молот Стрэнджа"): the sorcerer and his whole act, summoned over the target NPC.
 *  PORTAL  - a ring of sparks opens beside the target and the sorcerer steps out of it, floating, cloak flying,
 *  CAST    - a glowing glyph circle spreads under the target, mystic circles light up on his hands,
 *  SPIKES  - one spike bursts out of the ground under the target and lifts it, impaled; then more, one after
 *            another, pierce it from all sides and hold it high over the ground,
 *  HAMMER  - he opens a second, bigger portal; a huge hammer shoots out of it and strikes the target: the spikes
 *            shatter, the hammer bursts into sparks and the target flies off - always upwards,
 *  LEAVE   - he steps back through a new portal and it closes behind him.
 * Every position is a pure function of the act's age and a few synced values, so the client draws it smoothly.
 * The spikes, the hammer (and the flying target, StrangeServer) throw other NPCs as ragdolls and break leaves,
 * trees, ice, glass and plants.
 */
public class StrangeEntity extends Entity {
    // ---- the act (ticks)
    public static final int P1_OPEN0 = 4, P1_OPEN1 = 22, ARRIVE0 = 18, ARRIVE1 = 42, P1_CLOSE0 = 46, P1_CLOSE1 = 60;
    public static final int CAST0 = 50, GLYPH_IN = 16;
    public static final int SPIKE0 = 66, LIFT = 10, SIDE0 = 80, SIDE_STEP = 6, SPIKES = 7, GROW = 3;
    public static final int P2_OPEN0 = 114, P2_OPEN1 = 130, HAM0 = 132, HIT = 146, P2_CLOSE0 = 152, P2_CLOSE1 = 166;
    public static final int HAM_VANISH = 6, SHATTER = 26;
    public static final int P3_OPEN0 = 180, P3_OPEN1 = 196, LEAVE0 = 194, LEAVE1 = 216, P3_CLOSE0 = 218, P3_CLOSE1 = 232, END = 236;
    /** Impaled body over the ground, portal radii, where the hammer portal and the sorcerer stand. */
    public static final double LIFT_H = 3.4D, P1_R = 1.6D, P2_R = 2.4D, HAM_DIST = 10.5D, HAM_ASIDE = 2.5D, BACK = 5.5D, ASIDE = 4.5D, WALK = 2.6D;
    public static final double HEAD_HALF = 0.95D;

    private static final EntityDataAccessor<Integer> DATA_TARGET = SynchedEntityData.defineId(StrangeEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_AX = SynchedEntityData.defineId(StrangeEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_AY = SynchedEntityData.defineId(StrangeEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_AZ = SynchedEntityData.defineId(StrangeEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_DX = SynchedEntityData.defineId(StrangeEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_DZ = SynchedEntityData.defineId(StrangeEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SIDE = SynchedEntityData.defineId(StrangeEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_GY = SynchedEntityData.defineId(StrangeEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SY = SynchedEntityData.defineId(StrangeEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_HY = SynchedEntityData.defineId(StrangeEntity.class, EntityDataSerializers.FLOAT);

    // ---- server
    private int age;
    private UUID caster;
    private boolean impaled, ragdoll, launched, lostSent;
    private Vec3 lastHold;
    /** Ground under each spike (looked up once). */
    private double[] baseY;

    public StrangeEntity(EntityType<? extends StrangeEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(DATA_TARGET, -1);
        b.define(DATA_AX, 0.0F);
        b.define(DATA_AY, 0.0F);
        b.define(DATA_AZ, 0.0F);
        b.define(DATA_DX, 1.0F);
        b.define(DATA_DZ, 0.0F);
        b.define(DATA_SIDE, 1.0F);
        b.define(DATA_GY, 0.0F);
        b.define(DATA_SY, 0.0F);
        b.define(DATA_HY, 0.0F);
    }

    /**
     * Server: set up the act over this target. dir: caster -> target (flat); sideSign: the side the caster's camera
     * looks from (the sorcerer stands on the other side, so he is in the picture).
     */
    void setup(ServerPlayer caster, LivingEntity target, Vec3 dir, int sideSign) {
        this.caster = caster.getUUID();
        Vec3 a = target.position();
        this.entityData.set(DATA_TARGET, target.getId());
        this.entityData.set(DATA_AX, (float) a.x);
        this.entityData.set(DATA_AY, (float) a.y);
        this.entityData.set(DATA_AZ, (float) a.z);
        this.entityData.set(DATA_DX, (float) dir.x);
        this.entityData.set(DATA_DZ, (float) dir.z);
        this.entityData.set(DATA_SIDE, sideSign >= 0 ? 1.0F : -1.0F);
        double gy = GhostShipEntity.groundBelow(level(), a.x, a.y + 2.0D, a.z, a.y);
        this.entityData.set(DATA_GY, (float) gy);
        Vec3 s = spotXZ();
        this.entityData.set(DATA_SY, (float) GhostShipEntity.groundBelow(level(), s.x, a.y + 4.0D, s.z, gy));
        Vec3 h = hammerPortalXZ();
        double hg = GhostShipEntity.groundBelow(level(), h.x, a.y + 4.0D, h.z, gy);
        this.entityData.set(DATA_HY, (float) Math.min(gy + LIFT_H - 0.8D, Math.max(gy + 1.6D, hg + 1.7D)));
        setPos(a.x, gy, a.z);
    }

    // ------------------------------------------------------------------ the act's geometry (both sides)

    public int targetId() {
        return this.entityData.get(DATA_TARGET);
    }

    public Vec3 anchor() {
        return new Vec3(this.entityData.get(DATA_AX), this.entityData.get(DATA_AY), this.entityData.get(DATA_AZ));
    }

    public double groundY() {
        return this.entityData.get(DATA_GY);
    }

    public Vec3 dir() {
        Vec3 d = new Vec3(this.entityData.get(DATA_DX), 0.0D, this.entityData.get(DATA_DZ));
        return d.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : d.normalize();
    }

    /** The side the camera looks from. */
    public Vec3 side() {
        Vec3 d = dir();
        return new Vec3(-d.z, 0.0D, d.x).scale(this.entityData.get(DATA_SIDE));
    }

    public int actAge() {
        return level().isClientSide() ? this.tickCount : this.age;
    }

    static double ease(double x) {
        x = Mth.clamp(x, 0.0D, 1.0D);
        return x * x * (3.0D - 2.0D * x);
    }

    static double easeOut(double x) {
        x = Mth.clamp(x, 0.0D, 1.0D);
        double k = 1.0D - x;
        return 1.0D - k * k * k;
    }

    /** Overshoots a little before settling (portals snap open). */
    static double easeOutBack(double x) {
        x = Mth.clamp(x, 0.0D, 1.0D);
        double c = 1.7D;
        double k = x - 1.0D;
        return 1.0D + (c + 1.0D) * k * k * k + c * k * k;
    }

    /** Where the target is held, impaled, over the ground. */
    public Vec3 impale() {
        Vec3 a = anchor();
        return new Vec3(a.x, groundY() + LIFT_H, a.z);
    }

    private Vec3 spotXZ() {
        return anchor().subtract(dir().scale(BACK)).subtract(side().scale(ASIDE));
    }

    /** Where the sorcerer floats (his ground, his spot). */
    public Vec3 spot() {
        Vec3 s = spotXZ();
        return new Vec3(s.x, this.entityData.get(DATA_SY), s.z);
    }

    /** The way he faces: at the target. */
    public Vec3 face() {
        Vec3 d = impale().subtract(spot()).multiply(1.0D, 0.0D, 1.0D);
        return d.lengthSqr() < 1.0E-6D ? dir() : d.normalize();
    }

    /** His feet: out of the portal, floating over the ground; at the end backwards into the next portal. */
    public Vec3 strangeFeet(double t) {
        Vec3 s = spot(), f = face();
        double back = 0.0D;
        if (t < ARRIVE1) back = WALK * (1.0D - ease((t - ARRIVE0) / (ARRIVE1 - ARRIVE0)));
        else if (t > LEAVE0) back = WALK * ease((t - LEAVE0) / (LEAVE1 - LEAVE0));
        double hover = 0.35D + 0.08D * Math.sin(t * 0.12D);
        return s.subtract(f.scale(back)).add(0.0D, hover, 0.0D);
    }

    /** Fades in while he steps out of the portal, out while he steps back in. */
    public static float strangeAlpha(double t) {
        double in = Mth.clamp((t - ARRIVE0) / 12.0D, 0.0D, 1.0D);
        double out = 1.0D - Mth.clamp((t - LEAVE0 - 8.0D) / 12.0D, 0.0D, 1.0D);
        return (float) Math.min(in, out);
    }

    /** The portal he comes through (and leaves by): behind his spot, facing the target. */
    public Vec3 doorCentre() {
        return spot().subtract(face().scale(WALK - 0.4D)).add(0.0D, 1.35D, 0.0D);
    }

    static double portal(double t, double o0, double o1, double c0, double c1, double r) {
        if (t <= o0 || t >= c1) return 0.0D;
        if (t < o1) return r * easeOutBack((t - o0) / (o1 - o0));
        if (t < c0) return r;
        return r * (1.0D - ease((t - c0) / (c1 - c0)));
    }

    /** Radius of the sorcerer's door (arrival, then departure). */
    public static double doorRadius(double t) {
        return Math.max(portal(t, P1_OPEN0, P1_OPEN1, P1_CLOSE0, P1_CLOSE1, P1_R), portal(t, P3_OPEN0, P3_OPEN1, P3_CLOSE0, P3_CLOSE1, P1_R));
    }

    private Vec3 hammerPortalXZ() {
        return anchor().subtract(dir().scale(HAM_DIST)).subtract(side().scale(HAM_ASIDE));
    }

    /** The hammer's portal: low, on the caster's side, so the blow goes up. */
    public Vec3 hammerPortal() {
        Vec3 h = hammerPortalXZ();
        return new Vec3(h.x, this.entityData.get(DATA_HY), h.z);
    }

    public static double hammerPortalRadius(double t) {
        return portal(t, P2_OPEN0, P2_OPEN1, P2_CLOSE0, P2_CLOSE1, P2_R);
    }

    /** The hammer's flight direction (always rising). */
    public Vec3 hammerDir() {
        Vec3 d = impale().subtract(hammerPortal());
        return d.lengthSqr() < 1.0E-6D ? dir() : d.normalize();
    }

    /** Centre of the hammer's head: out of the portal, faster and faster, the face striking the body at HIT. */
    public Vec3 hammerCenter(double t) {
        Vec3 from = hammerPortal(), hd = hammerDir();
        Vec3 to = impale().subtract(hd.scale(HEAD_HALF + 0.2D));
        double u = Mth.clamp((t - HAM0) / (HIT - HAM0), 0.0D, 1.0D);
        double e = 0.3D * u + 0.7D * u * u;
        return from.lerp(to, e);
    }

    public static float hammerScale(double t) {
        if (t < HAM0) return 0.0F;
        if (t < HAM0 + 3.0D) return (float) (0.4D + 0.6D * (t - HAM0) / 3.0D);
        if (t < HIT) return 1.0F;
        if (t < HIT + HAM_VANISH) {
            double u = (t - HIT) / HAM_VANISH;
            return (float) ((1.0D - u) * (1.0D + 0.25D * Math.sin(u * Math.PI)));
        }
        return 0.0F;
    }

    /** The swing: the head comes down onto the target in the last part of the flight (radians, about its side). */
    public static double hammerWhip(double t) {
        double u = Mth.clamp((t - HAM0) / (HIT - HAM0), 0.0D, 1.0D);
        return 0.9D * (1.0D - u) * (1.0D - u);
    }

    // ---- spikes: 0 is the one straight up under the target (it lifts it), the rest pierce it from all sides

    public static int spikeTime(int k) {
        return k == 0 ? SPIKE0 : SIDE0 + (k - 1) * SIDE_STEP;
    }

    public static int spikeDur(int k) {
        return k == 0 ? LIFT : GROW;
    }

    public static double spikeWidth(int k) {
        return k == 0 ? 0.55D : 0.32D + 0.05D * (k % 2);
    }

    /** 0..1 how far out of the ground (fast at first, it bursts out). */
    public static double spikeGrow(int k, double t) {
        return easeOut((t - spikeTime(k)) / spikeDur(k));
    }

    public Vec3 spikeBase(int k) {
        Vec3 a = anchor();
        double x = a.x, z = a.z;
        if (k > 0) {
            double ang = k * Math.PI * 2.0D / (SPIKES - 1) + 0.4D;
            double r = 2.7D + 0.35D * Math.sin(k * 1.9D);
            x += Math.cos(ang) * r;
            z += Math.sin(ang) * r;
        }
        if (this.baseY == null) this.baseY = new double[SPIKES];
        if (this.baseY[k] == 0.0D) {
            double gy = groundY();
            double g = k == 0 ? gy : GhostShipEntity.groundBelow(level(), x, gy + 3.0D, z, gy);
            if (Math.abs(g - gy) > 3.0D) g = gy;
            this.baseY[k] = g - 0.4D;
        }
        return new Vec3(x, this.baseY[k], z);
    }

    public Vec3 spikeTip(int k) {
        Vec3 f = impale();
        if (k == 0) return f.add(0.0D, 1.2D, 0.0D);
        Vec3 d = f.subtract(spikeBase(k));
        return f.add(d.normalize().scale(1.1D));
    }

    /** The spike as it stands now: it slides up out of the ground (its hidden part below the surface). */
    public Vec3[] spikeNow(int k, double t) {
        double u = spikeGrow(k, t);
        Vec3 b = spikeBase(k), tip = spikeTip(k);
        Vec3 off = tip.subtract(b).scale(u - 1.0D);
        return new Vec3[]{b.add(off), tip.add(off)};
    }

    /** The target's body: lifted by the first spike, then held where all of them meet (a jolt at each new one). */
    public Vec3 body(double t) {
        Vec3 f = impale();
        double gy = groundY();
        if (t < SPIKE0 + LIFT) {
            double u = spikeGrow(0, t);
            double y = Math.max(gy + 0.9D, gy - 0.4D + (LIFT_H + 1.6D) * u - 1.2D);
            return new Vec3(f.x, Math.min(f.y, y), f.z);
        }
        double jolt = 0.0D;
        for (int k = 1; k < SPIKES; k++) {
            double d = t - spikeTime(k) - GROW + 1.0D;
            if (d >= 0.0D && d < 7.0D) jolt += 0.16D * Math.sin(Math.PI * d / 7.0D);
        }
        return f.add(0.0D, jolt, 0.0D);
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            StrangeFx.tick(this);
            return;
        }
        ServerLevel level = (ServerLevel) level();
        this.age++;
        double t = this.age;
        if (this.age >= END) {
            StrangeServer.gone(this, level);
            discard();
            return;
        }
        Entity te = level.getEntity(targetId());
        LivingEntity target = te instanceof LivingEntity le && le.isAlive() && !le.isRemoved() ? le : null;
        ServerPlayer player = this.caster != null ? (ServerPlayer) level.getPlayerByUUID(this.caster) : null;
        sounds(level, t);

        if (target != null && this.age < SPIKE0) freeze(target);
        if (this.age == SPIKE0) impale(level, target, player);
        for (int k = 0; k < SPIKES; k++) {
            int at = spikeTime(k);
            if (this.age >= at && this.age <= at + spikeDur(k)) spikeSweep(level, k, t, target);
            if (this.age == at) erupt(level, k);
            if (k > 0 && this.age == at + GROW) pierce(level, k);
        }
        if (this.impaled && !this.launched && this.age < HIT) {
            if (target != null) hang(target, t);
            else if (!this.lostSent && player != null) {
                this.lostSent = true;
                StrangeServer.lost(player);
            }
        }
        if (this.age >= HAM0 && this.age <= HIT) hammerSweep(level, t, target);
        if (this.age == HIT) strike(level, target, player);
    }

    private void sounds(ServerLevel level, double t) {
        Vec3 door = doorCentre(), hp = hammerPortal(), f = impale();
        if (this.age == P1_OPEN0 || this.age == P3_OPEN0) {
            play(level, door, SoundEvents.BEACON_ACTIVATE, 1.4F, 1.6F);
            play(level, door, SoundEvents.FIRECHARGE_USE, 1.0F, 0.7F);
            play(level, door, SoundEvents.ILLUSIONER_CAST_SPELL, 1.2F, 1.2F);
        }
        if (this.age == ARRIVE0 + 8) {
            play(level, door, SoundEvents.ILLUSIONER_PREPARE_MIRROR, 1.4F, 1.0F);
            play(level, door, SoundEvents.ENCHANTMENT_TABLE_USE, 1.2F, 0.8F);
        }
        if (this.age == LEAVE0 + 6) play(level, door, SoundEvents.ILLUSIONER_MIRROR_MOVE, 1.4F, 1.0F);
        if (this.age == P1_CLOSE0 || this.age == P3_CLOSE0) play(level, door, SoundEvents.BEACON_DEACTIVATE, 1.2F, 1.6F);
        // the crackle of the spark rings
        if (this.age % 5 == 0) {
            if (doorRadius(t) > 0.3D) play(level, door, SoundEvents.FIRE_AMBIENT, 1.0F, 1.7F);
            if (hammerPortalRadius(t) > 0.3D) play(level, hp, SoundEvents.FIRE_AMBIENT, 1.4F, 1.5F);
        }
        if (this.age == CAST0) {
            play(level, spot().add(0.0D, 1.5D, 0.0D), SoundEvents.EVOKER_PREPARE_ATTACK, 1.4F, 0.8F);
            play(level, f, SoundEvents.BEACON_POWER_SELECT, 1.4F, 1.3F);
        }
        if (this.age == P2_OPEN0) {
            play(level, hp, SoundEvents.BEACON_ACTIVATE, 2.0F, 1.2F);
            play(level, hp, SoundEvents.ILLUSIONER_CAST_SPELL, 1.6F, 0.7F);
            play(level, spot().add(0.0D, 1.5D, 0.0D), SoundEvents.EVOKER_PREPARE_SUMMON, 1.2F, 1.1F);
        }
        if (this.age == HAM0) {
            play(level, hp, SoundEvents.TRIDENT_RIPTIDE_3.value(), 2.0F, 0.6F);
            play(level, hp, SoundEvents.MACE_SMASH_AIR, 2.0F, 0.6F);
        }
        if (this.age == P2_CLOSE0) play(level, hp, SoundEvents.BEACON_DEACTIVATE, 1.6F, 1.3F);
    }

    private static void play(ServerLevel level, Vec3 at, SoundEvent s, float vol, float pitch) {
        level.playSound(null, at.x, at.y, at.z, s, SoundSource.PLAYERS, vol, pitch);
    }

    /** Before the spikes: the target stands spellbound, staring at the sorcerer. */
    private void freeze(LivingEntity target) {
        if (target instanceof Mob m) {
            m.getNavigation().stop();
            Vec3 s = spot();
            m.getLookControl().setLookAt(s.x, s.y + 1.6D, s.z);
        }
        Vec3 dm = target.getDeltaMovement();
        target.setDeltaMovement(0.0D, Math.min(0.0D, dm.y), 0.0D);
    }

    /** The first spike: the target is a ragdoll on it now, lifted and held. */
    private void impale(ServerLevel level, LivingEntity target, ServerPlayer player) {
        if (target == null) {
            this.lostSent = true;
            if (player != null) StrangeServer.lost(player);
            return;
        }
        this.impaled = true;
        this.lastHold = body(SPIKE0);
        WheelMod.hold(target);
        if (WheelRagdolls.available() && !WheelRagdolls.isRagdolled(target)) {
            target.addTag(WheelMod.RAGDOLL_TAG);
            if (WheelConfig.LIMP.get()) target.addTag(WheelMod.KICKMOD_LIMP_TAG);
            if (WheelRagdolls.launch(level, target, new Vec3(0.0D, 6.0D, 0.0D), Vec3.ZERO, WheelConfig.RAGDOLL_SECONDS.get())) {
                this.ragdoll = true;
            } else {
                target.removeTag(WheelMod.RAGDOLL_TAG);
                target.removeTag(WheelMod.KICKMOD_LIMP_TAG);
            }
        }
    }

    /** Held where the spikes meet. */
    private void hang(LivingEntity target, double t) {
        target.fallDistance = 0.0F;
        Vec3 to = body(t);
        if (this.ragdoll && WheelRagdolls.isRagdolled(target)) {
            Vec3 pathVel = to.subtract(this.lastHold).scale(20.0D);
            WheelRagdolls.holdRootMoving(target, to, pathVel, new Vec3(0.0D, 0.15D * Math.sin(t * 0.08D), 0.0D), 40.0D, 16.0D);
        } else {
            target.setPos(to.x, to.y - target.getBbHeight() * 0.5D, to.z);
            target.setDeltaMovement(Vec3.ZERO);
        }
        this.lastHold = to;
    }

    /** A spike bursts out of the ground: the earth cracks, stone flies. */
    private void erupt(ServerLevel level, int k) {
        Vec3 b = spikeBase(k).add(0.0D, 0.45D, 0.0D);
        play(level, b, SoundEvents.EVOKER_FANGS_ATTACK, 1.8F, 0.65F + 0.05F * k);
        play(level, b, SoundEvents.POINTED_DRIPSTONE_LAND, 1.6F, 0.6F);
        if (k == 0) {
            play(level, b, SoundEvents.ANVIL_PLACE, 1.0F, 0.5F);
            play(level, b, SoundEvents.AMETHYST_CLUSTER_BREAK, 1.6F, 0.6F);
        }
        BlockState ground = level.getBlockState(BlockPos.containing(b.x, b.y - 0.6D, b.z));
        if (!ground.isAir()) level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), b.x, b.y, b.z, 30, 0.5D, 0.2D, 0.5D, 0.3D);
        level.sendParticles(WheelEntity.dust(0xFF8A1E, 1.4F), b.x, b.y + 0.2D, b.z, 16, 0.5D, 0.3D, 0.5D, 0.0D);
        level.sendParticles(ParticleTypes.LAVA, b.x, b.y, b.z, 4, 0.3D, 0.1D, 0.3D, 0.0D);
    }

    /** A side spike runs the target through. */
    private void pierce(ServerLevel level, int k) {
        Vec3 f = body(this.age);
        play(level, f, SoundEvents.TRIDENT_HIT, 1.4F, 0.7F + 0.06F * k);
        play(level, f, SoundEvents.PLAYER_ATTACK_CRIT, 1.2F, 0.7F);
        level.sendParticles(ParticleTypes.CRIT, f.x, f.y, f.z, 14, 0.3D, 0.3D, 0.3D, 0.4D);
        level.sendParticles(WheelEntity.dust(0xFFB040, 1.2F), f.x, f.y, f.z, 10, 0.3D, 0.3D, 0.3D, 0.0D);
    }

    /** While a spike comes out: what is in its way breaks, NPCs it touches are thrown up. */
    private void spikeSweep(ServerLevel level, int k, double t, LivingEntity target) {
        Vec3[] s = spikeNow(k, t);
        Vec3 a = s[0], b = s[1];
        double len = a.distanceTo(b);
        for (double d = 0.0D; d <= len; d += 0.8D) smashSphere(level, a.lerp(b, d / Math.max(len, 1.0E-3D)), 0.8D, this);
        AABB box = new AABB(a, b).inflate(1.2D);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, StrangeEntity::rammable)) {
            if (e == target) continue;
            Vec3 c = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
            if (distToSegment(c, a, b) > 1.1D) continue;
            Vec3 away = c.subtract(impale()).multiply(1.0D, 0.0D, 1.0D);
            away = away.lengthSqr() < 1.0E-4D ? dir() : away.normalize();
            knock(level, e, away.scale(5.0D).add(0.0D, 13.0D, 0.0D));
        }
    }

    /** The hammer's way: breakables shatter, NPCs are swept off. */
    private void hammerSweep(ServerLevel level, double t, LivingEntity target) {
        Vec3 c = hammerCenter(t), hd = hammerDir();
        smashSphere(level, c, 1.5D, this);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(2.2D), StrangeEntity::rammable)) {
            if (e == target) continue;
            if (e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D).distanceTo(c) > 2.0D) continue;
            knock(level, e, hd.multiply(1.0D, 0.0D, 1.0D).scale(16.0D).add(0.0D, 12.0D, 0.0D));
        }
        if (this.age % 2 == 0) level.sendParticles(WheelEntity.dust(0xFF9A2E, 1.6F), c.x, c.y, c.z, 6, 0.4D, 0.4D, 0.4D, 0.0D);
    }

    /** The blow: the spikes shatter, the hammer bursts, the target flies - up and away. */
    private void strike(ServerLevel level, LivingEntity target, ServerPlayer player) {
        Vec3 f = body(HIT), hd = hammerDir();
        play(level, f, SoundEvents.MACE_SMASH_GROUND_HEAVY, 2.5F, 0.7F);
        play(level, f, SoundEvents.ANVIL_LAND, 1.5F, 0.6F);
        play(level, f, SoundEvents.GENERIC_EXPLODE.value(), 1.6F, 1.1F);
        play(level, f, SoundEvents.GLASS_BREAK, 1.6F, 0.6F);
        play(level, f, SoundEvents.AMETHYST_CLUSTER_BREAK, 2.0F, 0.5F);
        level.sendParticles(ParticleTypes.FLASH, f.x, f.y, f.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.EXPLOSION, f.x, f.y, f.z, 3, 0.6D, 0.6D, 0.6D, 0.0D);
        level.sendParticles(ParticleTypes.END_ROD, f.x, f.y, f.z, 40, 0.3D, 0.3D, 0.3D, 0.35D);
        level.sendParticles(ParticleTypes.CRIT, f.x, f.y, f.z, 30, 0.5D, 0.5D, 0.5D, 0.6D);
        level.sendParticles(WheelEntity.dust(0xFF8A1E, 2.0F), f.x, f.y, f.z, 40, 1.0D, 1.0D, 1.0D, 0.0D);
        // the spikes go to pieces
        BlockState stone = Blocks.BLACKSTONE.defaultBlockState();
        for (int k = 0; k < SPIKES; k++) {
            Vec3 m = spikeBase(k).lerp(spikeTip(k), 0.55D);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, stone), m.x, m.y, m.z, 24, 0.4D, 0.6D, 0.4D, 0.3D);
        }
        smashSphere(level, f, 2.2D, this);
        if (target == null) return;
        this.launched = true;
        Vec3 flat = hd.multiply(1.0D, 0.0D, 1.0D);
        flat = flat.lengthSqr() < 1.0E-4D ? dir() : flat.normalize();
        Vec3 sideways = new Vec3(-flat.z, 0.0D, flat.x).scale((level.random.nextDouble() - 0.5D) * 4.0D);
        Vec3 vel = flat.scale(19.0D).add(sideways).add(0.0D, 23.0D + Math.max(0.0D, hd.y) * 6.0D, 0.0D);
        WheelMod.unhold(target);
        target.fallDistance = 0.0F;
        if (this.ragdoll && WheelRagdolls.isRagdolled(target)) {
            Vec3 spin = new Vec3(level.random.nextGaussian(), level.random.nextGaussian(), level.random.nextGaussian()).scale(3.0D);
            WheelRagdolls.throwRigid(target, vel, spin);
        } else {
            target.setDeltaMovement(vel.scale(1.0D / 20.0D));
            target.hurtMarked = true;
        }
        if (player != null) StrangeServer.launched(player, target, f);
    }

    // ------------------------------------------------------------------ collisions (also used by StrangeServer)

    static boolean rammable(LivingEntity e) {
        return e.isAlive() && !(e instanceof Player) && !(e instanceof ArmorStand) && !e.isSpectator() && !WheelMod.held(e)
                && !WheelRagdolls.isRagdolled(e);
    }

    static void knock(ServerLevel level, LivingEntity e, Vec3 vel) {
        Vec3 c = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1.0F, 0.8F);
        level.sendParticles(WheelEntity.dust(0xFF8A1E, 1.2F), c.x, c.y, c.z, 8, 0.3D, 0.4D, 0.3D, 0.0D);
        if (!WheelRagdolls.available()) {
            e.setDeltaMovement(vel.scale(1.0D / 20.0D));
            e.hurtMarked = true;
            return;
        }
        e.addTag(WheelMod.RAGDOLL_TAG);
        if (WheelConfig.LIMP.get()) e.addTag(WheelMod.KICKMOD_LIMP_TAG);
        Vec3 spin = new Vec3(level.random.nextGaussian(), level.random.nextGaussian(), level.random.nextGaussian()).scale(1.5D);
        if (!WheelRagdolls.launch(level, e, vel, spin, WheelConfig.RAGDOLL_SECONDS.get())) {
            e.removeTag(WheelMod.RAGDOLL_TAG);
            e.removeTag(WheelMod.KICKMOD_LIMP_TAG);
            e.setDeltaMovement(vel.scale(1.0D / 20.0D));
            e.hurtMarked = true;
        }
    }

    static void smashSphere(ServerLevel level, Vec3 c, double r, Entity breaker) {
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(c.x - r); x <= Mth.floor(c.x + r); x++) {
            for (int y = Mth.floor(c.y - r); y <= Mth.floor(c.y + r); y++) {
                for (int z = Mth.floor(c.z - r); z <= Mth.floor(c.z + r); z++) {
                    if (c.distanceToSqr(x + 0.5D, y + 0.5D, z + 0.5D) > r * r) continue;
                    bp.set(x, y, z);
                    BlockState s = level.getBlockState(bp);
                    if (!s.isAir() && GhostShipEntity.breakable(s)) level.destroyBlock(bp, false, breaker);
                }
            }
        }
    }

    static double distToSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double l2 = ab.lengthSqr();
        if (l2 < 1.0E-8D) return p.distanceTo(a);
        double u = Mth.clamp(p.subtract(a).dot(ab) / l2, 0.0D, 1.0D);
        return p.distanceTo(a.add(ab.scale(u)));
    }

    // ------------------------------------------------------------------ misc

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
    public AABB getBoundingBoxForCulling() {
        return getBoundingBox().inflate(24.0D, 16.0D, 24.0D);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
