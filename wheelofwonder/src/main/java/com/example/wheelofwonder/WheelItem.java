package com.example.wheelofwonder;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

/** Right click: the wheel rolls from your feet to the block you look at. Reusable, with a cooldown. */
public class WheelItem extends Item {
    public WheelItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel server)) return InteractionResultHolder.success(stack);

        double range = WheelConfig.MAX_RANGE.get();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 target = hit.getType() == HitResult.Type.MISS ? eye.add(look.scale(range)) : hit.getLocation();

        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-4D) flat = Vec3.directionFromRotation(0.0F, player.getYRot());
        flat = flat.normalize();
        Vec3 start = player.position().add(flat.scale(1.6D));
        double ground = groundBelow(server, start.x, player.getY() + 0.5D, start.z, player.getY());
        start = new Vec3(start.x, ground, start.z);
        // at least a short roll, even when aiming at your feet
        Vec3 to = new Vec3(target.x - start.x, 0.0D, target.z - start.z);
        if (to.lengthSqr() < 9.0D) target = start.add(flat.scale(3.0D));

        WheelEntity wheel = WheelEntity.create(server, player, start, new Vec3(target.x, ground, target.z));
        server.addFreshEntity(wheel);
        level.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 1.0F, 1.4F);
        level.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 1.0F, 1.2F);
        int cd = WheelConfig.COOLDOWN.get();
        if (cd > 0) player.getCooldowns().addCooldown(this, cd);
        return InteractionResultHolder.consume(stack);
    }

    private static double groundBelow(ServerLevel level, double x, double fromY, double z, double fallback) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int by = Mth.floor(fromY); by >= Mth.floor(fromY) - 6; by--) {
            p.set(Mth.floor(x), by, Mth.floor(z));
            VoxelShape s = level.getBlockState(p).getCollisionShape(level, p);
            if (!s.isEmpty()) return by + s.max(Direction.Axis.Y);
        }
        return fallback;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.wheelofwonder.wheel_of_wonder.desc").withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.add(Component.translatable("item.wheelofwonder.wheel_of_wonder.desc2").withStyle(ChatFormatting.GRAY));
    }
}
