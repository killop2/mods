package com.example.wheelofwonder;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The only place that touches Sable: Ragdolls (sable_player_ragdoll), through reflection, the same way kickmod's
 * RagdollBridge does: no compile dependency, nothing bundled, and without the mod the wheel falls back to
 * holding the NPCs on its face as normal entities.
 *
 * Public API used: RagdollAPI.launchMob / isMobRagdolled / releaseMob (so the bodies are ordinary Sable ragdolls
 * that Ragdoll Reactions, kickmod and the corpse mod understand). To pin a body to the spinning wheel we also read
 * the ragdoll's physics parts (MobRagdollAssembly.RAGDOLL_STATES) and steer them with Sable's RigidBodyHandle
 * (SableBodies), the same calls the ragdoll mod uses for its own knock-ups.
 */
public final class WheelRagdolls {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String API = "dev.leo.sableplayerragdoll.api.RagdollAPI";
    private static final String ASSEMBLY = "dev.leo.sableplayerragdoll.mob.MobRagdollAssembly";
    private static final int FOREVER_TICKS = Integer.MAX_VALUE / 4;

    private static boolean resolved;
    private static boolean sable;
    private static Method launchMob;
    private static Constructor<?> optionsCtor;
    private static Method isMobRagdolled;
    private static Method releaseMob;
    private static Method hasPendingLaunch;
    private static Map<?, ?> states;
    private static Method rootPosition;
    private static Method selectRoot;
    private static Method stateParts;
    private static Method partSubLevel;
    private static Method partSpawn;
    private static java.util.Set<?> converted;
    private static Map<?, ?> pending;
    private static Map<?, ?> deferred;
    /** mob UUID -> server tick since when Sable marks it as a ragdoll although it has no body */
    private static final Map<Object, Integer> STALE_SINCE = new java.util.HashMap<>();

    /** One physics body of a ragdoll: the Sable ServerSubLevel (kept as Object so this class never links Sable). */
    public record Body(Object subLevel, boolean root) {
    }

    private WheelRagdolls() {
    }

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        if (!ModList.get().isLoaded("sable_player_ragdoll")) {
            LOGGER.info("[wheelofwonder] Sable: Ragdolls not installed: NPCs stick to the wheel as normal entities");
            return;
        }
        sable = ModList.get().isLoaded("sable");
        try {
            Class<?> api = Class.forName(API);
            for (Method m : api.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                switch (m.getName()) {
                    case "launchMob" -> {
                        if (p.length == 5 && p[2] == Vec3.class && p[3] == Vec3.class) {
                            launchMob = m;
                            optionsCtor = p[4].getConstructor(int.class);
                        }
                    }
                    case "isMobRagdolled" -> {
                        if (p.length == 1) isMobRagdolled = m;
                    }
                    case "releaseMob" -> {
                        if (p.length == 1) releaseMob = m;
                    }
                    default -> {
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[wheelofwonder] Sable: Ragdolls API not available ({})", t.toString());
        }
        try {
            Class<?> asm = Class.forName(ASSEMBLY);
            Field f = asm.getDeclaredField("RAGDOLL_STATES");
            f.setAccessible(true);
            states = (Map<?, ?>) f.get(null);
            for (Method m : asm.getDeclaredMethods()) {
                switch (m.getName()) {
                    case "rootPosition" -> {
                        if (m.getParameterCount() == 1 && m.getReturnType() == Vec3.class) {
                            m.setAccessible(true);
                            rootPosition = m;
                        }
                    }
                    case "hasPendingLaunch" -> {
                        if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == UUID.class) {
                            m.setAccessible(true);
                            hasPendingLaunch = m;
                        }
                    }
                    case "selectRoot" -> {
                        if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == List.class) {
                            m.setAccessible(true);
                            selectRoot = m;
                        }
                    }
                    default -> {
                    }
                }
            }
            try {
                Field c = asm.getDeclaredField("CONVERTED_ENTITIES");
                c.setAccessible(true);
                converted = (java.util.Set<?>) c.get(null);
                Field p = asm.getDeclaredField("PENDING_LAUNCHES");
                p.setAccessible(true);
                pending = (Map<?, ?>) p.get(null);
                Field d = asm.getDeclaredField("DEFERRED_RESTORE_AT");
                d.setAccessible(true);
                deferred = (Map<?, ?>) d.get(null);
            } catch (Throwable t) {
                converted = null;
            }
            Class<?> state = Class.forName(ASSEMBLY + "$RagdollState");
            stateParts = state.getDeclaredMethod("parts");
            stateParts.setAccessible(true);
            Class<?> spawned = Class.forName(ASSEMBLY + "$SpawnedPart");
            partSubLevel = spawned.getDeclaredMethod("subLevel");
            partSubLevel.setAccessible(true);
            partSpawn = spawned.getDeclaredMethod("part");
            partSpawn.setAccessible(true);
        } catch (Throwable t) {
            LOGGER.info("[wheelofwonder] ragdoll body access unavailable, ragdolls will not be pinned to the wheel ({})", t.toString());
            states = null;
        }
        LOGGER.info("[wheelofwonder] ragdoll bridge: launch={}, release={}, bodies={}, sable={}",
                launchMob != null, releaseMob != null, states != null && stateParts != null, sable);
    }

    public static boolean available() {
        resolve();
        return launchMob != null;
    }

    public static boolean isRagdolled(LivingEntity mob) {
        resolve();
        if (isMobRagdolled == null) return false;
        try {
            return Boolean.TRUE.equals(isMobRagdolled.invoke(null, mob));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Sable sometimes keeps a mob in its "converted" set although the ragdoll body is gone (seen after re-joining a
     * world with saved ragdolls). Such an NPC stands normally, but every mod then treats it as lying: it takes no
     * hits, cannot be loaded into a ride and cannot be ragdolled again. Server, once a second: a mark without a body,
     * without a pending launch and without a waiting restore for 2 seconds is a leftover and is dropped.
     */
    public static void sweepStale(int tick) {
        resolve();
        if (converted == null || states == null) return;
        try {
            java.util.Set<Object> seen = new java.util.HashSet<>();
            for (Object id : converted.toArray()) {
                if (states.containsKey(id) || (pending != null && pending.containsKey(id)) || (deferred != null && deferred.containsKey(id))) continue;
                seen.add(id);
                Integer since = STALE_SINCE.putIfAbsent(id, tick);
                if (since != null && tick - since >= 40) {
                    converted.remove(id);
                    seen.remove(id);
                    LOGGER.info("[wheelofwonder] dropped a leftover ragdoll mark of {} (no ragdoll body)", id);
                }
            }
            STALE_SINCE.keySet().retainAll(seen);
        } catch (Throwable t) {
            converted = null;   // Sable changed: stop trying
        }
    }

    /** True while Sable is still waiting for the client to send the mob's model. */
    public static boolean isPending(LivingEntity mob) {
        resolve();
        if (hasPendingLaunch == null) return false;
        try {
            return Boolean.TRUE.equals(hasPendingLaunch.invoke(null, mob.getUUID()));
        } catch (Throwable t) {
            return false;
        }
    }

    /** @param seconds 0 = never gets up by itself */
    public static boolean launch(ServerLevel level, LivingEntity mob, Vec3 velocity, Vec3 spin, int seconds) {
        resolve();
        if (launchMob == null) return false;
        if (isRagdolled(mob)) return true;
        try {
            Object options = optionsCtor.newInstance(seconds <= 0 ? FOREVER_TICKS : seconds * 20);
            Object session = launchMob.invoke(null, level, mob, velocity, spin, options);
            return session != null || isRagdolled(mob) || isPending(mob);
        } catch (Throwable t) {
            LOGGER.warn("[wheelofwonder] ragdoll launch failed for {}", mob, t);
            return false;
        }
    }

    public static boolean release(LivingEntity mob) {
        resolve();
        if (releaseMob == null) return false;
        try {
            releaseMob.invoke(null, mob);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[wheelofwonder] ragdoll release failed for {}", mob, t);
            return false;
        }
    }

    /** World position of the ragdoll's root body (the torso), or null if there is no body yet. */
    public static Vec3 rootPosition(LivingEntity mob) {
        resolve();
        if (states == null || rootPosition == null) return null;
        try {
            Object state = states.get(mob.getUUID());
            if (state == null) return null;
            Object v = rootPosition.invoke(null, state);
            return v instanceof Vec3 pos ? pos : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** All physics bodies of the mob's ragdoll (root first), or an empty list. */
    public static List<Body> bodies(LivingEntity mob) {
        resolve();
        List<Body> out = new ArrayList<>();
        if (states == null || stateParts == null || !sable) return out;
        try {
            Object state = states.get(mob.getUUID());
            if (state == null) return out;
            List<?> parts = (List<?>) stateParts.invoke(state);
            Object root = selectRoot != null && !parts.isEmpty() ? selectRoot.invoke(null, parts) : (parts.isEmpty() ? null : parts.get(0));
            if (root != null) out.add(new Body(partSubLevel.invoke(root), true));
            for (Object p : parts) {
                if (p != root) out.add(new Body(partSubLevel.invoke(p), false));
            }
        } catch (Throwable t) {
            LOGGER.debug("[wheelofwonder] could not read ragdoll parts of {}", mob, t);
        }
        return out;
    }

    /** Sets the velocity (m/s) and spin (rad/s) of one ragdoll body. Silently does nothing without Sable. */
    public static void setVelocity(Body body, Vec3 linear, Vec3 angular) {
        if (!sable || body == null || body.subLevel() == null) return;
        try {
            SableBodies.setVelocity(body.subLevel(), linear, angular);
        } catch (Throwable t) {
            LOGGER.debug("[wheelofwonder] could not steer ragdoll body", t);
        }
    }

    /**
     * Throws the whole ragdoll as ONE rigid object: one shared spin, each part gets the velocity it has as a point
     * of that tumbling body (v + w x r), so the joints do not fight each other (no shaking in the air).
     * @return false if the mob has no ragdoll bodies
     */
    public static boolean throwRigid(LivingEntity mob, Vec3 vel, Vec3 spin) {
        List<Body> bodies = bodies(mob);
        if (bodies.isEmpty()) return false;
        Vec3 root = bodyPosition(bodies.get(0));
        for (Body b : bodies) {
            Vec3 p = root != null ? bodyPosition(b) : null;
            Vec3 pv = vel;
            if (p != null && p.distanceTo(root) < 4.0D) pv = vel.add(spin.cross(p.subtract(root)));
            setVelocity(b, pv, spin);
        }
        return true;
    }

    /** Steers the root body along a path: moves with {@code pathVel} (m/s) and corrects towards {@code target}. */
    public static void steerRoot(LivingEntity mob, Vec3 target, Vec3 pathVel, Vec3 spin) {
        List<Body> bodies = bodies(mob);
        Vec3 root = rootPosition(mob);
        if (bodies.isEmpty() || root == null || root.distanceTo(target) > 12.0D) return;
        Vec3 want = pathVel.add(target.subtract(root).scale(8.0D));
        if (want.length() > 25.0D) want = want.normalize().scale(25.0D);
        setVelocity(bodies.get(0), want, spin);
    }

    /**
     * Like steerRoot, for fast flights (the rocket): higher speed cap and a stronger pull, so the body keeps up
     * with a 30 m/s rocket; does nothing if the root is farther than {@code bail} from the target.
     */
    public static void steerRootFast(LivingEntity mob, Vec3 target, Vec3 pathVel, double cap, double bail) {
        List<Body> bodies = bodies(mob);
        Vec3 root = rootPosition(mob);
        if (bodies.isEmpty() || root == null || root.distanceTo(target) > bail) return;
        Vec3 want = pathVel.add(target.subtract(root).scale(10.0D));
        if (want.length() > cap) want = want.normalize().scale(cap);
        setVelocity(bodies.get(0), want, Vec3.ZERO);
    }

    /**
     * Telekinesis: the root body follows {@code target} moving with {@code pathVel} (m/s), turning with
     * {@code spin} (rad/s); the limbs dangle. Does nothing if the root is farther than {@code bail}.
     */
    public static void holdRootMoving(LivingEntity mob, Vec3 target, Vec3 pathVel, Vec3 spin, double cap, double bail) {
        List<Body> bodies = bodies(mob);
        Vec3 root = rootPosition(mob);
        if (bodies.isEmpty() || root == null || root.distanceTo(target) > bail) return;
        Vec3 want = pathVel.add(target.subtract(root).scale(9.0D));
        if (want.length() > cap) want = want.normalize().scale(cap);
        setVelocity(bodies.get(0), want, spin);
    }

    /** Pulls the ragdoll's root body to {@code target} and keeps it there (limbs dangle). */
    public static void holdRoot(LivingEntity mob, Vec3 target, Vec3 spin) {
        List<Body> bodies = bodies(mob);
        Vec3 root = rootPosition(mob);
        if (bodies.isEmpty() || root == null || root.distanceTo(target) > 12.0D) return;
        Vec3 want = target.subtract(root).scale(9.0D);
        if (want.length() > 20.0D) want = want.normalize().scale(20.0D);
        setVelocity(bodies.get(0), want, spin);
    }

    private static Method poseMethod, plotMethod, centerBlockMethod, transformMethod;

    /**
     * World position of one ragdoll body (centre of its plot's centre block, the way the ragdoll mod computes its
     * root position), or null if it cannot be read. Pure reflection: Sable's pose classes live in a nested jar.
     */
    public static Vec3 bodyPosition(Body body) {
        if (!sable || body == null || body.subLevel() == null) return null;
        try {
            Object sub = body.subLevel();
            if (poseMethod == null) {
                poseMethod = sub.getClass().getMethod("logicalPose");
                plotMethod = sub.getClass().getMethod("getPlot");
            }
            Object pose = poseMethod.invoke(sub);
            Object plot = plotMethod.invoke(sub);
            if (centerBlockMethod == null) centerBlockMethod = plot.getClass().getMethod("getCenterBlock");
            if (transformMethod == null) transformMethod = pose.getClass().getMethod("transformPosition", Vec3.class);
            Object block = centerBlockMethod.invoke(plot);
            Object v = transformMethod.invoke(pose, Vec3.atCenterOf((net.minecraft.core.Vec3i) block));
            return v instanceof Vec3 p ? p : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Adds {@code dv} (m/s) to one body's velocity, keeping its spin. */
    public static void addVelocity(Body body, Vec3 dv) {
        if (!sable || body == null || body.subLevel() == null) return;
        try {
            SableBodies.addVelocity(body.subLevel(), dv);
        } catch (Throwable t) {
            LOGGER.debug("[wheelofwonder] could not push ragdoll body", t);
        }
    }

    public static Vec3 velocity(Body body) {
        if (!sable || body == null || body.subLevel() == null) return Vec3.ZERO;
        try {
            return SableBodies.velocity(body.subLevel());
        } catch (Throwable t) {
            return Vec3.ZERO;
        }
    }
}
