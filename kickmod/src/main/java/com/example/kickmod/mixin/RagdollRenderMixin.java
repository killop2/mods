package com.example.kickmod.mixin;

import com.example.kickmod.client.RagdollView;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Records where Sable: Ragdolls draws each ragdoll part (one anchor block per part, centred on the part), so the
 * kick camera can aim at the body exactly as the player sees it. require = 0: without the mod nothing happens.
 */
@Pseudo
@Mixin(targets = "dev.leo.sableplayerragdoll.mob.client.MobRagdollPartBlockEntityRenderer", remap = false)
public abstract class RagdollRenderMixin {
    private static Method kickmod$entityId, kickmod$role, kickmod$anchor;

    @Inject(method = "render(Ldev/leo/sableplayerragdoll/mob/block/entity/MobRagdollPartBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"), require = 0)
    private void kickmod$recordPart(@Coerce Object be, float partial, PoseStack poseStack, MultiBufferSource buffers,
                                    int light, int overlay, CallbackInfo ci) {
        try {
            if (kickmod$entityId == null) {
                Class<?> c = be.getClass();
                kickmod$entityId = c.getMethod("sourceEntityNetworkId");
                kickmod$role = c.getMethod("role");
                kickmod$anchor = c.getMethod("renderAnchor");
            }
            if (!Boolean.TRUE.equals(kickmod$anchor.invoke(be))) return;
            int id = (Integer) kickmod$entityId.invoke(be);
            boolean torso = String.valueOf(kickmod$role.invoke(be)).equals("TORSO");
            Vector3f p = poseStack.last().pose().transformPosition(0.5F, 0.5F, 0.5F, new Vector3f());
            Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            RagdollView.record(id, torso, cam.add(p.x(), p.y(), p.z()));
        } catch (Throwable ignored) {
        }
    }
}
