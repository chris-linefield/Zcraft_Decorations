package com.zcraft.decorations.block.entity;

import javax.annotation.Nullable;

import com.zcraft.decorations.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class CollisionProxyBlockEntity extends BlockEntity {
    private static final String ORIGIN_KEY = "Origin";
    private static final String BLOCK_NAME_KEY = "BlockName";
    private static final String FACING_KEY = "Facing";

    @Nullable
    private BlockPos origin;
    @Nullable
    private String blockName;
    @Nullable
    private Direction facing;

    public CollisionProxyBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.COLLISION_PROXY.get(), pos, state);
    }

    public void configure(BlockPos origin, String blockName, Direction facing) {
        this.origin = origin;
        this.blockName = blockName;
        this.facing = facing;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Nullable
    public BlockPos getOrigin() {
        return origin;
    }

    @Nullable
    public String getBlockName() {
        return blockName;
    }

    @Nullable
    public Direction getFacing() {
        return facing;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (origin != null) {
            tag.putLong(ORIGIN_KEY, origin.asLong());
        }
        if (blockName != null) {
            tag.putString(BLOCK_NAME_KEY, blockName);
        }
        if (facing != null) {
            tag.putByte(FACING_KEY, (byte) facing.get3DDataValue());
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        origin = tag.contains(ORIGIN_KEY) ? BlockPos.of(tag.getLong(ORIGIN_KEY)) : null;
        blockName = tag.contains(BLOCK_NAME_KEY) ? tag.getString(BLOCK_NAME_KEY) : null;
        facing = tag.contains(FACING_KEY) ? Direction.from3DDataValue(tag.getByte(FACING_KEY)) : null;
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            load(tag);
        }
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
    }
}
