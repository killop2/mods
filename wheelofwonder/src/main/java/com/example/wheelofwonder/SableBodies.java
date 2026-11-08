package com.example.wheelofwonder;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Direct calls into Sable's physics API. Only ever loaded through WheelRagdolls after it has checked that Sable is
 * installed, so the mod still starts without Sable.
 */
final class SableBodies {
    private SableBodies() {
    }

    private static RigidBodyHandle handle(Object subLevel) {
        RigidBodyHandle h = RigidBodyHandle.of((ServerSubLevel) subLevel);
        return h != null && h.isValid() ? h : null;
    }

    static Vec3 velocity(Object subLevel) {
        RigidBodyHandle h = handle(subLevel);
        if (h == null) return Vec3.ZERO;
        Vector3d v = h.getLinearVelocity(new Vector3d());
        return new Vec3(v.x, v.y, v.z);
    }

    /** Adds to the body's velocity (its spin is left alone). */
    static void addVelocity(Object subLevel, Vec3 dv) {
        RigidBodyHandle h = handle(subLevel);
        if (h == null) return;
        h.addLinearAndAngularVelocity(new Vector3d(dv.x, dv.y, dv.z), new Vector3d());
    }

    /** Replaces the body's velocity: adds the difference between the wanted and the current velocity. */
    static void setVelocity(Object subLevel, Vec3 linear, Vec3 angular) {
        RigidBodyHandle h = handle(subLevel);
        if (h == null) return;
        Vector3d v = h.getLinearVelocity(new Vector3d());
        Vector3d w = h.getAngularVelocity(new Vector3d());
        h.addLinearAndAngularVelocity(
                new Vector3d(linear.x - v.x, linear.y - v.y, linear.z - v.z),
                new Vector3d(angular.x - w.x, angular.y - w.y, angular.z - w.z));
    }
}
