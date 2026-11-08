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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.Tags;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The ghost ship of the Trident ("Тризуб!"). Summoned over the target NPC, it plays its whole act by itself:
 *  STORM    - clouds gather and thunder rolls, fog creeps over the ground; the ship takes shape far off in the mist,
 *  SAIL     - it glides in and stops beside the target over the ground, its cannons firing round it,
 *  BOARD    - the dead crew slide down ropes and close in with sabres and pistols,
 *  BREAK    - the target fights them off (they are thrown back) and runs,
 *  HARPOON  - the captain fires the harpoon that is his left arm: the hook catches the running target (a ragdoll
 *             now) and the chain reels it up to the deck,
 *  TIE      - it is bound to the main mast,
 *  CIRCLE   - the ship rises and sails round and round over the place; below, a pool of dark water spreads with
 *             sharks circling in it,
 *  WALK     - the prisoner is untied and driven out along the bow onto the bowsprit,
 *  KICK     - the captain kicks it off; on its way down a shark leaps out of the water, catches it in its jaws and
 *             takes it down,
 *  VANISH   - the ship melts into the fog, the water and the sharks are gone; the prisoner lies there, dealt a
 *             deadly blow.
 * Every position (ship, crew, captain, hook, prisoner, sharks) is a pure function of the ship's age and of a few
 * synced values (where the target stood, the heading, where the hook struck, the water level), so the client
 * draws the same act smoothly. The hull and the cannonballs throw other NPCs as ragdolls and break leaves, trees,
 * ice, glass and plants.
 */
public class GhostShipEntity extends Entity {
    // ---- the act (ticks)
    public static final int APPEAR = 10, SAIL_END = 85;
    public static final int BOARD0 = 88, DESCEND = BOARD0 + 10, SURROUND = BOARD0 + 32, BREAK = 160;
    public static final int RUN_END = BREAK + 34, HARPOON = 186, HIT = 198, REEL_END = 232, TIE_END = 262;
    public static final int CIRCLE0 = 262, RISE_TIME = 26, WALK0 = 372, WALK_END = 414, KICK = 420;
    public static final int VANISH0 = 470, VANISH_END = 530;
    public static final int WATER0 = 270, WATER_IN = 30, WATER_OUT0 = 480, WATER_OUT = 50;
    public static final int CREW = 4, CANNONS = 7, CANNON0 = 44, CANNON_STEP = 8, BALL_FLIGHT = 8, SHARKS = 3, LEAP = 12, DIVE = 20;
    /** Deck height over the keel, the stern's raised deck, half the beam (rail), half the length. */
    public static final double DECK = 2.2D, QUARTER = 3.2D, RAIL = 2.3D, HALF_LEN = 7.0D;
    public static final double CIRCLE_R = 9.0D, CIRCLE_H = 10.0D, WATER_R = 14.0D, GRAVITY = 0.055D;

    private static final EntityDataAccessor<Integer> DATA_TARGET = SynchedEntityData.defineId(GhostShipEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_AX = SynchedEntityData.defineId(GhostShipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_AY = SynchedEntityData.defineId(GhostShipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_AZ = SynchedEntityData.defineId(GhostShipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_DX = SynchedEntityData.defineId(GhostShipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_DZ = SynchedEntityData.defineId(GhostShipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_HX = SynchedEntityData.defineId(GhostShipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_HY = SynchedEntityData.defineId(GhostShipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_HZ = SynchedEntityData.defineId(GhostShipEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_WY = SynchedEntityData.defineId(GhostShipEntity.class, EntityDataSerializers.FLOAT);

    // ---- server
    private int age;
    private UUID caster;
    private boolean captured, kicked, dropped;
    private Vec3 lastHold;
    private final Set<UUID> rammed = new HashSet<>();

    public GhostShipEntity(EntityType<? extends GhostShipEntity> type, Level level) {
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
        b.define(DATA_HX, 0.0F);
        b.define(DATA_HY, 0.0F);
        b.define(DATA_HZ, 0.0F);
        b.define(DATA_WY, 0.0F);
    }

    /** Server: set up the act over this target, the ship lying away from the caster (dir: caster -> target, flat). */
    void setup(ServerPlayer caster, LivingEntity target, Vec3 dir) {
        this.caster = caster.getUUID();
        Vec3 a = target.position();
        this.entityData.set(DATA_TARGET, target.getId());
        this.entityData.set(DATA_AX, (float) a.x);
        this.entityData.set(DATA_AY, (float) a.y);
        this.entityData.set(DATA_AZ, (float) a.z);
        this.entityData.set(DATA_DX, (float) dir.x);
        this.entityData.set(DATA_DZ, (float) dir.z);
        this.entityData.set(DATA_WY, (float) (groundBelow(level(), a.x, a.y + 2.0D, a.z, a.y) + 0.35D));
        Vec3 e = escape();
        this.entityData.set(DATA_HX, (float) e.x);
        this.entityData.set(DATA_HY, (float) (a.y + 1.0D));
        this.entityData.set(DATA_HZ, (float) e.z);
        Vec3 o = origin(0.0D);
        setPos(o.x, o.y, o.z);
    }

    // ------------------------------------------------------------------ the act's geometry (both sides)

    public int targetId() {
        return this.entityData.get(DATA_TARGET);
    }

    public Vec3 anchor() {
        return new Vec3(this.entityData.get(DATA_AX), this.entityData.get(DATA_AY), this.entityData.get(DATA_AZ));
    }

    /** Where the hook struck the running target (set at the hit; before it, where the target runs to). */
    public Vec3 hookHit() {
        return new Vec3(this.entityData.get(DATA_HX), this.entityData.get(DATA_HY), this.entityData.get(DATA_HZ));
    }

    /** The surface of the shark pool. */
    public double waterY() {
        return this.entityData.get(DATA_WY);
    }

    private Vec3 dir0() {
        return new Vec3(this.entityData.get(DATA_DX), 0.0D, this.entityData.get(DATA_DZ));
    }

    /** Where the target runs to when it breaks free: off along the ship's length, away from it. */
    public Vec3 escape() {
        Vec3 s = dir0(), f = new Vec3(-s.z, 0.0D, s.x);
        return anchor().subtract(s.scale(2.5D)).add(f.scale(8.0D));
    }

    public int actAge() {
        return level().isClientSide() ? this.tickCount : this.age;
    }

    static double ease(double x) {
        x = Mth.clamp(x, 0.0D, 1.0D);
        return x * x * (3.0D - 2.0D * x);
    }

    /** The ship's heading: fixed until it starts circling, then it turns with the circle (radians). */
    public double heading(double t) {
        double th0 = Math.atan2(this.entityData.get(DATA_DZ), this.entityData.get(DATA_DX));
        if (t <= CIRCLE0) return th0;
        double tau = t - CIRCLE0;
        // slowly speeding up to about a turn every 9 seconds
        return th0 + 0.035D * tau * tau / (tau + 25.0D);
    }

    /** Ship's starboard (local +X): away from the caster; while circling, outwards from the circle. */
    public Vec3 side(double t) {
        double h = heading(t);
        return new Vec3(Math.cos(h), 0.0D, Math.sin(h));
    }

    /** Ship's bow (local +Z). */
    public Vec3 fwd(double t) {
        Vec3 s = side(t);
        return new Vec3(-s.z, 0.0D, s.x);
    }

    /** Turn of the ship about Y (radians), as used by Axis.YP.rotation(-yaw) in the renderer. */
    public double yawRad(double t) {
        return heading(t);
    }

    /** Where the ship's keel (bottom centre) is t ticks into the act. */
    public Vec3 origin(double t) {
        Vec3 a = anchor(), s0 = dir0(), f0 = new Vec3(-s0.z, 0.0D, s0.x);
        Vec3 station = a.add(s0.scale(5.5D)).add(0.0D, 3.0D, 0.0D);
        Vec3 far = station.subtract(f0.scale(34.0D)).add(0.0D, 7.0D, 0.0D);
        Vec3 p;
        if (t < APPEAR) {
            p = far;
        } else if (t < SAIL_END) {
            double u = (t - APPEAR) / (SAIL_END - APPEAR);
            p = far.lerp(station, 1.0D - (1.0D - u) * (1.0D - u));
        } else if (t < CIRCLE0) {
            p = station;
        } else {
            // up and out onto the circle, then round and round over the place
            double u = ease((t - CIRCLE0) / RISE_TIME);
            double r = 5.5D + (CIRCLE_R - 5.5D) * u, h = 3.0D + CIRCLE_H * u;
            if (t > VANISH0) h += (t - VANISH0) * 0.05D;
            p = a.add(side(t).scale(r)).add(0.0D, h, 0.0D);
        }
        return p.add(0.0D, 0.18D * Math.sin(t * 0.08D), 0.0D);
    }

    /** The ship's slow roll (radians); it leans into the circle. */
    public static double roll(double t) {
        double lean = t > CIRCLE0 ? -0.06D * ease((t - CIRCLE0) / RISE_TIME) : 0.0D;
        return 0.045D * Math.sin(t * 0.06D) + lean;
    }

    /** How solid the ship is (0 = not there, 1 = fully there): it comes out of the fog and melts back into it. */
    public static float presence(double t) {
        if (t < APPEAR) return 0.0F;
        if (t < 40.0D) return (float) ease((t - APPEAR) / 30.0D);
        if (t < VANISH0) return 1.0F;
        return (float) (1.0D - ease((t - VANISH0) / (double) (VANISH_END - VANISH0)));
    }

    /** How far the shark pool has spread (0..1). */
    public static float water(double t) {
        if (t < WATER0) return 0.0F;
        if (t < WATER0 + WATER_IN) return (float) ease((t - WATER0) / WATER_IN);
        if (t < WATER_OUT0) return 1.0F;
        return (float) (1.0D - ease((t - WATER_OUT0) / WATER_OUT));
    }

    /** Local ship point (x starboard, y up from the keel, z towards the bow) to world, t ticks into the act. */
    public Vec3 toWorld(Vec3 local, double t) {
        Vec3 s = side(t), f = fwd(t);
        double r = roll(t);
        double y = local.y * Math.cos(r) + local.x * Math.sin(r);
        double x = local.x * Math.cos(r) - local.y * Math.sin(r);
        return origin(t).add(s.scale(x)).add(0.0D, y, 0.0D).add(f.scale(local.z));
    }

    /** A sailor's deck place. */
    public static Vec3 home(int i) {
        return new Vec3(i % 2 == 0 ? -0.9D : 0.9D, DECK, i < 2 ? 3.0D : -1.0D);
    }

    private Vec3 ring(int i, double spin, double r) {
        double ang = Math.PI * 0.25D + i * Math.PI * 0.5D + spin;
        return anchor().add(Math.cos(ang) * r, 0.0D, Math.sin(ang) * r);
    }

    /** Where sailor i stands (feet, world) t ticks into the act. */
    public Vec3 sailor(int i, double t) {
        Vec3 home = toWorld(home(i), t);
        Vec3 rail = toWorld(new Vec3(-RAIL + 0.05D, DECK + 0.45D, home(i).z), t);
        if (t < BOARD0) return home;
        if (t < DESCEND) return home.lerp(rail, ease((t - BOARD0) / (double) (DESCEND - BOARD0)));
        if (t < SURROUND) {
            double u = (t - DESCEND) / (double) (SURROUND - DESCEND);
            return rail.lerp(ring(i, 0.0D, 1.4D), ease(u)).add(0.0D, 0.6D * Math.sin(Math.PI * u), 0.0D);
        }
        double spinEnd = (BREAK - SURROUND) * 0.04D;
        if (t < BREAK) return ring(i, (t - SURROUND) * 0.04D, 1.4D);
        // the target fights them off: thrown back, they tumble and get up
        Vec3 near = ring(i, spinEnd, 1.4D), thrown = ring(i, spinEnd, 4.2D);
        if (t < BREAK + 14) {
            double u = (t - BREAK) / 14.0D;
            return near.lerp(thrown, ease(u)).add(0.0D, 1.2D * Math.sin(Math.PI * u), 0.0D);
        }
        if (t < HARPOON - 2) return thrown;
        if (t < HARPOON + 18) {
            // back up the ropes to the ship
            double u = (t - HARPOON + 2) / 20.0D;
            return thrown.lerp(rail, ease(u));
        }
        if (t < REEL_END) return rail.lerp(home, ease((t - HARPOON - 18) / (double) (REEL_END - HARPOON - 18)));
        Vec3 mast = toWorld(new Vec3(i % 2 == 0 ? -0.8D : 0.8D, DECK, i < 2 ? 0.9D : -0.9D), t);
        if (t < TIE_END) return home.lerp(mast, ease((t - REEL_END) / 12.0D));
        if (t < WALK0) return i < 2 ? mast : home.lerp(mast, 1.0D - ease((t - TIE_END) / 12.0D));
        if (i < 2) {
            // two of them drive the prisoner out to the bow
            Vec3 bow = toWorld(new Vec3(i == 0 ? -0.7D : 0.7D, DECK, 4.6D), t);
            return mast.lerp(bow, ease((t - WALK0) / (double) (WALK_END - WALK0)));
        }
        return home;
    }

    /** True while sailor i is hanging on its rope (between the rail and the ground). */
    public static boolean onRope(int i, double t) {
        return (t >= DESCEND && t < SURROUND) || (t >= HARPOON - 2 && t < HARPOON + 18);
    }

    /** True while sailor i lies thrown back by the target. */
    public static boolean knockedDown(double t) {
        return t >= BREAK && t < HARPOON - 2;
    }

    /** The captain: on the stern deck; he fires his harpoon from there, then walks to the bow and gives the kick. */
    public Vec3 captain(double t) {
        Vec3 stand = new Vec3(-0.6D, QUARTER, -5.0D);
        if (t < WALK0) return toWorld(stand, t);
        Vec3 mid = new Vec3(0.5D, DECK, 1.5D), bow = new Vec3(0.4D, DECK, 5.6D);
        double u = (t - WALK0) / (double) (KICK - 2 - WALK0);
        if (u < 0.4D) return toWorld(stand.lerp(mid, ease(u / 0.4D)), t);
        return toWorld(mid.lerp(bow, ease((u - 0.4D) / 0.6D)), t);
    }

    /** The muzzle of the captain's harpoon arm. */
    public Vec3 harpoonMuzzle(double t) {
        return captain(t).add(0.0D, 1.4D, 0.0D).add(side(t).scale(-0.5D));
    }

    /**
     * Where the target runs while it is free, t ticks into the act: from where it stood to its escape point
     * (the server moves it there; the hook flies at this point).
     */
    public Vec3 running(double t) {
        double u = ease((t - BREAK) / (double) (RUN_END - BREAK));
        return anchor().lerp(escape(), Mth.clamp(u, 0.0D, 1.0D));
    }

    /** The hook in flight (HARPOON..HIT) or holding the prisoner while it is reeled in. */
    public Vec3 hook(double t) {
        if (t < HIT) {
            double u = (t - HARPOON) / (double) (HIT - HARPOON);
            Vec3 to = running(HIT).add(0.0D, 1.0D, 0.0D);
            return harpoonMuzzle(t).lerp(to, Mth.clamp(u, 0.0D, 1.0D)).add(0.0D, 1.5D * Math.sin(Math.PI * Mth.clamp(u, 0.0D, 1.0D)), 0.0D);
        }
        return prisoner(t);
    }

    /** The point in front of the main mast where the prisoner is bound. */
    public Vec3 mastPoint(double t) {
        return toWorld(new Vec3(-0.45D, DECK + 1.15D, 0.0D), t);
    }

    /** Where the prisoner's body is held (after the hook caught it, until the shark lets go). */
    public Vec3 prisoner(double t) {
        Vec3 hit = hookHit();
        if (t < HIT) return hit;
        if (t < REEL_END) {
            // reeled in on the chain in a swinging arc up to the rail, then to the mast
            double u = (t - HIT) / (double) (REEL_END - HIT);
            Vec3 rail = toWorld(new Vec3(-RAIL - 0.3D, DECK + 1.6D, -1.0D), t);
            if (u < 0.75D) {
                double v = ease(u / 0.75D);
                return hit.lerp(rail, v).add(0.0D, 2.5D * Math.sin(Math.PI * v), 0.0D);
            }
            return rail.lerp(mastPoint(t), ease((u - 0.75D) / 0.25D));
        }
        if (t < WALK0) return mastPoint(t);
        if (t < KICK) {
            Vec3 bow = toWorld(new Vec3(0.0D, DECK + 1.1D, 5.4D), t);
            Vec3 sprit = toWorld(new Vec3(0.0D, 3.3D, 8.1D), t);
            double u = (t - WALK0) / (double) (WALK_END - WALK0);
            if (u >= 1.0D) return sprit;
            return u < 0.65D ? mastPoint(t).lerp(bow, ease(u / 0.65D)) : bow.lerp(sprit, ease((u - 0.65D) / 0.35D));
        }
        int catchT = catchTick();
        if (t < catchT) return fall(t - KICK);
        // in the shark's jaws, down into the water
        return sharkMouth(t);
    }

    /** The kicked prisoner's fall, tau ticks after the kick: forward off the bow, in towards the middle, and down. */
    public Vec3 fall(double tau) {
        Vec3 k = toWorld(new Vec3(0.0D, 3.3D, 8.1D), KICK);
        Vec3 in = anchor().subtract(k).multiply(1.0D, 0.0D, 1.0D);
        in = in.lengthSqr() < 1.0E-4D ? Vec3.ZERO : in.normalize();
        Vec3 v = fwd(KICK).scale(0.2D).add(in.scale(0.18D)).add(0.0D, 0.16D, 0.0D);
        return k.add(v.scale(tau)).subtract(0.0D, 0.5D * GRAVITY * tau * tau, 0.0D);
    }

    /** The moment the shark has it: when it has fallen to a few blocks over the water. */
    public int catchTick() {
        double wy = waterY();
        for (int tau = 4; tau < 120; tau++) {
            if (fall(tau).y <= wy + 3.5D) return KICK + tau;
        }
        return KICK + 40;
    }

    /** Where the leaping shark's jaws are (from its leap out of the water to its dive back in). */
    public Vec3 sharkMouth(double t) {
        int c = catchTick();
        Vec3 catchAt = fall(c - KICK);
        Vec3 out = new Vec3(catchAt.x, waterY() - 0.5D, catchAt.z).subtract(fwd(KICK).scale(3.0D));
        Vec3 down = new Vec3(catchAt.x, waterY() + 0.4D, catchAt.z).add(fwd(KICK).scale(3.5D));
        if (t < c) {
            double u = Mth.clamp((t - (c - LEAP)) / (double) LEAP, 0.0D, 1.0D);
            return out.lerp(catchAt, u).add(0.0D, 1.2D * Math.sin(Math.PI * u), 0.0D);
        }
        double u = Mth.clamp((t - c) / (double) DIVE, 0.0D, 1.0D);
        return catchAt.lerp(down, u).add(0.0D, 1.5D * Math.sin(Math.PI * u) - 0.0D, 0.0D).subtract(0.0D, u * u * 1.5D, 0.0D);
    }

    /** Shark k swimming its round in the pool (centre of its body, just under the surface) and its heading. */
    public Vec3 shark(int k, double t) {
        double r = 4.0D + k * 2.4D, w = (k % 2 == 0 ? 0.045D : -0.038D);
        double ang = k * 2.1D + t * w;
        Vec3 a = anchor();
        return new Vec3(a.x + Math.cos(ang) * r, waterY() - 0.45D + 0.1D * Math.sin(t * 0.2D + k), a.z + Math.sin(ang) * r);
    }

    public Vec3 sharkDir(int k, double t) {
        return shark(k, t + 1.0D).subtract(shark(k, t)).normalize();
    }

    /** Gun port k on the side facing the target. */
    public Vec3 gunport(int k, double t) {
        return toWorld(new Vec3(-RAIL - 0.1D, 1.35D, -4.5D + (k % 5) * 2.2D), t);
    }

    /** Where cannonball k lands (round the target, never on it); y is set to the ground by the caller. */
    public Vec3 impactXZ(int k) {
        double ang = getId() * 0.731D + k * 2.4D;
        double r = 2.8D + (k % 3) * 1.3D;
        return anchor().add(Math.cos(ang) * r, 0.0D, Math.sin(ang) * r);
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            GhostShipFx.tick(this);
            return;
        }
        ServerLevel level = (ServerLevel) level();
        this.age++;
        double t = this.age;
        Vec3 o = origin(t);
        setPos(o.x, o.y, o.z);
        if (this.age >= VANISH_END) {
            TrizubServer.shipGone(this, level);
            discard();
            return;
        }
        Entity te = level.getEntity(targetId());
        LivingEntity target = te instanceof LivingEntity le && le.isAlive() && !le.isRemoved() ? le : null;
        ServerPlayer player = this.caster != null ? (ServerPlayer) level.getPlayerByUUID(this.caster) : null;

        // the storm: thunder rolling out of the clouds (no bolts: just the sound and the flashes in the cloud)
        if (this.age == 6 || this.age == 26 || this.age == 58) {
            Vec3 a = anchor();
            level.playSound(null, a.x, a.y + 16.0D, a.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 3.0F, 0.6F + level.random.nextFloat() * 0.2F);
        }
        if (this.age == APPEAR) {
            Vec3 a = anchor();
            level.playSound(null, a.x, a.y, a.z, SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 1.0F, 0.6F);
            level.playSound(null, o.x, o.y, o.z, SoundEvents.AMBIENT_CAVE.value(), SoundSource.HOSTILE, 1.5F, 0.6F);
        }
        if (this.age % 40 == 20 && this.age < VANISH0) level.playSound(null, o.x, o.y + 3, o.z, SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 0.8F, 0.5F);
        if (this.age % 30 == 0 && this.age < VANISH0) level.playSound(null, o.x, o.y + 2, o.z, SoundEvents.WOOD_STEP, SoundSource.HOSTILE, 1.0F, 0.5F);

        // the hull ploughs through what is in its way while it sails and while it rises
        boolean moving = (t > APPEAR && t < SAIL_END) || (t > CIRCLE0 && t < CIRCLE0 + RISE_TIME);
        if (moving && this.age % 2 == 0) smash(level, t);
        ram(level, t, target);

        // the cannons
        for (int k = 0; k < CANNONS; k++) {
            int fire = CANNON0 + k * CANNON_STEP;
            if (this.age == fire) {
                Vec3 g = gunport(k, t);
                level.playSound(null, g.x, g.y, g.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.6F, 0.6F);
                level.playSound(null, g.x, g.y, g.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.HOSTILE, 1.0F, 0.5F);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, g.x, g.y, g.z, 12, 0.3D, 0.3D, 0.3D, 0.05D);
                level.sendParticles(ParticleTypes.FLAME, g.x, g.y, g.z, 8, 0.2D, 0.2D, 0.2D, 0.05D);
            }
            if (this.age == fire + BALL_FLIGHT) cannonball(level, k, target);
        }

        if (target != null && !this.captured) holdTarget(level, target, t, o);
        if (this.age == HIT) catchWithHook(level, target, player, t);
        if (this.captured && !this.dropped && target != null) carry(level, target, t, o);
        if (this.captured && !this.dropped && target == null && this.age >= KICK) this.dropped = true;

        // the shark pool
        if (this.age == WATER0) {
            Vec3 a = anchor();
            level.playSound(null, a.x, waterY(), a.z, SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 2.0F, 0.5F);
            level.playSound(null, a.x, waterY(), a.z, SoundEvents.ELDER_GUARDIAN_AMBIENT, SoundSource.HOSTILE, 1.2F, 0.5F);
        }
        if (this.age > WATER0 && this.age < WATER_OUT0 && this.age % 25 == 0) {
            Vec3 a = anchor();
            level.playSound(null, a.x, waterY(), a.z, SoundEvents.GENERIC_SWIM, SoundSource.HOSTILE, 1.0F, 0.6F);
        }
    }

    /** Before the hook: the target stands spellbound; then it breaks free and runs. */
    private void holdTarget(ServerLevel level, LivingEntity target, double t, Vec3 o) {
        if (target instanceof Mob m) m.getNavigation().stop();
        if (t < BREAK) {
            if (target instanceof Mob m) m.getLookControl().setLookAt(o.x, o.y + 3, o.z);
            Vec3 dm = target.getDeltaMovement();
            target.setDeltaMovement(0.0D, Math.min(0.0D, dm.y), 0.0D);
            if (t > SURROUND && this.age % 9 == 0) {
                // pistol shots and clashing sabres
                Vec3 a = anchor().add(0.0D, 1.2D, 0.0D);
                level.playSound(null, a.x, a.y, a.z, this.age % 18 == 0 ? SoundEvents.CROSSBOW_SHOOT : SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.0F, 0.7F);
                level.sendParticles(ParticleTypes.SMOKE, a.x, a.y, a.z, 4, 0.8D, 0.3D, 0.8D, 0.02D);
            }
            return;
        }
        if (this.age == BREAK) {
            // it fights them off
            Vec3 a = anchor().add(0.0D, 1.0D, 0.0D);
            level.playSound(null, a.x, a.y, a.z, SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.HOSTILE, 1.5F, 0.7F);
            level.playSound(null, a.x, a.y, a.z, SoundEvents.SKELETON_HURT, SoundSource.HOSTILE, 1.2F, 0.6F);
            level.playSound(null, a.x, a.y, a.z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.HOSTILE, 1.2F, 0.8F);
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, a.x, a.y, a.z, 4, 1.0D, 0.2D, 1.0D, 0.0D);
            level.sendParticles(ParticleTypes.SOUL, a.x, a.y, a.z, 10, 1.2D, 0.4D, 1.2D, 0.04D);
        }
        // running to its escape point
        Vec3 p = running(t);
        double g = groundBelow(level, p.x, p.y + 2.0D, p.z, p.y);
        Vec3 next = running(t + 1.0D);
        target.setPos(p.x, g, p.z);
        target.setDeltaMovement(next.subtract(p).multiply(1.0D, 0.0D, 1.0D));
        float yaw = (float) (Mth.atan2(-(next.x - p.x), next.z - p.z) * Mth.RAD_TO_DEG);
        if (next.distanceToSqr(p) > 1.0E-6D) {
            target.setYRot(yaw);
            target.yBodyRot = yaw;
            target.setYHeadRot(yaw);
        }
        if (this.age == HARPOON) {
            Vec3 m = harpoonMuzzle(t);
            level.playSound(null, m.x, m.y, m.z, SoundEvents.CROSSBOW_SHOOT, SoundSource.HOSTILE, 2.0F, 0.5F);
            level.playSound(null, m.x, m.y, m.z, SoundEvents.CHAIN_BREAK, SoundSource.HOSTILE, 1.2F, 0.6F);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, m.x, m.y, m.z, 8, 0.2D, 0.2D, 0.2D, 0.03D);
        }
    }

    /** The hook strikes: the target is a ragdoll on the chain now. */
    private void catchWithHook(ServerLevel level, LivingEntity target, ServerPlayer player, double t) {
        if (target != null && !WheelRagdolls.isRagdolled(target) && WheelRagdolls.available()) {
            Vec3 hit = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
            this.entityData.set(DATA_HX, (float) hit.x);
            this.entityData.set(DATA_HY, (float) hit.y);
            this.entityData.set(DATA_HZ, (float) hit.z);
            target.addTag(WheelMod.RAGDOLL_TAG);
            if (WheelConfig.LIMP.get()) target.addTag(WheelMod.KICKMOD_LIMP_TAG);
            if (WheelRagdolls.launch(level, target, new Vec3(0.0D, 4.0D, 0.0D), Vec3.ZERO, WheelConfig.RAGDOLL_SECONDS.get())) {
                WheelMod.hold(target);
                this.captured = true;
                this.lastHold = hit;
                level.playSound(null, hit.x, hit.y, hit.z, SoundEvents.TRIDENT_HIT, SoundSource.HOSTILE, 1.6F, 0.7F);
                level.playSound(null, hit.x, hit.y, hit.z, SoundEvents.CHAIN_PLACE, SoundSource.HOSTILE, 1.4F, 0.6F);
                level.sendParticles(ParticleTypes.CRIT, hit.x, hit.y, hit.z, 20, 0.3D, 0.3D, 0.3D, 0.3D);
                if (player != null) TrizubServer.capture(player, target, hit);
            } else {
                target.removeTag(WheelMod.RAGDOLL_TAG);
                target.removeTag(WheelMod.KICKMOD_LIMP_TAG);
            }
        }
        if (!this.captured && player != null) TrizubServer.lost(player);
    }

    /** From the hook to the mast, out to the bowsprit, the kick, the fall, the shark; then let go, dead. */
    private void carry(ServerLevel level, LivingEntity target, double t, Vec3 o) {
        target.fallDistance = 0.0F;
        int catchT = catchTick();
        if (this.age >= catchT + DIVE) {
            finish(level, target, t);
            return;
        }
        if (WheelRagdolls.isRagdolled(target)) {
            Vec3 to = prisoner(t);
            Vec3 pathVel = to.subtract(this.lastHold).scale(20.0D);
            this.lastHold = to;
            Vec3 spin = t < KICK ? new Vec3(0.0D, 0.25D * Math.sin(t * 0.1D), 0.0D) : side(KICK).scale(2.0D);
            WheelRagdolls.holdRootMoving(target, to, pathVel, spin, 45.0D, 16.0D);
        }
        if (this.age == REEL_END - 4) level.playSound(null, o.x, o.y + DECK, o.z, SoundEvents.WOOD_FALL, SoundSource.HOSTILE, 1.2F, 0.6F);
        if (this.age == REEL_END + 6) {
            Vec3 m = mastPoint(t);
            level.playSound(null, m.x, m.y, m.z, SoundEvents.LEASH_KNOT_PLACE, SoundSource.HOSTILE, 1.4F, 0.6F);
            level.playSound(null, m.x, m.y, m.z, SoundEvents.CHAIN_PLACE, SoundSource.HOSTILE, 1.2F, 0.5F);
        }
        if (this.age == WALK0) {
            Vec3 m = mastPoint(t);
            level.playSound(null, m.x, m.y, m.z, SoundEvents.LEASH_KNOT_BREAK, SoundSource.HOSTILE, 1.2F, 0.7F);
        }
        if (this.age == KICK) {
            this.kicked = true;
            WheelMod.hold(target);
            Vec3 c = prisoner(t);
            level.playSound(null, c.x, c.y, c.z, SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.HOSTILE, 1.6F, 0.6F);
            level.playSound(null, c.x, c.y, c.z, SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 0.7F, 1.4F);
            level.sendParticles(ParticleTypes.SOUL, c.x, c.y, c.z, 12, 0.4D, 0.4D, 0.4D, 0.03D);
            TrizubServer.pushed(target);
        }
        if (this.age == catchT - LEAP) {
            Vec3 m = sharkMouth(t);
            level.playSound(null, m.x, waterY(), m.z, SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 2.0F, 0.8F);
            level.sendParticles(ParticleTypes.SPLASH, m.x, waterY() + 0.2D, m.z, 40, 1.0D, 0.2D, 1.0D, 0.3D);
        }
        if (this.age == catchT) {
            Vec3 m = sharkMouth(t);
            level.playSound(null, m.x, m.y, m.z, SoundEvents.PHANTOM_BITE, SoundSource.HOSTILE, 2.0F, 0.5F);
            level.playSound(null, m.x, m.y, m.z, SoundEvents.EVOKER_FANGS_ATTACK, SoundSource.HOSTILE, 1.6F, 0.6F);
            level.sendParticles(WheelEntity.dust(0x7A0A0A, 1.6F), m.x, m.y, m.z, 30, 0.4D, 0.4D, 0.4D, 0.0D);
            level.sendParticles(ParticleTypes.SPLASH, m.x, m.y, m.z, 30, 0.6D, 0.4D, 0.6D, 0.2D);
        }
    }

    /** The shark lets go in the water: the target lies there, dealt a deadly blow. */
    private void finish(ServerLevel level, LivingEntity target, double t) {
        this.dropped = true;
        Vec3 at = sharkMouth(t);
        WheelMod.unhold(target);
        target.removeTag(WheelMod.RAGDOLL_TAG);
        level.playSound(null, at.x, waterY(), at.z, SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 2.2F, 0.6F);
        level.sendParticles(ParticleTypes.SPLASH, at.x, waterY() + 0.2D, at.z, 60, 1.5D, 0.3D, 1.5D, 0.4D);
        level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, at.x, waterY() - 0.3D, at.z, 30, 1.0D, 0.2D, 1.0D, 0.1D);
        level.sendParticles(WheelEntity.dust(0x7A0A0A, 1.8F), at.x, waterY() + 0.1D, at.z, 30, 1.2D, 0.1D, 1.2D, 0.0D);
        target.invulnerableTime = 0;
        target.hurt(level.damageSources().generic(), 1000.0F);
        if (target.isAlive() && WheelRagdolls.isRagdolled(target)) {
            WheelRagdolls.throwRigid(target, new Vec3(0.0D, -3.0D, 0.0D), Vec3.ZERO);
        }
    }

    private void cannonball(ServerLevel level, int k, LivingEntity target) {
        Vec3 p = impactXZ(k);
        double g = groundBelow(level, p.x, anchor().y + 4.0D, p.z, anchor().y);
        Vec3 at = new Vec3(p.x, g, p.z);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.4F, 0.9F);
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.3D, at.z, 2, 0.3D, 0.2D, 0.3D, 0.0D);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.3D, at.z, 10, 0.5D, 0.3D, 0.5D, 0.04D);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y + 0.2D, at.z, 12, 0.5D, 0.2D, 0.5D, 0.06D);
        BlockState ground = level.getBlockState(BlockPos.containing(at.x, at.y - 0.5D, at.z));
        if (!ground.isAir()) level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), at.x, at.y + 0.2D, at.z, 30, 0.6D, 0.2D, 0.6D, 0.3D);
        smashSphere(level, at.add(0.0D, 0.5D, 0.0D), 1.7D);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(2.4D), this::rammable)) {
            if (e == target) continue;
            Vec3 away = e.position().subtract(at).multiply(1.0D, 0.0D, 1.0D);
            away = away.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : away.normalize();
            knock(level, e, away.scale(8.0D).add(0.0D, 7.0D, 0.0D));
        }
    }

    // ------------------------------------------------------------------ collisions

    private boolean rammable(LivingEntity e) {
        return e.isAlive() && !(e instanceof Player) && !(e instanceof ArmorStand) && !e.isSpectator() && !WheelMod.held(e)
                && !WheelRagdolls.isRagdolled(e) && !this.rammed.contains(e.getUUID());
    }

    /** NPCs inside the hull fall as ragdolls, thrown along the ship's way and up. */
    private void ram(ServerLevel level, double t, LivingEntity target) {
        Vec3 c = toWorld(new Vec3(0.0D, DECK * 0.5D, 0.0D), t);
        Vec3 vel = origin(t + 1.0D).subtract(origin(t)).scale(20.0D);
        Vec3 f = fwd(t), s = side(t);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(HALF_LEN + 1.0D, 3.5D, HALF_LEN + 1.0D), this::rammable)) {
            if (e == target) continue;
            Vec3 rel = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D).subtract(c);
            double along = rel.dot(f), across = rel.dot(s);
            if (Math.abs(along) > HALF_LEN + 0.3D || Math.abs(across) > RAIL + 0.3D || rel.y < -2.0D || rel.y > 3.5D) continue;
            Vec3 push = vel.lengthSqr() > 1.0D ? vel.normalize() : s.scale(across >= 0.0D ? 1.0D : -1.0D);
            knock(level, e, push.scale(10.0D).add(0.0D, 8.0D, 0.0D));
        }
    }

    private void knock(ServerLevel level, LivingEntity e, Vec3 vel) {
        this.rammed.add(e.getUUID());
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
        }
        Vec3 c = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.HOSTILE, 1.0F, 0.6F);
        level.sendParticles(ParticleTypes.SOUL, c.x, c.y, c.z, 6, 0.3D, 0.4D, 0.3D, 0.03D);
    }

    /** Breakable blocks inside the hull's volume go to pieces. */
    private void smash(ServerLevel level, double t) {
        for (double x = -RAIL; x <= RAIL + 0.01D; x += 1.0D) {
            for (double y = 0.0D; y <= DECK + 1.5D; y += 1.0D) {
                for (double z = -HALF_LEN; z <= HALF_LEN + 0.01D; z += 1.0D) {
                    Vec3 w = toWorld(new Vec3(x, y, z), t);
                    BlockPos bp = BlockPos.containing(w);
                    BlockState s = level.getBlockState(bp);
                    if (!s.isAir() && breakable(s)) level.destroyBlock(bp, false, this);
                }
            }
        }
    }

    private void smashSphere(ServerLevel level, Vec3 c, double r) {
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(c.x - r); x <= Mth.floor(c.x + r); x++) {
            for (int y = Mth.floor(c.y - r); y <= Mth.floor(c.y + r); y++) {
                for (int z = Mth.floor(c.z - r); z <= Mth.floor(c.z + r); z++) {
                    if (c.distanceToSqr(x + 0.5D, y + 0.5D, z + 0.5D) > r * r) continue;
                    bp.set(x, y, z);
                    BlockState s = level.getBlockState(bp);
                    if (!s.isAir() && breakable(s)) level.destroyBlock(bp, false, this);
                }
            }
        }
    }

    /** What a ghost ship goes through: leaves, trees, ice, glass, snow, plants. */
    static boolean breakable(BlockState s) {
        if (!s.getFluidState().isEmpty() && s.getBlock() != Blocks.ICE) return false;
        return s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(BlockTags.ICE) || s.is(Tags.Blocks.GLASS_BLOCKS) || s.is(Tags.Blocks.GLASS_PANES)
                || s.is(Blocks.SNOW) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW) || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS)
                || s.is(Blocks.CACTUS) || s.is(Blocks.BAMBOO) || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.VINE) || s.is(Blocks.MUSHROOM_STEM)
                || s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(BlockTags.WOOL_CARPETS)
                || (s.canBeReplaced() && s.getFluidState().isEmpty());
    }

    static double groundBelow(Level level, double x, double fromY, double z, double fallback) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int by = Mth.floor(fromY); by >= Mth.floor(fromY) - 16; by--) {
            p.set(Mth.floor(x), by, Mth.floor(z));
            VoxelShape s = level.getBlockState(p).getCollisionShape(level, p);
            if (!s.isEmpty()) return by + s.max(Direction.Axis.Y);
        }
        return fallback;
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
        return getBoundingBox().inflate(40.0D, 30.0D, 40.0D);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
