package com.example.wheelofwonder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server side of Shockwave!:
 * WINDUP - the hero swings the spear overhead (the client animates it),
 * WAVE   - the spear hits the ground: a rift runs along the ground in the aimed direction, over bumps up to one
 *          block, until its range, a wall or a cliff. Every NPC it touches is caught,
 * VORTEX - caught NPCs close to each other share one vortex: they are pulled spinning into its centre, lifted a
 *          little off the ground, then the vortex bursts: the damage, and every one of them flies off as a ragdoll,
 *          each in its own direction, away from the centre and up.
 * The first NPC caught is the caster's: his camera moves onto it and follows its body (TRACK) until Ctrl.
 */
public final class ShockwaveServer {
    /** Wind-up of the swing until the spear hits the ground (the client uses the same count). */
    public static final int WINDUP_TICKS = 20;
    private static final int WINDUP = 0, WAVE = 1, AFTER = 2;
    private static final int MAX_AGE = 20 * 60 * 20;
    private static final double MAX_STEP = 8.0D, NEAR = 128.0D;

    private static final class Member {
        final LivingEntity mob;
        final Vec3 from;
        final int joinAge, slot;
        final float yaw0;

        Member(LivingEntity mob, int joinAge, int slot) {
            this.mob = mob;
            this.from = mob.position();
            this.joinAge = joinAge;
            this.slot = slot;
            this.yaw0 = mob.getYRot();
        }
    }

    private static final class Vortex {
        final Vec3 centre;
        final List<Member> members = new ArrayList<>();
        int age;
        double spin;
        boolean burst;

        Vortex(Vec3 centre) {
            this.centre = centre;
        }
    }

    private static final class Cast {
        final ServerPlayer player;
        final Vec3 dir;
        int phase = WINDUP, age, phaseAge;
        Vec3 front;
        double travelled;
        final Set<UUID> hit = new HashSet<>();
        final List<Vortex> vortices = new ArrayList<>();
        LivingEntity focus;
        boolean focusLaunched, clientDone, endSent;
        Vec3 last;

        Cast(ServerPlayer player, Vec3 dir) {
            this.player = player;
            this.dir = dir;
        }
    }

    private static final Map<UUID, Cast> CASTS = new HashMap<>();

    private ShockwaveServer() {
    }

    public static void handleRequest(ShockRequest req, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (req.action() == ShockRequest.START) {
            start(player);
        } else if (req.action() == ShockRequest.DONE) {
            Cast c = CASTS.get(player.getUUID());
            if (c != null) c.clientDone = true;
        }
    }

    private static void start(ServerPlayer p) {
        var item = p.getMainHandItem();
        if (CASTS.containsKey(p.getUUID()) || !ShockwaveItem.isShockwave(item) || p.getCooldowns().isOnCooldown(item.getItem())) {
            sendCaster(p, ShockState.END, -1, p.position());
            return;
        }
        Vec3 look = p.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-4D) flat = Vec3.directionFromRotation(0.0F, p.getYRot());
        Cast c = new Cast(p, flat.normalize());
        CASTS.put(p.getUUID(), c);
        // a long cooldown while it runs (the normal one is set at the end)
        p.getCooldowns().addCooldown(item.getItem(), 20 * 60);
        ServerLevel level = p.serverLevel();
        Vec3 at = p.position().add(0.0D, 1.6D, 0.0D);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.TRIDENT_RIPTIDE_1.value(), SoundSource.PLAYERS, 0.9F, 0.7F);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.PLAYERS, 0.7F, 1.5F);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.2F, 0.8F);
    }

    public static void onServerStopping() {
        CASTS.clear();
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        Iterator<Cast> it = CASTS.values().iterator();
        while (it.hasNext()) {
            Cast c = it.next();
            ServerPlayer p = c.player;
            if (p.isRemoved() || !p.isAlive() || c.age > MAX_AGE) {
                endCooldown(p);
                it.remove();
                continue;
            }
            c.age++;
            c.phaseAge++;
            ServerLevel level = p.serverLevel();
            if (c.phase == WINDUP) {
                if (c.phaseAge == WINDUP_TICKS - 6) level.playSound(null, p.getX(), p.getEyeY(), p.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 0.6F);
                if (c.phaseAge >= WINDUP_TICKS) slam(c, level);
            } else if (c.phase == WAVE) {
                advance(c, level);
            }
            for (Vortex v : c.vortices) if (!v.burst) pull(c, v, level);
            // the caster's camera NPC: its body every tick
            if (c.focusLaunched && !c.clientDone && c.focus != null && !c.focus.isRemoved()) {
                LivingEntity mob = c.focus;
                Vec3 pos = WheelRagdolls.rootPosition(mob);
                if (pos == null) pos = WheelRagdolls.isRagdolled(mob) ? c.last : centre(mob);
                if (pos.distanceTo(c.last) > MAX_STEP) pos = c.last;
                c.last = pos;
                sendCaster(p, ShockState.TRACK, mob.getId(), pos);
                if (c.age % 5 == 0) WheelMod.keepLoaded(level, pos);
            }
            boolean vorticesDone = true;
            for (Vortex v : c.vortices) vorticesDone &= v.burst;
            if (c.phase == AFTER && vorticesDone) {
                boolean focusDone = c.focus == null || !c.focusLaunched || c.clientDone || c.focus.isRemoved();
                if (!c.endSent && (c.focus == null || !c.focusLaunched)) {
                    // nobody to follow: the camera may go back
                    c.endSent = true;
                    sendCaster(p, ShockState.END, -1, c.front);
                }
                if (focusDone) {
                    endCooldown(p);
                    it.remove();
                }
            }
        }
    }

    // ------------------------------------------------------------------ the strike and the rift

    private static void slam(Cast c, ServerLevel level) {
        ServerPlayer p = c.player;
        Vec3 o = p.position().add(c.dir.scale(1.4D));
        double g = groundBelow(level, o.x, p.getY() + 1.5D, o.z, p.getY());
        c.front = new Vec3(o.x, g, o.z);
        c.phase = WAVE;
        c.phaseAge = 0;
        Vec3 f = c.front;
        broadcast(level, new ShockState(ShockState.SLAM, p.getId(), -1, f.x, f.y, f.z, (float) c.dir.x, (float) c.dir.z));
        level.playSound(null, f.x, f.y, f.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 1.2F, 0.5F);
        level.playSound(null, f.x, f.y, f.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.4F, 0.7F);
        level.playSound(null, f.x, f.y, f.z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 2.0F, 0.6F);
        level.playSound(null, f.x, f.y, f.z, SoundEvents.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 0.6F, 1.6F);
        BlockState ground = level.getBlockState(BlockPos.containing(f.x, f.y - 0.5D, f.z));
        if (!ground.isAir()) level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), f.x, f.y + 0.1D, f.z, 60, 1.2D, 0.2D, 1.2D, 0.3D);
        level.sendParticles(ParticleTypes.EXPLOSION, f.x, f.y + 0.3D, f.z, 2, 0.4D, 0.1D, 0.4D, 0.0D);
        for (int i = 0; i < 36; i++) {
            double a = i * Math.PI * 2.0D / 36.0D;
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, f.x, f.y + 0.2D, f.z, 0, Math.cos(a), 0.15D, Math.sin(a), 0.7D);
            if (i % 2 == 0) level.sendParticles(WheelEntity.dust(i % 4 == 0 ? 0x8FE9FF : 0xA45BFF, 1.5F),
                    f.x + Math.cos(a) * 1.2D, f.y + 0.2D, f.z + Math.sin(a) * 1.2D, 1, 0.0D, 0.05D, 0.0D, 0.0D);
        }
    }

    private static void advance(Cast c, ServerLevel level) {
        double step = WheelConfig.SHOCK_SPEED.get() / 20.0D;
        Vec3 f = c.front;
        double nx = f.x + c.dir.x * step, nz = f.z + c.dir.z * step;
        double g = groundBelow(level, nx, f.y + 2.0D, nz, Double.NaN);
        if (Double.isNaN(g) || g > f.y + 1.6D) {
            // a cliff or a wall: the rift ends here
            endWave(c, level);
            return;
        }
        c.front = new Vec3(nx, g, nz);
        c.travelled += step;
        f = c.front;
        broadcast(level, new ShockState(ShockState.FRONT, c.player.getId(), -1, f.x, f.y, f.z, (float) c.dir.x, (float) c.dir.z));
        RandomSource r = level.getRandom();
        BlockState ground = level.getBlockState(BlockPos.containing(f.x, f.y - 0.5D, f.z));
        if (!ground.isAir()) level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), f.x, f.y + 0.1D, f.z, 8, 0.5D, 0.1D, 0.5D, 0.25D);
        if (c.phaseAge % 3 == 0) {
            level.playSound(null, f.x, f.y, f.z, SoundEvents.DEEPSLATE_BREAK, SoundSource.PLAYERS, 1.1F, 0.5F + r.nextFloat() * 0.2F);
            level.playSound(null, f.x, f.y, f.z, SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.PLAYERS, 0.8F, 0.6F + r.nextFloat() * 0.3F);
        }
        catchNpcs(c, level);
        if (c.travelled >= WheelConfig.SHOCK_RANGE.get()) endWave(c, level);
    }

    private static void endWave(Cast c, ServerLevel level) {
        c.phase = AFTER;
        c.phaseAge = 0;
        Vec3 f = c.front;
        broadcast(level, new ShockState(ShockState.WAVE_END, c.player.getId(), -1, f.x, f.y, f.z, (float) c.dir.x, (float) c.dir.z));
        level.playSound(null, f.x, f.y, f.z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 1.4F, 0.8F);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, f.x, f.y + 0.3D, f.z, 25, 0.5D, 0.3D, 0.5D, 0.4D);
    }

    /** NPCs at the front of the rift are caught: into the nearest young vortex, or a new one right here. */
    private static void catchNpcs(Cast c, ServerLevel level) {
        Vec3 f = c.front;
        double w = WheelConfig.SHOCK_WIDTH.get();
        AABB box = new AABB(f, f).inflate(w + 1.0D, 3.0D, w + 1.0D);
        for (LivingEntity mob : level.getEntitiesOfClass(LivingEntity.class, box, BallistaServer::loadable)) {
            if (c.hit.contains(mob.getUUID()) || WheelMod.held(mob)) continue;
            double dx = mob.getX() - f.x, dz = mob.getZ() - f.z, reach = w + mob.getBbWidth() * 0.5D;
            if (dx * dx + dz * dz > reach * reach) continue;
            if (mob.getY() > f.y + 2.5D || mob.getY() + mob.getBbHeight() < f.y - 1.0D) continue;
            c.hit.add(mob.getUUID());
            int pull = WheelConfig.SHOCK_PULL.get();
            Vortex v = null;
            for (Vortex o : c.vortices) {
                if (!o.burst && o.age < pull / 2 && o.centre.distanceTo(f.add(0.0D, 1.0D, 0.0D)) < 3.5D) {
                    v = o;
                    break;
                }
            }
            if (v == null) {
                v = new Vortex(f.add(0.0D, 1.0D, 0.0D));
                c.vortices.add(v);
                Vec3 vc = v.centre;
                // dx carries the vortex's length (ticks) for the client's effects
                broadcast(level, new ShockState(ShockState.VORTEX, c.player.getId(), -1, vc.x, vc.y, vc.z, (float) pull, 0.0F));
                level.playSound(null, vc.x, vc.y, vc.z, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 1.2F, 1.3F);
                level.playSound(null, vc.x, vc.y, vc.z, SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 1.0F, 1.2F);
            }
            v.members.add(new Member(mob, v.age, v.members.size()));
            if (mob instanceof Mob m) m.getNavigation().stop();
            Vec3 mc = centre(mob);
            level.playSound(null, mc.x, mc.y, mc.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.6F, 0.7F);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, mc.x, mc.y, mc.z, 20, 0.3D, 0.5D, 0.3D, 0.3D);
            level.sendParticles(WheelEntity.dust(0x8FE9FF, 1.3F), mc.x, mc.y, mc.z, 14, 0.35D, 0.6D, 0.35D, 0.0D);
            if (c.focus == null) {
                c.focus = mob;
                c.last = mc;
                sendCaster(c.player, ShockState.CATCH, mob.getId(), v.centre);
            }
        }
    }

    // ------------------------------------------------------------------ the vortex

    private static void pull(Cast c, Vortex v, ServerLevel level) {
        v.age++;
        int pull = WheelConfig.SHOCK_PULL.get();
        Vec3 o = v.centre;
        double ground = o.y - 1.0D;
        double tt = Math.min(1.0D, v.age / (double) pull);
        double te = tt * tt * (3.0D - 2.0D * tt);
        // the vortex winds up: slow at first, whirling at the end
        v.spin += 0.05D + 0.42D * tt * tt;
        RandomSource r = level.getRandom();
        int n = 0;
        for (Member m : v.members) {
            LivingEntity mob = m.mob;
            if (mob.isRemoved() || !mob.isAlive() || WheelRagdolls.isRagdolled(mob)) continue;
            // each NPC has its own place on the ring (golden angle, outer rings for later ones), tightening and
            // rising as the vortex winds up
            double orbitR = Math.min(2.3D, 1.05D + 0.32D * m.slot) * (1.0D - 0.3D * te);
            double orbitA = v.spin + m.slot * 2.39996D;
            double rise = 0.3D + 1.5D * te + 0.12D * Math.sin(v.age * 0.3D + m.slot * 1.7D);
            Vec3 orbit = new Vec3(o.x + Math.cos(orbitA) * orbitR, ground + rise, o.z + Math.sin(orbitA) * orbitR);
            // first it is dragged from where it stood onto its place
            double inU = Mth.clamp((v.age - m.joinAge) / 22.0D, 0.0D, 1.0D);
            double inE = inU * inU * (3.0D - 2.0D * inU);
            Vec3 p = m.from.lerp(orbit, inE);
            mob.setPos(p.x, p.y, p.z);
            mob.setDeltaMovement(Vec3.ZERO);
            mob.fallDistance = 0.0F;
            // and it turns round and round, faster than it orbits
            float yaw = m.yaw0 + (float) Math.toDegrees(v.spin * 1.8D * inE);
            mob.setYRot(yaw);
            mob.setYHeadRot(yaw);
            mob.yBodyRot = yaw;
            mob.yBodyRotO = yaw;
            if (mob instanceof Mob mm) mm.getNavigation().stop();
            // caught in light: frost and violet sparks on the body, soul fire under the feet
            Vec3 mc = centre(mob);
            if ((v.age + m.slot) % 2 == 0) {
                level.sendParticles(WheelEntity.dust(r.nextBoolean() ? 0x8FE9FF : 0xA45BFF, 1.0F), mc.x, mc.y, mc.z, 2, 0.3D, 0.6D, 0.3D, 0.0D);
            }
            if ((v.age + m.slot) % 4 == 0) level.sendParticles(ParticleTypes.ELECTRIC_SPARK, mc.x, mc.y, mc.z, 2, 0.3D, 0.5D, 0.3D, 0.15D);
            if ((v.age + m.slot) % 5 == 0) level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y, p.z, 1, 0.15D, 0.05D, 0.15D, 0.01D);
            n++;
        }
        // light swirling up around the vortex
        double rad = 2.8D - 1.2D * te;
        for (int k = 0; k < 3; k++) {
            double a = v.spin * 1.3D + k * Math.PI * 2.0D / 3.0D;
            level.sendParticles(WheelEntity.dust(k == 0 ? 0xEFFFFF : k == 1 ? 0x8FE9FF : 0xA45BFF, 1.3F),
                    o.x + Math.cos(a) * rad, ground + 0.2D + r.nextDouble() * 2.6D, o.z + Math.sin(a) * rad, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        if (v.age % 2 == 0) level.sendParticles(ParticleTypes.REVERSE_PORTAL, o.x, o.y + 0.5D, o.z, 4, 1.0D, 0.8D, 1.0D, 0.05D);
        if (v.age % 6 == 0) level.sendParticles(ParticleTypes.END_ROD, o.x, ground + 0.2D, o.z, 3, 0.8D, 0.0D, 0.8D, 0.04D);
        // the roar of the vortex; it charges up before it bursts
        if (v.age % 12 == 0) level.playSound(null, o.x, o.y, o.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 0.7F, 1.4F + (float) tt * 0.5F);
        if (v.age % 20 == 10) level.playSound(null, o.x, o.y, o.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.0F, 0.6F + (float) tt * 0.8F);
        if (v.age == pull - 24) level.playSound(null, o.x, o.y, o.z, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 1.4F, 1.1F);
        if (v.age > pull - 12) level.sendParticles(ParticleTypes.END_ROD, o.x, o.y + 0.4D, o.z, 3, 1.4D, 1.0D, 1.4D, -0.08D);
        if (v.age >= pull || n == 0 && v.age > 2) burst(c, v, level);
    }

    private static void burst(Cast c, Vortex v, ServerLevel level) {
        v.burst = true;
        Vec3 o = v.centre;
        ServerPlayer p = c.player;
        RandomSource r = level.getRandom();
        broadcast(level, new ShockState(ShockState.BURST, p.getId(), -1, o.x, o.y, o.z, (float) c.dir.x, (float) c.dir.z));
        level.playSound(null, o.x, o.y, o.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 1.0F, 1.3F);
        level.playSound(null, o.x, o.y, o.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.3F, 0.9F);
        level.playSound(null, o.x, o.y, o.z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 2.0F, 1.2F);
        level.sendParticles(ParticleTypes.FLASH, o.x, o.y, o.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.SONIC_BOOM, o.x, o.y, o.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        for (int i = 0; i < 40; i++) {
            double a = i * Math.PI * 2.0D / 40.0D;
            level.sendParticles(i % 2 == 0 ? ParticleTypes.ELECTRIC_SPARK : ParticleTypes.END_ROD, o.x, o.y, o.z, 0, Math.cos(a), 0.2D + r.nextDouble() * 0.3D, Math.sin(a), 0.8D);
        }
        List<Member> alive = new ArrayList<>();
        for (Member m : v.members) if (!m.mob.isRemoved() && m.mob.isAlive() && !WheelRagdolls.isRagdolled(m.mob)) alive.add(m);
        int n = alive.size();
        double base = Math.atan2(c.dir.z, c.dir.x);
        if (n > 1) {
            Vec3 off = alive.get(0).mob.position().subtract(o);
            if (off.x * off.x + off.z * off.z > 1.0E-4D) base = Math.atan2(off.z, off.x);
        }
        double knock = WheelConfig.SHOCK_KNOCK.get(), lift = WheelConfig.SHOCK_LIFT.get();
        for (int i = 0; i < n; i++) {
            LivingEntity mob = alive.get(i).mob;
            mob.invulnerableTime = 0;
            mob.hurt(p.damageSources().playerAttack(p), WheelConfig.SHOCK_DAMAGE.get().floatValue());
            if (!mob.isAlive()) continue;
            // each one in its own direction: spread evenly around, a lone NPC goes forward and to a side
            double a = n == 1 ? base + (r.nextBoolean() ? 1.0D : -1.0D) * (0.45D + r.nextDouble() * 0.5D)
                    : base + i * Math.PI * 2.0D / n + (r.nextDouble() - 0.5D) * 0.4D;
            Vec3 d = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
            Vec3 vel = d.scale(knock * (0.85D + r.nextDouble() * 0.3D)).add(0.0D, lift * (0.9D + r.nextDouble() * 0.2D), 0.0D);
            Vec3 axis = new Vec3(0.0D, 1.0D, 0.0D).cross(d);
            Vec3 spin = (axis.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : axis.normalize()).scale(-1.8D)
                    .add(r.nextGaussian() * 0.4D, r.nextGaussian() * 0.4D, r.nextGaussian() * 0.4D);
            BallistaServer.launch(level, null, mob, vel, spin, true);
            if (mob == c.focus) {
                c.focusLaunched = true;
                c.last = centre(mob);
                sendCaster(p, ShockState.LAUNCH, mob.getId(), c.last);
            }
        }
        if (c.focus != null && !c.focusLaunched && v.members.stream().anyMatch(m -> m.mob == c.focus)) {
            // the camera's NPC is gone (died): nothing to follow
            c.focus = null;
        }
    }

    // ------------------------------------------------------------------ helpers

    static Vec3 centre(LivingEntity e) {
        return e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
    }

    private static double groundBelow(ServerLevel level, double x, double fromY, double z, double fallback) {
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (int by = Mth.floor(fromY); by >= Mth.floor(fromY) - 6; by--) {
            bp.set(Mth.floor(x), by, Mth.floor(z));
            BlockState st = level.getBlockState(bp);
            VoxelShape s = st.getCollisionShape(level, bp);
            if (!s.isEmpty()) return by + s.max(Direction.Axis.Y);
        }
        return fallback;
    }

    private static void endCooldown(ServerPlayer p) {
        var item = WheelMod.SHOCK_ITEM.get();
        p.getCooldowns().removeCooldown(item);
        int cd = WheelConfig.SHOCK_COOLDOWN.get();
        if (cd > 0) p.getCooldowns().addCooldown(item, cd);
    }

    private static void sendCaster(ServerPlayer p, int phase, int id, Vec3 pos) {
        PacketDistributor.sendToPlayer(p, new ShockState(phase, p.getId(), id, pos.x, pos.y, pos.z, 0.0F, 0.0F));
    }

    private static void broadcast(ServerLevel level, ShockState s) {
        PacketDistributor.sendToPlayersNear(level, null, s.x(), s.y(), s.z(), NEAR, s);
    }
}
