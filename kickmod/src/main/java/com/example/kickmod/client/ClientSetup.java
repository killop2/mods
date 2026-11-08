package com.example.kickmod.client;

import com.example.kickmod.KickMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.Input;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

public final class ClientSetup {
    public static final KeyMapping KICK = new KeyMapping("key.kickmod.kick", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_KP_9, "key.categories.kickmod");

    private ClientSetup() {
    }

    @EventBusSubscriber(modid = KickMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent event) {
            event.register(KICK);
        }
    }

    @EventBusSubscriber(modid = KickMod.MOD_ID, value = Dist.CLIENT)
    public static final class GameEvents {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            KickController.tick();
            while (KICK.consumeClick()) {
                KickController.tryStart();
            }
        }

        @SubscribeEvent
        public static void input(MovementInputUpdateEvent event) {
            if (!KickController.isLocked()) return;
            Input in = event.getInput();
            in.forwardImpulse = 0.0F;
            in.leftImpulse = 0.0F;
            in.up = false;
            in.down = false;
            in.left = false;
            in.right = false;
            in.jumping = false;
            in.shiftKeyDown = false;
        }

        /** Mouse wheel zooms the free camera on the ragdoll (and does not switch hotbar slots meanwhile). */
        @SubscribeEvent
        public static void scroll(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent event) {
            if (KickController.onScroll(event.getScrollDeltaY())) event.setCanceled(true);
        }

        @SubscribeEvent
        public static void hand(RenderHandEvent event) {
            if (KickController.isActive()) event.setCanceled(true);
        }

        @SubscribeEvent
        public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
            KickController.finish();
        }
    }
}
