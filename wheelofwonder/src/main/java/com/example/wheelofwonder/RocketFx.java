package com.example.wheelofwonder;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Client-side particles of the rocket ride and the flying rocket: a handful per tick, vanilla types only. */
final class RocketFx {
    private RocketFx() {
    }

    static void rideAmbient(RocketRideEntity e) {
        Level level = e.level();
        RandomSource r = e.getRandom();
        int t = e.tickCount;
        // the jester's crown smoulders
        if (t % 10 == 0) {
            Vec3 m = e.position().add(0.0D, 4.6D, 0.0D);
            level.addParticle(ParticleTypes.WITCH, m.x + (r.nextDouble() - 0.5D) * 0.6D, m.y, m.z + (r.nextDouble() - 0.5D) * 0.6D, 0.0D, 0.02D, 0.0D);
        }
        float w = e.omega(0.0F) / RocketRideEntity.OMEGA;
        if (w <= 0.01F) return;
        // spinning: sparks from the hub, steam from the pistons, confetti thrown off the cars
        Vec3 hub = e.position().add(0.0D, RocketRideEntity.ARM_Y, 0.0D);
        if (r.nextFloat() < w) {
            double a = r.nextDouble() * Math.PI * 2.0D;
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, hub.x + Math.cos(a) * 0.65D, hub.y, hub.z + Math.sin(a) * 0.65D,
                    Math.cos(a) * 0.15D, 0.05D, Math.sin(a) * 0.15D);
        }
        if (t % 4 == 0) {
            double a = r.nextDouble() * Math.PI * 2.0D;
            level.addParticle(ParticleTypes.CLOUD, hub.x + Math.cos(a) * 0.5D, hub.y - 0.3D, hub.z + Math.sin(a) * 0.5D, 0.0D, 0.02D, 0.0D);
        }
        float ang = e.viewAngle(0.0F), tilt = e.tilt(0.0F);
        if (t % 2 == 0) {
            int k = r.nextInt(RocketRideEntity.CARS);
            Vec3 c = e.toWorld(RocketRideEntity.carLocal(k, ang, tilt));
            int col = WheelEntity.BULB_COLORS[r.nextInt(WheelEntity.BULB_COLORS.length)];
            level.addParticle(WheelEntity.dust(col, 1.0F), c.x, c.y, c.z, 0.0D, 0.0D, 0.0D);
        }
        if (t % 7 == 0) level.addParticle(ParticleTypes.NOTE, hub.x, hub.y + 2.8D, hub.z, r.nextDouble(), 0.0D, 0.0D);
        // the loaded rocket's engine lights up before the launch
        if (e.state() == RocketRideEntity.LOADED && e.age() > RocketRideEntity.launchAt() - 30) {
            Vec3 c = e.toWorld(RocketRideEntity.carLocal(0, ang, tilt));
            Vec3 back = e.dirToWorld(RocketRideEntity.tangentLocal(0, ang)).scale(-1.05D);
            Vec3 tail = c.add(back);
            for (int i = 0; i < 2; i++) {
                level.addParticle(i == 0 ? ParticleTypes.FLAME : ParticleTypes.SOUL_FIRE_FLAME, tail.x, tail.y, tail.z,
                        back.x * 0.08D + (r.nextDouble() - 0.5D) * 0.03D, 0.01D, back.z * 0.08D + (r.nextDouble() - 0.5D) * 0.03D);
            }
            if (t % 2 == 0) level.addParticle(ParticleTypes.SMOKE, tail.x, tail.y, tail.z, 0.0D, 0.03D, 0.0D);
        }
    }

    static void flight(RocketEntity e) {
        Level level = e.level();
        RandomSource r = e.getRandom();
        Vector3f d = e.dir();
        Vec3 back = new Vec3(-d.x, -d.y, -d.z);
        Vec3 tail = e.position().add(back.scale(1.15D));
        if (e.thrusting()) {
            // fire and soul fire from the nozzle, a smoke trail and carnival sparks
            for (int i = 0; i < 2; i++) {
                level.addParticle(ParticleTypes.FLAME, tail.x, tail.y, tail.z,
                        back.x * 0.15D + (r.nextDouble() - 0.5D) * 0.05D, back.y * 0.15D + (r.nextDouble() - 0.5D) * 0.05D, back.z * 0.15D + (r.nextDouble() - 0.5D) * 0.05D);
            }
            level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, tail.x, tail.y, tail.z, back.x * 0.1D, back.y * 0.1D, back.z * 0.1D);
            level.addParticle(ParticleTypes.LARGE_SMOKE, tail.x, tail.y, tail.z, 0.0D, 0.02D, 0.0D);
        } else if (e.tickCount % 2 == 0) {
            level.addParticle(ParticleTypes.SMOKE, tail.x, tail.y, tail.z, 0.0D, 0.02D, 0.0D);
        }
        if (e.tickCount % 2 == 0) {
            int col = e.tickCount % 4 == 0 ? 0xC84BFF : 0xFF8A1F;
            level.addParticle(WheelEntity.dust(col, 1.5F), tail.x, tail.y, tail.z, 0.0D, 0.0D, 0.0D);
        }
        if (e.tickCount % 5 == 0) level.addParticle(ParticleTypes.FIREWORK, tail.x, tail.y, tail.z, (r.nextDouble() - 0.5D) * 0.1D, 0.05D, (r.nextDouble() - 0.5D) * 0.1D);
    }
}
