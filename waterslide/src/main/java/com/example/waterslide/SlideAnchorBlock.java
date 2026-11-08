package com.example.waterslide;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/** The slide's root piece. Holds the facing, the slide type, and the block entity that draws the 3D model. */
public class SlideAnchorBlock extends SlidePartBlock implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final IntegerProperty TYPE = IntegerProperty.create("type", 0, Math.max(1, SlideTypes.count() - 1));

    public SlideAnchorBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH).setValue(TYPE, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING, TYPE);
    }

    @Override
    protected int shapeIndex(BlockState s) {
        return SlideShapes.cells(s.getValue(TYPE), s.getValue(FACING)).get(0L);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SlideAnchorBlockEntity(pos, state);
    }
}
