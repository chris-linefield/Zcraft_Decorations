package com.zcraft.decorations.block;

import java.util.List;

import com.zcraft.decorations.config.ZcraftConfig;
import com.zcraft.decorations.model.ModelShapeCache;
import com.zcraft.decorations.model.ModelShapeCache.IntBounds;
import com.zcraft.decorations.model.ShapeProfiler;
import com.zcraft.decorations.block.entity.CollisionProxyBlockEntity;
import com.zcraft.decorations.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class ModelBasedFacingBlock extends FacingModelBlock {
    private static final int PROXY_CLEANUP_RADIUS = 4;

    private final String blockName;
    private final boolean noCollision;
    private final boolean noDrops;
    private volatile VoxelShape[] cachedShapes;

    public ModelBasedFacingBlock(String blockName, Properties properties) {
        this(blockName, properties, false, false);
    }

    public ModelBasedFacingBlock(String blockName, Properties properties, boolean noCollision) {
        this(blockName, properties, noCollision, noCollision);
    }

    public ModelBasedFacingBlock(String blockName, Properties properties, boolean noCollision, boolean noDrops) {
        super(properties);
        this.blockName = blockName;
        this.noCollision = noCollision;
        this.noDrops = noDrops;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShapeForState(state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (noCollision) {
            return Shapes.empty();
        }
        return getShapeForState(state);
    }

    @Override
    public VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return getShapeForState(state);
    }

    @Override
    public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShapeForState(state);
    }

    @Override
    public VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        return getShapeForState(state);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        if (state == null || noCollision) {
            return state;
        }
        if (!isProxyCollisionEnabled()) {
            return state;
        }

        Direction facing = state.getValue(FACING);
        if (!ModelShapeCache.isMultiBlock(blockName, facing)) {
            return state;
        }

        IntBounds bounds = ModelShapeCache.getOrCreateIntBounds(blockName, facing);
        Level level = context.getLevel();
        BlockPos origin = context.getClickedPos();
        for (int dx = bounds.minX(); dx <= bounds.maxX(); dx++) {
            for (int dy = bounds.minY(); dy <= bounds.maxY(); dy++) {
                for (int dz = bounds.minZ(); dz <= bounds.maxZ(); dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    if (!ModelShapeCache.hasLocalShape(blockName, facing, dx, dy, dz)) {
                        continue;
                    }
                    BlockPos target = origin.offset(dx, dy, dz);
                    if (!level.getBlockState(target).isAir()) {
                        return null;
                    }
                }
            }
        }
        return state;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || noCollision) {
            return;
        }
        if (!isProxyCollisionEnabled()) {
            return;
        }
        Direction facing = state.getValue(FACING);
        if (!ModelShapeCache.isMultiBlock(blockName, facing)) {
            return;
        }

        IntBounds bounds = ModelShapeCache.getOrCreateIntBounds(blockName, facing);
        BlockState proxyState = ModBlocks.COLLISION_PROXY.get().defaultBlockState();
        for (int dx = bounds.minX(); dx <= bounds.maxX(); dx++) {
            for (int dy = bounds.minY(); dy <= bounds.maxY(); dy++) {
                for (int dz = bounds.minZ(); dz <= bounds.maxZ(); dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    if (!ModelShapeCache.hasLocalShape(blockName, facing, dx, dy, dz)) {
                        continue;
                    }
                    BlockPos target = pos.offset(dx, dy, dz);
                    if (!level.getBlockState(target).isAir()) {
                        continue;
                    }
                    level.setBlock(target, proxyState, 3);
                    BlockEntity entity = level.getBlockEntity(target);
                    if (entity instanceof CollisionProxyBlockEntity proxy) {
                        proxy.configure(pos, blockName, facing);
                    }
                }
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide && state.getBlock() != newState.getBlock() && !noCollision && isProxyCollisionEnabled()) {
            cleanupProxies(level, pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    private static boolean isProxyCollisionEnabled() {
        return ZcraftConfig.PROXY_COLLISION.get();
    }

    private static void cleanupProxies(Level level, BlockPos origin) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        for (int dx = -PROXY_CLEANUP_RADIUS; dx <= PROXY_CLEANUP_RADIUS; dx++) {
            for (int dy = -PROXY_CLEANUP_RADIUS; dy <= PROXY_CLEANUP_RADIUS; dy++) {
                for (int dz = -PROXY_CLEANUP_RADIUS; dz <= PROXY_CLEANUP_RADIUS; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    cursor.set(ox + dx, oy + dy, oz + dz);
                    if (level.getBlockState(cursor).getBlock() != ModBlocks.COLLISION_PROXY.get()) {
                        continue;
                    }
                    BlockEntity entity = level.getBlockEntity(cursor);
                    if (entity instanceof CollisionProxyBlockEntity proxy && origin.equals(proxy.getOrigin())) {
                        level.removeBlock(cursor, false);
                    }
                }
            }
        }
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        if (noDrops) {
            return List.of();
        }
        return List.of(new ItemStack(this));
    }

    private VoxelShape getShapeForState(BlockState state) {
        long startNanos = ShapeProfiler.enabled() ? System.nanoTime() : 0L;
        VoxelShape[] shapes = cachedShapes;
        if (shapes == null) {
            shapes = new VoxelShape[4];
            cachedShapes = shapes;
        }
        Direction dir = state.getValue(FACING);
        int index = horizontalIndex(dir);
        VoxelShape shape = shapes[index];
        if (shape == null || !ModelShapeCache.isLocalShapeSetReady(blockName, dir)) {
            shape = ModelShapeCache.getOrScheduleLocalShape(blockName, dir, 0, 0, 0);
            if (ModelShapeCache.isLocalShapeSetReady(blockName, dir)) {
                shapes[index] = shape;
            }
        }
        if (ShapeProfiler.enabled()) {
            String label = "block=" + blockName + ",facing=" + dir.getSerializedName() + ",origin";
            ShapeProfiler.registerShape(shape, label);
            ShapeProfiler.recordShapeGet(label, System.nanoTime() - startNanos);
        }
        return shape;
    }

    private static int horizontalIndex(Direction dir) {
        return switch (dir) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }
}
