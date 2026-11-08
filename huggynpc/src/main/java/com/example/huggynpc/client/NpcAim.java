package com.example.huggynpc.client;

import com.example.huggynpc.NpcCombat;
import com.example.huggynpc.NpcHitPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Optional;

/**
 * Left click on an NPC. The vanilla way (crosshair pick -> attack packet) loses hits on NPCs: the hitbox the client
 * picks with and the one the server checks the reach against can differ (Custom NPCs animations, Sable marking the
 * mob as a ragdoll). Here the client finds the NPC itself, with a hitbox at least the NPC's normal size, and sends
 * its own hit packet that the server checks by distance only.
 */
public final class NpcAim {
    private NpcAim() {
    }

    /** Runs after the Walrus PUNCH! gloves handler (a started combo cancels the event and never reaches here). */
    public static void onAttack(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || p.isSpectator() || mc.gameMode == null) return;
        Entity target = find(mc, p);
        if (target == null) return;
        PacketDistributor.sendToServer(new NpcHitPayload(target.getId()));
        p.resetAttackStrengthTicker();
        event.setSwingHand(true);
        event.setCanceled(true);   // no vanilla attack packet (it would be a second, possibly refused hit)
    }

    private static Entity find(Minecraft mc, LocalPlayer p) {
        if (mc.hitResult instanceof EntityHitResult ehr && NpcCombat.isNpc(ehr.getEntity())) return ehr.getEntity();
        double reach = p.entityInteractionRange();
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(reach));
        // blocks in front stop the hit, like vanilla
        double maxD = reach;
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) maxD = Math.min(maxD, mc.hitResult.getLocation().distanceTo(eye));
        Entity best = null;
        double bestD = maxD;
        AABB area = p.getBoundingBox().expandTowards(look.scale(reach)).inflate(3.0D);
        for (Entity e : mc.level.getEntities(p, area, x -> NpcCombat.isNpc(x) && x.isAlive() && !x.isSpectator())) {
            Optional<Vec3> hit = box(e).clip(eye, end);
            if (hit.isEmpty()) continue;
            double d = hit.get().distanceTo(eye);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /** The NPC's hitbox, but never smaller than its entity type's normal size (a shrunk box would miss). */
    private static AABB box(Entity e) {
        AABB bb = e.getBoundingBox();
        float w = Math.max((float) bb.getXsize(), Math.max(0.6F, e.getType().getWidth()));
        float h = Math.max((float) bb.getYsize(), Math.max(1.8F, e.getType().getHeight()));
        double cx = (bb.minX + bb.maxX) * 0.5D, cz = (bb.minZ + bb.maxZ) * 0.5D;
        return new AABB(cx - w * 0.5D, bb.minY, cz - w * 0.5D, cx + w * 0.5D, bb.minY + h, cz + w * 0.5D).inflate(0.1D);
    }
}
