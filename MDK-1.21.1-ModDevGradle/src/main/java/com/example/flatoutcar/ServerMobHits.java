package com.example.flatoutcar;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Наезд машины на мобов/NPC/игроков (только сервер).
 * Порядок: 1) рэгдолл (Ragdoll Reactions -> Sable: Ragdolls) с физикой этих модов;
 * 2) если рэгдолл невозможен — моб отлетает по той же траектории и погибает (или получает урон по скорости).
 */
public final class ServerMobHits {
    private ServerMobHits() {
    }

    public static void hit(ServerLevel level, CarEntity car, LivingEntity target, Vec3 moveDir, double carMps) {
        // уже лежащий труп или моб в полёте — не трогаем, пусть физика рэгдолла доиграет
        if (CorpseManager.isCorpse(target)
                || (!(target instanceof ServerPlayer) && RagdollBridge.isMobRagdolled(target))) {
            return;
        }
        double mult = CarConfig.MOBS_LAUNCH_MULTIPLIER.get();
        Vec3 v = MobImpact.launchVelocity(car, target, moveDir, carMps, level.getRandom()).scale(mult);
        LivingEntity driver = car.getControllingPassenger();

        float vol = (float) Math.min(1.5D, 0.4D + carMps / 20.0D);
        level.playSound(null, target.getX(), target.getY(), target.getZ(), FlatoutCarMod.BUMP_SOUND.get(),
                SoundSource.NEUTRAL, vol, 0.8F + level.getRandom().nextFloat() * 0.3F);

        boolean ragdoll;
        if (target instanceof ServerPlayer p) {
            ragdoll = RagdollBridge.isRagdolled(p) || RagdollBridge.launch(p, v);
        } else if (CarConfig.MOBS_CORPSES.get()) {
            // труп: «вечный» рэгдолл Sable, моб не встаёт; если рэгдолл невозможен — гибель ниже
            ragdoll = CorpseManager.launchCorpse(level, target, v);
        } else {
            ragdoll = RagdollBridge.launchMob(level, target, v);
        }
        if (ragdoll) {
            return;
        }

        // запасной вариант: обычный отлёт (м/с -> блоки/тик) + урон
        target.setDeltaMovement(v.scale(1.0D / 20.0D));
        target.hurtMarked = true;
        target.hasImpulse = true;
        DamageSource src = driver instanceof Player pl ? level.damageSources().playerAttack(pl)
                : driver != null ? level.damageSources().mobAttack(driver)
                : level.damageSources().flyIntoWall();
        boolean kill = CarConfig.MOBS_KILL_IF_NO_RAGDOLL.get() && !(target instanceof Player);
        // урон по кинетической энергии: ~0.08 * v^2 (30 км/ч ~ 5.5, 60 км/ч ~ 22, 115 км/ч ~ 80)
        float dmg = kill ? Math.max(1000.0F, target.getMaxHealth() * 10.0F) : (float) (0.08D * carMps * carMps);
        target.invulnerableTime = 0;
        target.hurt(src, dmg);
        // урон мог сбросить скорость отбрасыванием — восстанавливаем траекторию удара
        target.setDeltaMovement(v.scale(1.0D / 20.0D));
        target.hurtMarked = true;
    }
}
