package com.example.wheelofwonder.client;

import com.example.wheelofwonder.WheelConfig;
import com.example.wheelofwonder.WheelMod;
import com.example.wheelofwonder.mixin.ItemInHandRendererAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.phys.EntityHitResult;
import com.example.wheelofwonder.WalrusGlovesItem;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

import java.util.Set;

public final class ClientSetup {
    public static final KeyMapping TOGGLE_HUD = new KeyMapping("key.wheelofwonder.toggle_hud", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.categories.wheelofwonder");
    public static final KeyMapping LOAD_BALLISTA = new KeyMapping("key.wheelofwonder.load_ballista", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_KP_7, "key.categories.wheelofwonder");
    public static final KeyMapping TELE_ORBIT = new KeyMapping("key.wheelofwonder.tele_orbit", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.wheelofwonder");

    /**
     * HUD layers hidden in immersive mode. Chat, titles, boss bars, the scoreboard, F3 and the tab list stay, and
     * inventory/chest/chat screens are separate screens, so they are never affected.
     */
    private static final Set<ResourceLocation> IMMERSIVE_HIDDEN = Set.of(
            VanillaGuiLayers.HOTBAR, VanillaGuiLayers.SELECTED_ITEM_NAME,
            VanillaGuiLayers.PLAYER_HEALTH, VanillaGuiLayers.ARMOR_LEVEL, VanillaGuiLayers.FOOD_LEVEL,
            VanillaGuiLayers.AIR_LEVEL, VanillaGuiLayers.VEHICLE_HEALTH, VanillaGuiLayers.JUMP_METER,
            VanillaGuiLayers.EXPERIENCE_BAR, VanillaGuiLayers.EXPERIENCE_LEVEL, VanillaGuiLayers.EFFECTS,
            VanillaGuiLayers.SPECTATOR_TOOLTIP);

    private ClientSetup() {
    }

    @EventBusSubscriber(modid = WheelMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        @SubscribeEvent
        public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(WheelMod.WHEEL.get(), WheelRenderer::new);
            event.registerEntityRenderer(WheelMod.PIECE.get(), PieceRenderer::new);
            event.registerEntityRenderer(WheelMod.BALLISTA.get(), BallistaRenderer::new);
            event.registerEntityRenderer(WheelMod.ROCKET_RIDE.get(), RocketRideRenderer::new);
            event.registerEntityRenderer(WheelMod.ROCKET.get(), RocketRenderer::new);
            event.registerEntityRenderer(WheelMod.METEOR.get(), MeteorRenderer::new);
            event.registerEntityRenderer(WheelMod.RANGER.get(), RangerRideRenderer::new);
            event.registerEntityRenderer(WheelMod.GHOST_SHIP.get(), GhostShipRenderer::new);
            event.registerEntityRenderer(WheelMod.STRANGE.get(), StrangeRenderer::new);
            event.registerEntityRenderer(WheelMod.PRISM.get(), PrismCoasterRenderer::new);
        }

        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent event) {
            event.register(TOGGLE_HUD);
            event.register(LOAD_BALLISTA);
        }

        /** Gloves on the arms in third person, for both skin types (wide and slim). */
        @SubscribeEvent
        public static void layers(EntityRenderersEvent.AddLayers event) {
            for (PlayerSkin.Model skin : event.getSkins()) {
                if (event.getSkin(skin) instanceof PlayerRenderer r) r.addLayer(new GloveLayer(r));
            }
        }
    }

    /** True while one of our cinematic cameras runs (HUD hidden, hands hidden, movement locked). */
    static boolean cinematic() {
        return WheelCamera.isActive() || PunchController.isActive() || BallistaCamera.isActive() || DaggerController.isActive()
                || ShockwaveController.isActive() || WhirlClient.isActive() || TrizubController.isActive() || StrangeController.isActive();
    }

    @EventBusSubscriber(modid = WheelMod.MOD_ID, value = Dist.CLIENT)
    public static final class GameEvents {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            WheelCamera.tick();
            PunchController.tick();
            PunchFx.tick();
            BallistaCamera.tick();
            DaggerController.tick();
            DaggerFx.tickHeld();
            ShockwaveController.tick();
            ShockwaveFx.tick();
            WhirlClient.tick();
            TelekinesisClient.tick();
            TrizubController.tick();
            StrangeController.tick();
            ChainClient.tick();
            while (LOAD_BALLISTA.consumeClick()) BallistaCamera.requestLoad();
            while (TOGGLE_HUD.consumeClick()) {
                boolean hide = !WheelConfig.HIDE_HUD.get();
                WheelConfig.HIDE_HUD.set(hide);
                WheelConfig.HIDE_HUD.save();
                LocalPlayer p = Minecraft.getInstance().player;
                if (p != null) {
                    p.displayClientMessage(Component.translatable(hide ? "wheelofwonder.msg.hud_hidden" : "wheelofwonder.msg.hud_shown"), true);
                }
            }
        }

        @SubscribeEvent
        public static void input(MovementInputUpdateEvent event) {
            if (PunchController.isActive()) {
                PunchController.input(event.getInput());
                return;
            }
            if (BallistaCamera.isActive()) {
                BallistaCamera.input(event.getInput());
                return;
            }
            if (DaggerController.isActive()) {
                DaggerController.input(event.getInput());
                return;
            }
            if (ShockwaveController.isActive()) {
                ShockwaveController.input(event.getInput());
                return;
            }
            if (WhirlClient.isActive()) {
                WhirlClient.input(event.getInput());
                return;
            }
            if (TrizubController.isActive()) {
                TrizubController.input(event.getInput());
                return;
            }
            if (StrangeController.isActive()) {
                StrangeController.input(event.getInput());
                return;
            }
            if (!WheelCamera.isLocked()) return;
            Input in = event.getInput();
            in.forwardImpulse = 0.0F;
            in.leftImpulse = 0.0F;
            in.up = false;
            in.down = false;
            in.left = false;
            in.right = false;
            in.jumping = false;
        }

        /**
         * During the wheel camera: no HUD at all. Otherwise, in immersive mode, the hotbar and status bars are not
         * drawn - only skipped, nothing is switched off, so the hotbar keys, scrolling and using items work as usual.
         */
        @SubscribeEvent
        public static void hud(RenderGuiLayerEvent.Pre event) {
            if (cinematic()) {
                event.setCanceled(true);
                return;
            }
            if (!WheelConfig.HIDE_HUD.get()) return;
            ResourceLocation layer = event.getName();
            if (IMMERSIVE_HIDDEN.contains(layer) || (layer.equals(VanillaGuiLayers.CROSSHAIR) && WheelConfig.HIDE_CROSSHAIR.get())) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void hand(RenderHandEvent event) {
            if (cinematic()) {
                event.setCanceled(true);
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || p.isInvisible()) return;
            boolean gloves = WalrusGlovesItem.isGloves(p.getMainHandItem());
            if (event.getHand() == InteractionHand.MAIN_HAND) {
                // gloves: show the gloved arm instead of the item (PlayerHandMixin puts the glove on it)
                if (!gloves) return;
                drawArm(mc, event, p.getMainArm());
                return;
            }
            // Vanilla shows only one arm when you hold nothing; draw the other one too (and with gloves: both gloved)
            if (!event.getItemStack().isEmpty()) return;
            if (gloves || (WheelConfig.BOTH_ARMS.get() && p.getMainHandItem().isEmpty())) {
                drawArm(mc, event, p.getMainArm().getOpposite());
            }
        }

        private static void drawArm(Minecraft mc, RenderHandEvent event, net.minecraft.world.entity.HumanoidArm arm) {
            PoseStack pose = event.getPoseStack();
            pose.pushPose();
            ((ItemInHandRendererAccessor) mc.gameRenderer.itemInHandRenderer).wheelofwonder$renderPlayerArm(pose,
                    event.getMultiBufferSource(), event.getPackedLight(), event.getEquipProgress(), event.getSwingProgress(), arm);
            pose.popPose();
            event.setCanceled(true);
        }

        /** Left click on an NPC with the gloves (not on cooldown): the Walrus PUNCH! combo instead of a normal hit. */
        @SubscribeEvent
        public static void attack(InputEvent.InteractionKeyMappingTriggered event) {
            if (!event.isAttack()) return;
            // holding an NPC with the orb: the left button turns it (read in TelekinesisClient), it does not hit
            if (TelekinesisClient.isHolding()) {
                event.setSwingHand(false);
                event.setCanceled(true);
                return;
            }
            if (cinematic()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !WalrusGlovesItem.isGloves(mc.player.getMainHandItem())) return;
            if (mc.hitResult instanceof EntityHitResult ehr && PunchController.tryStart(ehr.getEntity())) {
                event.setSwingHand(false);
                event.setCanceled(true);
            }
        }

        /**
         * Right click with Impalement Arts: the throw. Caught here, before vanilla, so a click on an NPC does not open
         * its dialog instead. While the throw runs, no clicks reach the world at all.
         */
        @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGH)
        public static void daggers(InputEvent.InteractionKeyMappingTriggered event) {
            if (DaggerController.isActive() || ShockwaveController.isActive() || WhirlClient.isActive() || TrizubController.isActive()
                    || StrangeController.isActive()) {
                event.setSwingHand(false);
                event.setCanceled(true);
                return;
            }
            if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) return;
            // Trident: the ghost ship
            if (TrizubController.tryStart()) {
                event.setSwingHand(true);
                event.setCanceled(true);
                return;
            }
            // Strange's Hammer: the sorcerer
            if (StrangeController.tryStart()) {
                event.setSwingHand(true);
                event.setCanceled(true);
                return;
            }
            // Telekinesis Orb: grab / let go
            if (TelekinesisClient.onUse()) {
                event.setSwingHand(false);
                event.setCanceled(true);
                return;
            }
            // Sinister Chain + Alt: Whirlwind Whip
            if (WhirlClient.tryStart()) {
                event.setSwingHand(true);
                event.setCanceled(true);
                return;
            }
            // Shockwave!: the swing and the rift; caught here so an NPC under the crosshair is ignored
            LocalPlayer sp = Minecraft.getInstance().player;
            if (sp != null && com.example.wheelofwonder.ShockwaveItem.isShockwave(sp.getMainHandItem())) {
                ShockwaveController.tryStart();
                event.setSwingHand(false);
                event.setCanceled(true);
                return;
            }
            // Sinister Chain: caught before the NPC's own right-click (its dialog)
            if (!cinematic() && ChainClient.onUse(event)) {
                event.setSwingHand(true);
                event.setCanceled(true);
                return;
            }
            // Chaos Meteor: the server aims and calls it down; caught here so an NPC under the crosshair is ignored
            Minecraft mcm = Minecraft.getInstance();
            if (mcm.player != null && com.example.wheelofwonder.ChaosMeteorItem.isMeteor(mcm.player.getMainHandItem())) {
                if (!cinematic() && !mcm.player.getCooldowns().isOnCooldown(mcm.player.getMainHandItem().getItem())) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new com.example.wheelofwonder.BallistaRequest(com.example.wheelofwonder.BallistaRequest.METEOR, -1));
                    event.setSwingHand(true);
                } else {
                    event.setSwingHand(false);
                }
                event.setCanceled(true);
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !com.example.wheelofwonder.ImpalementArtsItem.isDaggers(mc.player.getMainHandItem())) return;
            if (DaggerController.tryStart()) {
                event.setSwingHand(false);
                event.setCanceled(true);
            }
        }

        /** Mouse wheel zooms the free camera on the punched ragdoll (and does not switch hotbar slots). */
        @SubscribeEvent
        public static void scroll(InputEvent.MouseScrollingEvent event) {
            if (PunchController.onScroll(event.getScrollDeltaY()) || BallistaCamera.onScroll(event.getScrollDeltaY())
                    || DaggerController.onScroll(event.getScrollDeltaY()) || ShockwaveController.onScroll(event.getScrollDeltaY())
                    || WhirlClient.onScroll(event.getScrollDeltaY())
                    || TelekinesisClient.onScroll(event.getScrollDeltaY())
                    || TrizubController.onScroll(event.getScrollDeltaY())
                    || StrangeController.onScroll(event.getScrollDeltaY())) event.setCanceled(true);
        }

        /**
         * An NPC in a Ranger gondola is drawn exactly in the gondola in this frame, turned over with it, instead of at
         * its (late, interpolated) server position. The dispatcher pops the pose after the entity, so nothing leaks.
         */
        @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
        public static void rangerRider(net.neoforged.neoforge.client.event.RenderLivingEvent.Pre<?, ?> event) {
            net.minecraft.world.entity.LivingEntity e = event.getEntity();
            if (WhirlClient.whirling(e.getId())) {
                WhirlClient.placeBody(e, event.getPoseStack(), event.getPartialTick());
                return;
            }
            com.example.wheelofwonder.PrismCoasterEntity prism = com.example.wheelofwonder.PrismCoasterEntity.rideOf(e);
            if (prism != null) {
                // in the Prism Splitter's cart: drawn exactly on the cart, tilted with the track
                float pt = event.getPartialTick();
                net.minecraft.world.phys.Vec3 actual = e.getPosition(pt);
                net.minecraft.world.phys.Vec3[] f = prism.frame(prism.cartS(pt));
                net.minecraft.world.phys.Vec3 feet = f[0].add(f[3].scale(com.example.wheelofwonder.PrismCoasterEntity.FEET));
                PoseStack ps = event.getPoseStack();
                ps.translate(feet.x - actual.x, feet.y - actual.y, feet.z - actual.z);
                net.minecraft.world.phys.Vec3 t = f[1], h = new net.minecraft.world.phys.Vec3(t.x, 0.0D, t.z);
                if (h.lengthSqr() > 1.0E-4D) {
                    h = h.normalize();
                    ps.mulPose(new org.joml.Quaternionf().rotationTo((float) h.x, 0.0F, (float) h.z, (float) t.x, (float) t.y, (float) t.z));
                }
                return;
            }
            com.example.wheelofwonder.RangerRideEntity r = com.example.wheelofwonder.RangerRideEntity.rideOf(e);
            if (r == null) return;
            float pt = event.getPartialTick();
            net.minecraft.world.phys.Vec3 actual = e.getPosition(pt);
            net.minecraft.world.phys.Vec3 centre = r.gondolaWorld(pt);
            PoseStack ps = event.getPoseStack();
            ps.translate(centre.x - actual.x, centre.y - actual.y, centre.z - actual.z);
            float yaw = r.getYRot();
            ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-yaw));
            ps.mulPose(com.mojang.math.Axis.ZP.rotation((float) r.viewAngle(pt)));
            ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yaw));
            ps.translate(0.0D, -com.example.wheelofwonder.RangerRideEntity.FEET, 0.0D);
        }

        @SubscribeEvent
        public static void renderLevel(RenderLevelStageEvent event) {
            PunchFx.render(event);
            DaggerFx.render(event);
            ChainClient.render(event);
            ShockwaveFx.render(event);
            TelekinesisClient.render(event);
        }

        @SubscribeEvent
        public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
            WheelCamera.finish();
            PunchController.finish(false);
            BallistaCamera.finish();
            DaggerController.finish(true);
            ShockwaveController.finish();
            ShockwaveFx.clear();
            com.example.wheelofwonder.RangerRideEntity.clearClient();
            com.example.wheelofwonder.PrismCoasterEntity.clearClient();
            WhirlClient.clear();
            TelekinesisClient.clear();
            TrizubController.finish();
            StrangeController.finish();
        }
    }
}
