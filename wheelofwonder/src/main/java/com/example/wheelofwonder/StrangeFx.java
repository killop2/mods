package com.example.wheelofwonder;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side particles of Strange's act: sparks spraying off the portal rings, snow blowing out of the other
 * world through the first one, mystic dust over the glyph circle and round the floating sorcerer, embers off the
 * hammer.
 */
final class StrangeFx {
    private StrangeFx() {
    }

    static void tick(StrangeEntity e) {
        Level level = e.level();
        RandomSource r = e.getRandom();
        double t = e.tickCount;
        double dr = StrangeEntity.doorRadius(t);
        if (dr > 0.2D) {
            sparks(level, r, e.doorCentre(), e.face(), dr, 4);
            // the other world behind the first door: snow blows out of it
            if (t < StrangeEntity.P1_CLOSE1) {
                for (int i = 0; i < 2; i++) {
                    Vec3 p = inDisc(r, e.doorCentre(), e.face(), dr * 0.85D);
                    Vec3 f = e.face();
                    level.addParticle(ParticleTypes.SNOWFLAKE, p.x, p.y, p.z, f.x * 0.08D, -0.01D, f.z * 0.08D);
                }
            }
        }
        double hr = StrangeEntity.hammerPortalRadius(t);
        if (hr > 0.2D) sparks(level, r, e.hammerPortal(), e.hammerDir(), hr, 6);
        // the glyph circle under the target
        if (t > StrangeEntity.CAST0 && t < StrangeEntity.HIT + 15) {
            double g = Math.min(1.0D, (t - StrangeEntity.CAST0) / StrangeEntity.GLYPH_IN) * 3.4D;
            Vec3 a = e.anchor();
            for (int i = 0; i < 2; i++) {
                double ang = r.nextDouble() * Math.PI * 2.0D;
                level.addParticle(WheelEntity.dust(r.nextBoolean() ? 0xFF8A1E : 0xFFD060, 1.0F),
                        a.x + Math.cos(ang) * g, e.groundY() + 0.15D, a.z + Math.sin(ang) * g, 0.0D, 0.02D, 0.0D);
            }
        }
        // the floating sorcerer
        float there = StrangeEntity.strangeAlpha(t);
        if (there > 0.1F && r.nextFloat() < 0.5F) {
            Vec3 f = e.strangeFeet(t);
            level.addParticle(ParticleTypes.ENCHANT, f.x + r.nextGaussian() * 0.4D, f.y + r.nextDouble() * 2.0D, f.z + r.nextGaussian() * 0.4D, 0.0D, -0.1D, 0.0D);
            if (r.nextFloat() < 0.4F) level.addParticle(WheelEntity.dust(0xFFB040, 0.7F), f.x + r.nextGaussian() * 0.25D, f.y - 0.1D, f.z + r.nextGaussian() * 0.25D, 0.0D, -0.02D, 0.0D);
        }
        // embers off the flying hammer
        if (t >= StrangeEntity.HAM0 && t <= StrangeEntity.HIT) {
            Vec3 c = e.hammerCenter(t);
            for (int i = 0; i < 4; i++) {
                level.addParticle(ParticleTypes.SMALL_FLAME, c.x + r.nextGaussian() * 0.4D, c.y + r.nextGaussian() * 0.4D, c.z + r.nextGaussian() * 0.4D, 0.0D, 0.0D, 0.0D);
            }
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, r.nextGaussian() * 0.2D, r.nextGaussian() * 0.2D, r.nextGaussian() * 0.2D);
        }
        // sparks still drifting round the place of the blow
        if (t > StrangeEntity.HIT && t < StrangeEntity.HIT + 12) {
            Vec3 f = e.impale();
            for (int i = 0; i < 4; i++) {
                level.addParticle(ParticleTypes.SMALL_FLAME, f.x, f.y, f.z, r.nextGaussian() * 0.3D, r.nextGaussian() * 0.3D + 0.1D, r.nextGaussian() * 0.3D);
            }
        }
    }

    /** Sparks flung off a ring of radius r round c (plane facing n), along the ring and a little outwards. */
    private static void sparks(Level level, RandomSource r, Vec3 c, Vec3 n, double rad, int count) {
        Vec3 u = n.cross(new Vec3(0.0D, 1.0D, 0.0D));
        u = u.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : u.normalize();
        Vec3 v = u.cross(n).normalize();
        for (int i = 0; i < count; i++) {
            double a = r.nextDouble() * Math.PI * 2.0D;
            Vec3 radial = u.scale(Math.cos(a)).add(v.scale(Math.sin(a)));
            Vec3 tangent = u.scale(-Math.sin(a)).add(v.scale(Math.cos(a)));
            Vec3 p = c.add(radial.scale(rad));
            Vec3 vel = tangent.scale(0.18D + r.nextDouble() * 0.12D).add(radial.scale(0.05D)).add(0.0D, -0.03D, 0.0D);
            level.addParticle(r.nextFloat() < 0.6F ? ParticleTypes.SMALL_FLAME : ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, vel.x, vel.y, vel.z);
            if (r.nextFloat() < 0.4F) level.addParticle(WheelEntity.dust(0xFFC040, 0.8F), p.x, p.y, p.z, 0.0D, 0.0D, 0.0D);
        }
    }

    private static Vec3 inDisc(RandomSource r, Vec3 c, Vec3 n, double rad) {
        Vec3 u = n.cross(new Vec3(0.0D, 1.0D, 0.0D));
        u = u.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : u.normalize();
        Vec3 v = u.cross(n).normalize();
        double a = r.nextDouble() * Math.PI * 2.0D, d = Math.sqrt(r.nextDouble()) * rad;
        return c.add(u.scale(Math.cos(a) * d)).add(v.scale(Math.sin(a) * d));
    }
}
