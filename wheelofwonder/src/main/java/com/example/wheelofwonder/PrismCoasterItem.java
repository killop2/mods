package com.example.wheelofwonder;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * Right click: the Prism Splitter appears where you look, its track running off to your right, so you watch the
 * cart shoot up the ramp from the side. Not used up.
 */
public class PrismCoasterItem extends Item {
    public PrismCoasterItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel server)) return InteractionResultHolder.success(stack);
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(24.0D)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-4D) flat = Vec3.directionFromRotation(0.0F, player.getYRot());
        flat = flat.normalize();
        Vec3 at = hit.getType() == HitResult.Type.MISS ? player.position().add(flat.scale(10.0D)) : hit.getLocation();
        Vec3 off = at.subtract(player.position()).multiply(1.0D, 0.0D, 1.0D);
        if (off.length() < 6.0D) at = player.position().add(flat.scale(6.0D)).add(0.0D, at.y - player.getY(), 0.0D);
        // the track runs to the player's right; the station sits a little to the left of where they look
        Vec3 right = new Vec3(-flat.z, 0.0D, flat.x);
        at = at.subtract(right.scale(8.0D));
        double ground = groundBelow(server, at.x, at.y + 1.5D, at.z, at.y);

        PrismCoasterEntity ride = new PrismCoasterEntity(WheelMod.PRISM.get(), server);
        ride.setPos(at.x, ground, at.z);
        float yaw = (float) (Mth.atan2(right.z, right.x) * Mth.RAD_TO_DEG);
        ride.setYRot(yaw);
        ride.yRotO = yaw;
        server.addFreshEntity(ride);
        level.playSound(null, at.x, ground, at.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.4F, 0.8F);
        level.playSound(null, at.x, ground, at.z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.8F, 1.4F);
        server.sendParticles(ParticleTypes.END_ROD, at.x, ground + 2.0D, at.z, 40, 2.0D, 1.5D, 2.0D, 0.02D);
        player.getCooldowns().addCooldown(this, 10);
        return InteractionResultHolder.consume(stack);
    }

    private static double groundBelow(ServerLevel level, double x, double fromY, double z, double fallback) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int by = Mth.floor(fromY); by >= Mth.floor(fromY) - 8; by--) {
            p.set(Mth.floor(x), by, Mth.floor(z));
            VoxelShape s = level.getBlockState(p).getCollisionShape(level, p);
            if (!s.isEmpty()) return by + s.max(Direction.Axis.Y);
        }
        return fallback;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.wheelofwonder.prism_splitter.desc").withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.add(Component.translatable("item.wheelofwonder.prism_splitter.desc2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.wheelofwonder.prism_splitter.desc3").withStyle(ChatFormatting.GRAY));
    }
}
