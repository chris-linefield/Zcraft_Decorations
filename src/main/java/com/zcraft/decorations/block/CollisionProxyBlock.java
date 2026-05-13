package com.zcraft.decorations.block;

import com.zcraft.decorations.block.entity.CollisionProxyBlockEntity;
import com.zcraft.decorations.model.ModelShapeCache;
import com.zcraft.decorations.model.ShapeProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class CollisionProxyBlock extends Block implements EntityBlock {
    public CollisionProxyBlock(Properties properties) {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getProxyShape(level, pos);
    }

    @Override
    public VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return getProxyShape(level, pos);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getProxyShape(level, pos);
    }

    @Override
    public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getProxyShape(level, pos);
    }

    @Override
    public VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        return getProxyShape(level, pos);
    }

    private static VoxelShape getProxyShape(BlockGetter level, BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity instanceof CollisionProxyBlockEntity proxy) {
            BlockPos origin = proxy.getOrigin();
            String blockName = proxy.getBlockName();
            Direction facing = proxy.getFacing();
            if (origin != null && blockName != null && facing != null) {
                int offsetX = pos.getX() - origin.getX();
                int offsetY = pos.getY() - origin.getY();
                int offsetZ = pos.getZ() - origin.getZ();
                VoxelShape shape = ModelShapeCache.getOrScheduleLocalShape(
                        blockName,
                        facing,
                        offsetX,
                        offsetY,
                        offsetZ);
                if (ShapeProfiler.enabled()) {
                    ShapeProfiler.registerShape(shape, "block=" + blockName + ",facing=" + facing.getSerializedName()
                            + ",offset=" + offsetX + "," + offsetY + "," + offsetZ);
                }
                return shape;
            }
        }
        return Shapes.block();
    }

    @Override
    public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return 0;
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        return List.of();
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CollisionProxyBlockEntity(pos, state);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof CollisionProxyBlockEntity proxy) {
                BlockPos origin = proxy.getOrigin();
                if (origin != null) {
                    BlockState originState = level.getBlockState(origin);
                    if (!originState.isAir()) {
                        level.destroyBlock(origin, true, player);
                    }
                }
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
