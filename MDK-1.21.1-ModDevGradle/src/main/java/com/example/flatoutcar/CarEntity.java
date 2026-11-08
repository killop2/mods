package com.example.flatoutcar;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Аркадная машина. Физикой управляет клиент водителя (как у лодки/лошади),
 * без водителя физику считает сервер.
 * Все скорости здесь в блоках за тик (20 тиков = 1 секунда).
 */
public class CarEntity extends Entity {
    public static final float MAX_SPEED = 1.6F;      // ~32 м/с, ~115 км/ч
    private static final float MAX_REVERSE = 0.35F;
    private static final float ACCEL = 0.04F;
    private static final float BRAKE = 0.07F;
    private static final float TURN = 4.2F;          // градусов за тик
    private static final double GRAVITY = 0.08D;

    /** Длина машины от центра до бампера (модель 5.9 блока) и зона проверки перед ним. */
    public static final double HALF_LENGTH = 2.95D;
    private static final double PROBE_HALF_WIDTH = 0.9D;
    /** Удар считается фронтальным, если вдоль хода машина прошла меньше этой доли от задуманного. */
    private static final double FRONTAL_RATIO = 0.5D;
    /** Скорость, ниже которой удар — просто лёгкий толчок без звука. */
    private static final float BUMP_MIN_SPEED = 0.15F;

    /** Клиентские хуки (звук мотора, отправка пакета при ударе). Ставятся в ClientSetup, на сервере null. */
    public interface ClientHooks {
        void tick(CarEntity car);

        void crash(CarEntity car, float speedBeforeImpact, boolean eject);
    }

    public static ClientHooks clientHooks;

    private float speed;

    private int lerpSteps;
    private double lerpX, lerpY, lerpZ, lerpYRot;

    /** Только для клиента: поворот колёс (рад), положение руля (-1..1, + = влево), скорость по факту (блок/тик). */
    private static final float WHEEL_RADIUS = 0.43F;
    private float wheelSpin, wheelSpinO;
    private float steerVisual, steerVisualO;
    private float visualSpeed;
    /** Ссылка на играющий звук мотора (тип скрыт, чтобы общий код не грузил клиентские классы). */
    public Object engineSound;
    /** Клиент водителя: id сбитых сущностей -> тик удара (чтобы не тормозить об одного моба дважды). */
    private final Map<Integer, Integer> clientHits = new HashMap<>();

    /** Сервер: прошлая позиция, оценка скорости (блоки/тик) и кулдаун ударов по мобам. */
    private double lastServerX, lastServerZ;
    private boolean hasLastServerPos;
    private double serverSpeed;
    private final Map<UUID, Long> serverHits = new HashMap<>();

    public CarEntity(EntityType<? extends CarEntity> type, Level level) {
        super(type, level);
        this.blocksBuilding = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    // ---------- взаимодействие ----------

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        if (!this.level().isClientSide) {
            return player.startRiding(this) ? InteractionResult.CONSUME : InteractionResult.PASS;
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isInvulnerableTo(source)) {
            return false;
        }
        if (source.getEntity() instanceof Player p && p.getVehicle() == this) {
            return false;
        }
        if (!this.level().isClientSide && !this.isRemoved() && source.getEntity() instanceof Player) {
            this.spawnAtLocation(FlatoutCarMod.CAR_ITEM.get());
            this.discard();
        }
        return true;
    }

    @Override
    public boolean isPickable() {
        return !this.isRemoved();
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(FlatoutCarMod.CAR_ITEM.get());
    }

    @Override
    public boolean canAddPassenger(Entity passenger) {
        return this.getPassengers().isEmpty();
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        // только что вышедшего/вылетевшего водителя своя же машина не сбивает (~3.5 с)
        if (!this.level().isClientSide) {
            this.serverHits.put(passenger.getUUID(), this.level().getGameTime() + 40L);
        } else {
            this.clientHits.put(passenger.getId(), this.tickCount + 40);
        }
    }

    @Override
    public LivingEntity getControllingPassenger() {
        return this.getFirstPassenger() instanceof LivingEntity living ? living : null;
    }

    // ---------- синхронизация для чужих клиентов ----------

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpYRot = yRot;
        this.lerpSteps = 10;
    }

    private void tickLerp() {
        if (this.isControlledByLocalInstance()) {
            this.lerpSteps = 0;
            this.syncPacketPositionCodec(this.getX(), this.getY(), this.getZ());
        }
        if (this.lerpSteps > 0) {
            double d = 1.0D / this.lerpSteps;
            this.setPos(Mth.lerp(d, this.getX(), this.lerpX),
                    Mth.lerp(d, this.getY(), this.lerpY),
                    Mth.lerp(d, this.getZ(), this.lerpZ));
            this.setYRot(this.getYRot() + (float) Mth.wrapDegrees(this.lerpYRot - this.getYRot()) * (float) d);
            this.setRot(this.getYRot(), 0.0F);
            this.lerpSteps--;
        }
    }

    // ---------- физика ----------

    public Vec3 forward() {
        float r = this.getYRot() * ((float) Math.PI / 180F);
        return new Vec3(-Mth.sin(r), 0.0D, Mth.cos(r));
    }

    public float getSpeed() {
        return this.speed;
    }

    public void setSpeed(float speed) {
        this.speed = speed;
    }

    @Override
    public void tick() {
        super.tick();
        this.tickLerp();
        if (this.isControlledByLocalInstance()) {
            this.controlCar();
        }
        if (this.level().isClientSide) {
            this.tickWheels();
            if (clientHooks != null) {
                clientHooks.tick(this);
            }
        } else {
            this.tickServerMobHits();
        }
    }

    /**
     * Наезд на мобов считает сервер: позицию машины присылает клиент водителя,
     * скорость оцениваем по смещению между тиками. Работает и без водителя (машина катится по инерции).
     */
    private void tickServerMobHits() {
        double dx = this.getX() - this.lastServerX, dz = this.getZ() - this.lastServerZ;
        boolean valid = this.hasLastServerPos;
        this.lastServerX = this.getX();
        this.lastServerZ = this.getZ();
        this.hasLastServerPos = true;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (!valid || dist > 4.0D) {
            this.serverSpeed = 0.0D;   // телепорт или первый тик
            return;
        }
        Vec3 f = this.forward();
        double signed = dx * f.x + dz * f.z;
        if (dist > 1.0E-4D) {
            // пакеты движения иногда приходят пачкой — сглаживаем
            this.serverSpeed = this.serverSpeed * 0.5D + signed * 0.5D;
        } else {
            this.serverSpeed *= 0.5D;
        }
        if (!CarConfig.mobsEnabled() || Math.abs(this.serverSpeed) < CarConfig.mobMinSpeedPerTick()) {
            return;
        }
        long now = this.level().getGameTime();
        if ((now & 63L) == 0L) {
            this.serverHits.values().removeIf(t -> now - t > 40L);
        }
        Vec3 move = f.scale(Math.signum(this.serverSpeed));
        LivingEntity driver = this.getControllingPassenger();
        for (LivingEntity e : MobImpact.findOverlapping(this)) {
            if (e == driver) {
                continue;
            }
            Long last = this.serverHits.get(e.getUUID());
            if (last != null && now - last < 30L) {
                continue;
            }
            this.serverHits.put(e.getUUID(), now);
            ServerMobHits.hit((ServerLevel) this.level(), this, e, move, Math.abs(this.serverSpeed) * 20.0D);
        }
    }

    private void controlCar() {
        float throttle = 0.0F;
        float steer = 0.0F;
        LivingEntity driver = this.getControllingPassenger();
        if (driver != null) {
            throttle = driver.zza;
            steer = driver.xxa;
        }
        boolean ground = this.onGround();

        if (throttle > 0.01F) {
            if (this.speed < 0.0F) {
                this.speed += BRAKE;
            } else {
                this.speed += ACCEL * Math.max(0.0F, 1.15F - this.speed / MAX_SPEED);
            }
        } else if (throttle < -0.01F) {
            if (this.speed > 0.02F) {
                this.speed -= BRAKE;
            } else {
                this.speed -= 0.025F;
            }
        } else {
            this.speed *= ground ? 0.97F : 0.998F;
        }
        this.speed *= ground ? 0.995F : 0.999F;
        this.speed = Mth.clamp(this.speed, -MAX_SPEED, MAX_SPEED);
        if (this.speed < -MAX_REVERSE) {
            this.speed = -MAX_REVERSE;
        }
        if (Math.abs(this.speed) < 0.003F && Math.abs(throttle) < 0.01F) {
            this.speed = 0.0F;
        }

        float steerFactor = Mth.clamp(this.speed / 0.35F, -1.0F, 1.0F) * (ground ? 1.0F : 0.15F);
        this.setYRot(this.getYRot() - steer * TURN * steerFactor);

        Vec3 f = this.forward();
        double vy = this.getDeltaMovement().y;
        vy = ground ? -0.08D : (vy - GRAVITY) * 0.98D;
        Vec3 wanted = new Vec3(f.x * this.speed, vy, f.z * this.speed);

        float speedBefore = this.speed;
        boolean fast = Math.abs(speedBefore) >= CarConfig.crashMinSpeedPerTick();
        boolean noseBlocked = false;
        boolean entityHit = false;
        if (Math.abs(this.speed) > 0.01F) {
            AABB probe = this.noseProbe(f, Math.signum(this.speed)).move(wanted.x, 0.0D, wanted.z);
            noseBlocked = this.level().getBlockCollisions(this, probe).iterator().hasNext()
                    // над препятствием свободно — это подъём из плит/ступенек, его решает обычный заезд хитбокса
                    && this.level().getBlockCollisions(this, probe.move(0.0D, 0.6D, 0.0D)).iterator().hasNext();
            if (!noseBlocked && fast) {
                entityHit = this.hitsEntity(probe.expandTowards(0.0D, -0.6D, 0.0D), driver);
            }
        }
        if (noseBlocked) {
            wanted = new Vec3(0.0D, vy, 0.0D);   // бампер упёрся — дальше не едем
        }

        // лёгкие мобы/NPC: машина сбивает их и теряет часть скорости (сам отлёт моба считает сервер)
        if (!entityHit && CarConfig.mobsEnabled() && Math.abs(this.speed) >= CarConfig.mobMinSpeedPerTick()) {
            float sign = Math.signum(this.speed);
            for (LivingEntity e : MobImpact.findOverlapping(this)) {
                if (e == driver || MobImpact.isHeavy(e)) {
                    continue;
                }
                Integer last = this.clientHits.get(e.getId());
                if (last != null && this.tickCount - last < 30) {
                    continue;
                }
                this.clientHits.put(e.getId(), this.tickCount);
                this.speed -= sign * MobImpact.carSpeedLoss(e, this.speed);
            }
            if ((this.tickCount & 63) == 0) {
                this.clientHits.values().removeIf(t -> this.tickCount - t > 40);
            }
            if (this.speed != speedBefore) {
                wanted = new Vec3(f.x * this.speed, wanted.y, f.z * this.speed);
            }
        }
        this.setDeltaMovement(wanted);

        double x0 = this.getX(), z0 = this.getZ();
        this.move(MoverType.SELF, this.getDeltaMovement());

        boolean impact = noseBlocked || entityHit;
        if (!impact && this.horizontalCollision && Math.abs(this.speed) > 0.05F) {
            Vec3 dir = f.scale(Math.signum(this.speed));
            if (!(this.onGround() && this.tryStepUp(dir))) {
                double along = ((this.getX() - x0) * f.x + (this.getZ() - z0) * f.z) / this.speed;
                if (along < FRONTAL_RATIO) {
                    impact = true;      // лобовой удар
                } else {
                    this.speed *= 0.9F; // скользим вдоль стены
                }
            }
        }

        if (impact) {
            this.speed = noseBlocked ? -speedBefore * 0.1F : speedBefore * 0.3F;
            if (Math.abs(speedBefore) >= BUMP_MIN_SPEED && this.level().isClientSide && clientHooks != null
                    && driver instanceof Player) {
                boolean eject = fast && CarConfig.crashEjectEnabled();
                clientHooks.crash(this, speedBefore, eject);
            }
        }
    }

    /** Зона перед бампером (или за задним бампером при движении назад), выше ступенек. */
    private AABB noseProbe(Vec3 f, float sign) {
        double cx = this.getX() + f.x * HALF_LENGTH * sign;
        double cz = this.getZ() + f.z * HALF_LENGTH * sign;
        return new AABB(cx - PROBE_HALF_WIDTH, this.getY() + 0.6D, cz - PROBE_HALF_WIDTH,
                cx + PROBE_HALF_WIDTH, this.getY() + 1.4D, cz + PROBE_HALF_WIDTH);
    }

    private boolean hitsEntity(AABB box, LivingEntity driver) {
        // лёгких живых мобов машина сбивает (см. MobImpact), «стеной» считаются тяжёлые мобы и прочие сущности
        boolean mobs = CarConfig.mobsEnabled();
        return !this.level().getEntities(this, box, e -> e != driver && e.isAlive() && e.isPickable()
                && !e.isSpectator() && e.getRootVehicle() != this
                && !(mobs && e instanceof LivingEntity le && !MobImpact.isHeavy(le))).isEmpty();
    }

    /** Заезд на ступеньку/плиту высотой до полублока. */
    private boolean tryStepUp(Vec3 dir) {
        double h = 0.5625D;
        AABB box = this.getBoundingBox().move(dir.x * 0.4D, h, dir.z * 0.4D);
        if (this.level().noCollision(this, box)) {
            this.setPos(this.getX() + dir.x * 0.2D, this.getY() + h, this.getZ() + dir.z * 0.2D);
            return true;
        }
        return false;
    }

    // ---------- визуал (клиент) ----------

    private void tickWheels() {
        Vec3 f = this.forward();
        double moved = (this.getX() - this.xo) * f.x + (this.getZ() - this.zo) * f.z;
        this.visualSpeed = (float) moved;
        this.wheelSpinO = this.wheelSpin;
        this.wheelSpin += (float) (moved / WHEEL_RADIUS);
        if (Math.abs(this.wheelSpin) > 1000.0F) {
            float wrap = (float) (Math.PI * 2.0D) * Math.round(this.wheelSpin / (Math.PI * 2.0D));
            this.wheelSpin -= wrap;
            this.wheelSpinO -= wrap;
        }

        float target;
        LivingEntity driver = this.getControllingPassenger();
        if (this.isControlledByLocalInstance() && driver != null) {
            target = driver.xxa;
        } else {
            // у чужих машин руль восстанавливаем по скорости поворота
            float dYaw = Mth.wrapDegrees(this.getYRot() - this.yRotO);
            target = Mth.clamp(-dYaw / TURN, -1.0F, 1.0F) * (moved < 0 ? -1.0F : 1.0F);
        }
        this.steerVisualO = this.steerVisual;
        this.steerVisual += (Mth.clamp(target, -1.0F, 1.0F) - this.steerVisual) * 0.35F;
    }

    public float getWheelSpin(float partialTick) {
        return Mth.lerp(partialTick, this.wheelSpinO, this.wheelSpin);
    }

    public float getSteerVisual(float partialTick) {
        return Mth.lerp(partialTick, this.steerVisualO, this.steerVisual);
    }

    /** Фактическая скорость на клиенте (блоки/тик), для звука мотора. */
    public float getVisualSpeed() {
        return this.visualSpeed;
    }
}
