package com.example.flatoutcar;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Физика наезда машины на моба/NPC/игрока (общая для клиента и сервера).
 *
 * Модель — неупругий удар двух тел (как в анализе наездов на пешеходов):
 *   скорость моба после удара  v_m = v_car * (1 + e) * M / (M + m)
 *   потеря скорости машины    dv  = v_car * (1 + e) * m / (M + m)
 * где M — масса машины, m — масса моба (по объёму хитбокса), e — коэффициент восстановления.
 * Угол вылета: если центр масс моба выше капота — моба «подбрасывает» через капот (крутой угол),
 * если ниже (курица, кролик) — его толкает вперёд почти горизонтально. Скользящий удар добавляет боковую скорость.
 */
public final class MobImpact {
    /** Масса машины, кг (BMW M3 GTR ~1300 кг). */
    public static final double CAR_MASS = 1300.0D;
    /** Плотность «тела» моба по объёму хитбокса, кг/м³ (игрок 0.6×0.6×1.8 → ~70 кг). */
    private static final double MOB_DENSITY = 110.0D;
    private static final double RESTITUTION = 0.15D;
    /** Высота передней кромки капота над землёй, блоков. */
    private static final double HOOD_HEIGHT = 0.85D;
    /** Половина ширины машины, блоков (модель 2.8). */
    public static final double HALF_WIDTH = 1.4D;
    /** Моб тяжелее этой доли массы машины — для водителя это столкновение, как со стеной. */
    public static final double HEAVY_RATIO = 0.4D;

    private MobImpact() {
    }

    public static double mass(LivingEntity e) {
        double w = e.getBbWidth();
        return Math.max(2.0D, w * w * e.getBbHeight() * MOB_DENSITY);
    }

    public static boolean isHeavy(LivingEntity e) {
        return mass(e) >= CAR_MASS * HEAVY_RATIO;
    }

    /** Потеря скорости машины (блоки/тик) при ударе о моба. */
    public static float carSpeedLoss(LivingEntity e, float carSpeed) {
        double m = mass(e);
        return (float) (Math.abs(carSpeed) * (1.0D + RESTITUTION) * m / (CAR_MASS + m));
    }

    /** Подходит ли сущность как цель наезда. */
    public static boolean canHit(CarEntity car, LivingEntity e) {
        return e.isAlive() && !e.isSpectator() && !(e instanceof ArmorStand)
                && e.getRootVehicle() != car && !e.isPassenger();
    }

    /** Живые сущности, которые сейчас пересекают корпус машины (повёрнутый прямоугольник 5.9 × 2.8 × 1.7). */
    public static List<LivingEntity> findOverlapping(CarEntity car) {
        Vec3 f = car.forward();
        AABB search = car.getBoundingBox().inflate(CarEntity.HALF_LENGTH - car.getBbWidth() / 2.0D + 0.5D, 0.0D,
                CarEntity.HALF_LENGTH - car.getBbWidth() / 2.0D + 0.5D);
        return car.level().getEntitiesOfClass(LivingEntity.class, search, e -> {
            if (!canHit(car, e)) {
                return false;
            }
            double rx = e.getX() - car.getX(), rz = e.getZ() - car.getZ();
            double along = rx * f.x + rz * f.z;
            double side = rx * -f.z + rz * f.x;
            double r = e.getBbWidth() / 2.0D;
            return Math.abs(along) <= CarEntity.HALF_LENGTH + r && Math.abs(side) <= HALF_WIDTH + r
                    && e.getBoundingBox().maxY > car.getY() + 0.1D && e.getBoundingBox().minY < car.getY() + 1.7D;
        });
    }

    /**
     * Скорость вылета моба, м/с.
     * @param car   машина
     * @param e     моб
     * @param move  направление движения машины (горизонтальный единичный вектор)
     * @param carMps скорость машины, м/с
     */
    public static Vec3 launchVelocity(CarEntity car, LivingEntity e, Vec3 move, double carMps, RandomSource rnd) {
        double m = mass(e);
        double v = carMps * (1.0D + RESTITUTION) * CAR_MASS / (CAR_MASS + m) * (0.9D + 0.2D * rnd.nextDouble());

        // угол вылета от высоты центра масс относительно капота
        double com = e.getY() + e.getBbHeight() * 0.55D - car.getY();
        double angleDeg = com > HOOD_HEIGHT
                ? 18.0D + 10.0D * rnd.nextDouble()   // подброс через капот
                : 6.0D + 6.0D * rnd.nextDouble();     // низкий моб — толчок вперёд
        double a = Math.toRadians(angleDeg);

        // скользящий удар: боковая составляющая от смещения относительно оси машины
        Vec3 right = new Vec3(-move.z, 0.0D, move.x);
        double side = (e.getX() - car.getX()) * right.x + (e.getZ() - car.getZ()) * right.z;
        double lateral = Mth.clamp(side / HALF_WIDTH, -1.0D, 1.0D) * 0.3D;

        Vec3 horiz = move.add(right.scale(lateral)).normalize().scale(v * Math.cos(a));
        return new Vec3(horiz.x, v * Math.sin(a), horiz.z);
    }
}
