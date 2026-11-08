package com.example.waterslide;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Invisible collision piece of a slide. Its shape is chosen by its block state (palette index in S0..S2), so physics
 * engines that cache collision per state (Sable, Sable ragdolls) see the real slide.
 * - physics engines and entities that are not riding collide with the full trough;
 * - entities the slide physics is carrying only collide with the supports (no voxel steps under them);
 * - players on the server never collide with the trough: their own client simulates them.
 * Breaking any piece removes the whole slide.
 */
public class SlidePartBlock extends Block {
    public static final IntegerProperty S0 = IntegerProperty.create("s0", 0, SlideShapes.BASE - 1);
    public static final IntegerProperty S1 = IntegerProperty.create("s1", 0, SlideShapes.BASE - 1);
    public static final IntegerProperty S2 = IntegerProperty.create("s2", 0, SlideShapes.BASE - 1);
    /** Fourth digit, for shapes of slide types added later (old saved states load with s3 = 0, unchanged). */
    public static final IntegerProperty S3 = IntegerProperty.create("s3", 0, SlideShapes.HIGH - 1);

    public SlidePartBlock(Properties props) {
        super(props);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(S0, S1, S2, S3);
    }

    public BlockState withShape(int idx) {
        int base = SlideShapes.BASE, b2 = base * base;
        return defaultBlockState().setValue(S0, idx % base).setValue(S1, (idx / base) % base)
                .setValue(S2, (idx / b2) % base).setValue(S3, idx / (b2 * base));
    }

    /** Palette index of this state. */
    protected int shapeIndex(BlockState s) {
        int base = SlideShapes.BASE;
        return s.getValue(S0) + s.getValue(S1) * base + s.getValue(S2) * base * base + s.getValue(S3) * base * base * base;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SlideShapes.full(shapeIndex(state));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        int idx = shapeIndex(state);
        if (ctx instanceof EntityCollisionContext ec && ec.getEntity() != null) {
            Entity e = ec.getEntity();
            if ((e instanceof Player && !e.level().isClientSide()) || SlidePhysics.isRiding(e)) return SlideShapes.support(idx);
        }
        return SlideShapes.full(idx);
    }

    @Override
    protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel server && !SlideManager.isRemoving()) {
            int type = SlideManager.removeAt(server, pos);
            if (type >= 0 && !player.getAbilities().instabuild) {
                popResource(level, pos, new ItemStack(WaterSlideMod.SPAWNERS.get(Math.min(type, WaterSlideMod.SPAWNERS.size() - 1)).get()));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (level instanceof ServerLevel server && !SlideManager.isRemoving() && !newState.is(state.getBlock())) {
            SlideManager.removeAt(server, pos);   // explosions, commands, other mods
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
