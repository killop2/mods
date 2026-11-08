package com.example.huggynpc;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;

/**
 * Creates a real Custom NPC that looks like Huggy (the same as NPC Wand -> new NPC -> Model -> Entities -> Huggy),
 * so it shows up in the Custom NPCs lists and can be edited with the NPC Wand. Reflection only: no compile
 * dependency on Custom NPCs.
 */
public final class CnpcSpawner {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation NPC_TYPE = ResourceLocation.fromNamespaceAndPath("customnpcs", "customnpc");

    private CnpcSpawner() {
    }

    public static boolean available() {
        return BuiltInRegistries.ENTITY_TYPE.containsKey(NPC_TYPE);
    }

    /** @return the spawned NPC, or null if Custom NPCs is missing or its API changed. */
    public static Entity spawn(ServerLevel level, BlockPos pos, float yaw, HuggyNpcMod.NpcModel look) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(NPC_TYPE).orElse(null);
        if (type == null) return null;
        Entity npc = type.create(level);
        if (npc == null) return null;
        try {
            Object ais = npc.getClass().getField("ais").get(npc);
            ais.getClass().getMethod("setStartPos", BlockPos.class).invoke(ais, pos);
            Object model = npc.getClass().getField("modelData").get(npc);
            model.getClass().getMethod("setEntity", ResourceLocation.class).invoke(model, look.type().getId());
            Object display = npc.getClass().getField("display").get(npc);
            display.getClass().getMethod("setSkinTexture", String.class).invoke(display, look.texture());
            display.getClass().getMethod("setName", String.class).invoke(display, look.name());
            display.getClass().getMethod("setShowName", int.class).invoke(display, 1);   // no name over the head
        } catch (Throwable t) {
            LOGGER.warn("[huggynpc] cannot set up a Custom NPC, spawning the plain Huggy instead", t);
            npc.discard();
            return null;
        }
        npc.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, yaw, 0.0F);
        npc.setYHeadRot(yaw);
        npc.refreshDimensions();
        if (npc instanceof LivingEntity le) le.setHealth(le.getMaxHealth());
        if (look == HuggyNpcMod.VERITY) npc.addTag(VerityBrain.VERITY_TAG);
        level.addFreshEntity(npc);
        return npc;
    }
}
