package com.example.flatoutcar.client;

import com.example.flatoutcar.CarEntity;
import com.example.flatoutcar.EjectPayload;
import com.example.flatoutcar.FlatoutCarMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = FlatoutCarMod.MOD_ID, value = Dist.CLIENT)
public final class ClientSetup {
    /** Клавиша по умолчанию: G (меняется в настройках управления). */
    public static final KeyMapping EJECT_KEY = new KeyMapping(
            "key.flatoutcar.eject", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.flatoutcar");

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        CarEntity.clientHooks = new CarEntity.ClientHooks() {
            @Override
            public void tick(CarEntity car) {
                if (car.getControllingPassenger() == null) {
                    return;
                }
                if (!(car.engineSound instanceof EngineSound s) || s.isDone()) {
                    EngineSound sound = new EngineSound(car);
                    car.engineSound = sound;
                    Minecraft.getInstance().getSoundManager().play(sound);
                }
            }

            @Override
            public void crash(CarEntity car, float speedBeforeImpact, boolean eject) {
                if (car.getControllingPassenger() != Minecraft.getInstance().player) {
                    return;
                }
                if (eject) {
                    // звук удара и вылета проиграет сервер для всех
                    PacketDistributor.sendToServer(new EjectPayload(speedBeforeImpact, true));
                } else {
                    float v = Math.min(1.0F, Math.abs(speedBeforeImpact) / CarEntity.MAX_SPEED);
                    car.level().playLocalSound(car.getX(), car.getY(), car.getZ(), FlatoutCarMod.BUMP_SOUND.get(),
                            SoundSource.NEUTRAL, 0.4F + v, 0.9F + 0.2F * car.getRandom().nextFloat(), false);
                }
            }
        };
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(EJECT_KEY);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(FlatoutCarMod.CAR.get(), CarRenderer::new);
    }
}
