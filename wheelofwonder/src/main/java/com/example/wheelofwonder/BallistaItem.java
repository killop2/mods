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

/** Right click: a ballista appears where you look, aimed away from you. The tool is not used up. */
public class BallistaItem extends Item {
    public BallistaItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel server)) return InteractionResultHolder.success(stack);
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(8.0D)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-4D) flat = Vec3.directionFromRotation(0.0F, player.getYRot());
        flat = flat.normalize();
        Vec3 at = hit.getType() == HitResult.Type.MISS ? player.position().add(flat.scale(3.0D)) : hit.getLocation();
        // never on top of the player
        Vec3 off = at.subtract(player.position()).multiply(1.0D, 0.0D, 1.0D);
        if (off.length() < 2.2D) at = player.position().add(flat.scale(2.6D)).add(0.0D, at.y - player.getY(), 0.0D);
        double ground = groundBelow(server, at.x, at.y + 0.5D, at.z, at.y);

        BallistaEntity b = new BallistaEntity(WheelMod.BALLISTA.get(), server);
        b.setPos(at.x, ground, at.z);
        b.setYRot(player.getYRot());
        b.yRotO = player.getYRot();
        server.addFreshEntity(b);
        level.playSound(null, at.x, ground, at.z, SoundEvents.WOOD_PLACE, SoundSource.PLAYERS, 1.0F, 0.8F);
        level.playSound(null, at.x, ground, at.z, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 0.8F, 0.7F);
        server.sendParticles(net.minecraft.core.particles.ParticleTypes.WITCH, at.x, ground + 0.8D, at.z, 20, 0.9D, 0.5D, 0.9D, 0.0D);
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
        tooltip.add(Component.translatable("item.wheelofwonder.dark_ballista.desc").withStyle(ChatFormatting.DARK_PURPLE));
        tooltip.add(Component.translatable("item.wheelofwonder.dark_ballista.desc2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.wheelofwonder.dark_ballista.desc3").withStyle(ChatFormatting.GRAY));
    }
}
