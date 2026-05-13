package com.zcraft.decorations.registry;

import com.zcraft.decorations.ZcraftDecorationsMod;
import com.zcraft.decorations.block.entity.CollisionProxyBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, ZcraftDecorationsMod.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CollisionProxyBlockEntity>> COLLISION_PROXY = BLOCK_ENTITIES.register("collision_proxy",
            () -> BlockEntityType.Builder.of(CollisionProxyBlockEntity::new, ModBlocks.COLLISION_PROXY.get()).build(null));

    private ModBlockEntities() {
    }
}
