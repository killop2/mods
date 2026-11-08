package com.example.huggynpc.mixin;

import com.example.huggynpc.client.NpcModelLookup;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Sable: Ragdolls draws each ragdoll part with the renderer of the source mob. For a Custom NPC that looks like
 * Huggy that would be the Custom NPCs player model; hand over the Huggy renderer instead.
 */
@Pseudo
@Mixin(targets = "dev.leo.sableplayerragdoll.mob.client.MobRagdollPartBlockEntityRenderer", remap = false)
public abstract class SablePartRendererMixin {
    @Redirect(method = "renderReplay",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;getRenderer(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/client/renderer/entity/EntityRenderer;"),
            require = 0)
    private EntityRenderer<?> huggynpc$renderer(EntityRenderDispatcher dispatcher, Entity entity) {
        EntityRenderer<?> huggy = NpcModelLookup.huggyRendererFor(entity);
        return huggy != null ? huggy : dispatcher.getRenderer(entity);
    }
}
