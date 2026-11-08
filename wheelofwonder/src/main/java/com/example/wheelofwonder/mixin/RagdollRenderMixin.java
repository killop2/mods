package com.example.wheelofwonder.mixin;

import com.example.wheelofwonder.client.RagdollView;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Records where Sable: Ragdolls draws each part of the followed ragdoll, so the follow camera aims at the body
 * exactly as the player sees it (same as kickmod's mixin; both can coexist). require = 0: without the mod nothing happens.
 */
@Pseudo
@Mixin(targets = "dev.leo.sableplayerragdoll.mob.client.MobRagdollPartBlockEntityRenderer", remap = false)
public abstract class RagdollRenderMixin {
    @Unique
    private static Method wheelofwonder$entityId, wheelofwonder$role, wheelofwonder$anchor;

    @Inject(method = "render(Ldev/leo/sableplayerragdoll/mob/block/entity/MobRagdollPartBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"), require = 0)
    private void wheelofwonder$recordPart(@Coerce Object be, float partial, PoseStack poseStack, MultiBufferSource buffers,
                                          int light, int overlay, CallbackInfo ci) {
        try {
            if (wheelofwonder$entityId == null) {
                Class<?> c = be.getClass();
                wheelofwonder$entityId = c.getMethod("sourceEntityNetworkId");
                wheelofwonder$role = c.getMethod("role");
                wheelofwonder$anchor = c.getMethod("renderAnchor");
            }
            int id = (Integer) wheelofwonder$entityId.invoke(be);
            boolean stick = com.example.wheelofwonder.client.DaggerStick.wants(id);
            boolean tele = com.example.wheelofwonder.client.TelekinesisClient.wantsParts(id);
            if ((!RagdollView.wants(id) && !stick && !tele) || !Boolean.TRUE.equals(wheelofwonder$anchor.invoke(be))) return;
            Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            // the NPC held by telekinesis: where its hands and feet are, for the puppet strings
            if (tele) {
                Vector3f tp = poseStack.last().pose().transformPosition(0.5F, 0.5F, 0.5F, new Vector3f());
                com.example.wheelofwonder.client.TelekinesisClient.recordPart(be, String.valueOf(wheelofwonder$role.invoke(be)), cam.add(tp.x(), tp.y(), tp.z()));
            }
            // the impaled NPC: every part's frame, so stuck daggers move and turn with it
            if (stick) com.example.wheelofwonder.client.DaggerStick.record(be, poseStack.last().pose(), cam);
            if (!RagdollView.wants(id)) return;
            boolean torso = String.valueOf(wheelofwonder$role.invoke(be)).equals("TORSO");
            Vector3f p = poseStack.last().pose().transformPosition(0.5F, 0.5F, 0.5F, new Vector3f());
            RagdollView.record(id, torso, cam.add(p.x(), p.y(), p.z()));
        } catch (Throwable ignored) {
        }
    }
}
