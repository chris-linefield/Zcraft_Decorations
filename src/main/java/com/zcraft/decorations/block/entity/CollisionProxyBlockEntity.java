package com.zcraft.decorations.block.entity;

import com.zcraft.decorations.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

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
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
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
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        origin = tag.contains(ORIGIN_KEY) ? BlockPos.of(tag.getLong(ORIGIN_KEY)) : null;
        blockName = tag.contains(BLOCK_NAME_KEY) ? tag.getString(BLOCK_NAME_KEY) : null;
        facing = tag.contains(FACING_KEY) ? Direction.from3DDataValue(tag.getByte(FACING_KEY)) : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
