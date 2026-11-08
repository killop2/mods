package com.example.wheelofwonder;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Client-side particles of the Prism Splitter: glitter round the floating crystals and the towers, sparks off the loose crystals. */
final class PrismFx {
    private PrismFx() {
    }

    static void ambient(PrismCoasterEntity e) {
        Level level = e.level();
        RandomSource r = e.getRandom();
        if (r.nextFloat() < 0.35F) {
            // the floating crystals and the towers glitter
            Vec3[] spots = {new Vec3(11.0D, 11.0D, -10.0D), new Vec3(30.0D, 12.0D, -6.0D), new Vec3(2.0D, 6.5D, -2.7D), new Vec3(2.0D, 6.5D, 2.9D)};
            Vec3 q = e.toWorld(spots[r.nextInt(spots.length)]);
            level.addParticle(WheelEntity.dust(PrismCoasterEntity.PALETTE[r.nextInt(3)], 0.8F), q.x + r.nextGaussian() * 0.8D, q.y + r.nextGaussian() * 0.8D,
                    q.z + r.nextGaussian() * 0.8D, 0.0D, 0.0D, 0.0D);
        }
        if (r.nextFloat() < 0.15F) {
            Vec3 q = e.toWorld(PrismCoasterEntity.trackLocal(r.nextDouble() * PrismCoasterEntity.L));
            level.addParticle(ParticleTypes.END_ROD, q.x, q.y + 0.1D, q.z, 0.0D, 0.01D, 0.0D);
        }
        float[] fc = e.freeCrystals(0.0F);
        if (fc != null && e.viewState() == PrismCoasterEntity.FLIGHT) {
            int i = r.nextInt(PrismCoasterEntity.N);
            int o = i * PrismCoasterEntity.FLOATS;
            level.addParticle(WheelEntity.dust(PrismCoasterEntity.PALETTE[i % PrismCoasterEntity.PALETTE.length], 0.9F), fc[o], fc[o + 1], fc[o + 2], 0.0D, 0.0D, 0.0D);
        }
    }
}
