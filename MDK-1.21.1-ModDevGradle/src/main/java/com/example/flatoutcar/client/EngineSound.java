package com.example.flatoutcar.client;

import com.example.flatoutcar.CarEntity;
import com.example.flatoutcar.FlatoutCarMod;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * Зацикленный звук мотора, привязанный к машине: высота тона и громкость растут со скоростью.
 * Один экземпляр на машину с водителем; когда водитель вышел — затихает и останавливается.
 */
public class EngineSound extends AbstractTickableSoundInstance {
    private final CarEntity car;
    private float throttleSmooth;

    public EngineSound(CarEntity car) {
        super(FlatoutCarMod.ENGINE_SOUND.get(), SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
        this.car = car;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.01F;
        this.pitch = 0.5F;
        this.x = car.getX();
        this.y = car.getY();
        this.z = car.getZ();
    }

    @Override
    public void tick() {
        if (this.car.isRemoved()) {
            this.stop();
            return;
        }
        boolean driven = this.car.getControllingPassenger() != null;
        float load = Math.min(1.0F, Math.abs(this.car.getVisualSpeed()) / CarEntity.MAX_SPEED);
        this.throttleSmooth += ((driven ? load : 0.0F) - this.throttleSmooth) * 0.2F;
        this.x = this.car.getX();
        this.y = this.car.getY() + 0.5D;
        this.z = this.car.getZ();
        this.pitch = 0.55F + 1.25F * this.throttleSmooth;
        float target = driven ? 0.35F + 0.65F * this.throttleSmooth : 0.0F;
        this.volume = Mth.clamp(this.volume + (target - this.volume) * 0.25F, 0.0F, 1.0F);
        if (!driven && this.volume < 0.02F) {
            this.stop();
        }
    }

    public boolean isDone() {
        return this.isStopped();
    }
}
