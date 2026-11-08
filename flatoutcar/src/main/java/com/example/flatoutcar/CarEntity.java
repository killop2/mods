package com.example.flatoutcar;

import net.minecraft.nbt.CompoundTag;
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

/**
 * Аркадная машина. Физикой управляет клиент водителя (как у лодки/лошади),
 * без водителя физику считает сервер.
 * Все скорости здесь в блоках за тик (20 тиков = 1 секунда).
 */
public class CarEntity extends Entity {
    public static final float MAX_SPEED = 1.6F;      // ~32 м/с
    private static final float MAX_REVERSE = 0.35F;
    private static final float ACCEL = 0.04F;
    private static final float BRAKE = 0.07F;
    private static final float TURN = 4.2F;          // градусов за тик
    private static final double GRAVITY = 0.08D;

    private float speed;

    private int lerpSteps;
    private double lerpX, lerpY, lerpZ, lerpYRot;

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

    /** Скорость для катапультирования (блоков/тик): вперёд по ходу машины + небольшой подброс. */
    public Vec3 getEjectVelocity() {
        Vec3 f = this.forward();
        return new Vec3(f.x * this.speed, 0.1D, f.z * this.speed);
    }

    @Override
    public void tick() {
        super.tick();
        this.tickLerp();
        if (this.isControlledByLocalInstance()) {
            this.controlCar();
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
        this.speed = Mth.clamp(this.speed, -MAX_REVERSE, MAX_SPEED);
        if (Math.abs(this.speed) < 0.003F && Math.abs(throttle) < 0.01F) {
            this.speed = 0.0F;
        }

        float steerFactor = Mth.clamp(this.speed / 0.35F, -1.0F, 1.0F) * (ground ? 1.0F : 0.15F);
        this.setYRot(this.getYRot() - steer * TURN * steerFactor);

        Vec3 f = this.forward();
        double vy = this.getDeltaMovement().y;
        vy = ground ? -0.08D : (vy - GRAVITY) * 0.98D;
        this.setDeltaMovement(f.x * this.speed, vy, f.z * this.speed);

        this.move(MoverType.SELF, this.getDeltaMovement());

        if (this.horizontalCollision && Math.abs(this.speed) > 0.05F) {
            Vec3 dir = f.scale(Math.signum(this.speed));
            if (!(this.onGround() && this.tryStepUp(dir))) {
                this.speed *= 0.3F;   // удар
            }
        }
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
}
