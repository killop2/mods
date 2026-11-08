package com.example.huggynpc.mixin;

import com.example.huggynpc.client.NpcModelLookup;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Sable: Ragdolls builds a mob ragdoll from the model of the mob's renderer. A Custom NPC that looks like Huggy is
 * still drawn by the Custom NPCs renderer, whose own model is a player model, so the ragdoll would be Steve-shaped.
 * Here the Huggy model is handed over instead. require = 0 and @Pseudo: without Sable nothing happens.
 */
@Pseudo
@Mixin(targets = "dev.leo.sableplayerragdoll.mob.client.MobRagdollClientExtractor", remap = false)
public abstract class SableExtractorMixin {
    @Redirect(method = "extractAndSend(Lnet/minecraft/world/entity/LivingEntity;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;getModel()Lnet/minecraft/client/model/EntityModel;"),
            require = 0)
    private static EntityModel<?> huggynpc$model(LivingEntityRenderer<?, ?> renderer, LivingEntity entity) {
        EntityModel<?> huggy = NpcModelLookup.huggyModelFor(entity);
        return huggy != null ? huggy : renderer.getModel();
    }
}
