package com.zcraft.decorations.registry;

import com.zcraft.decorations.ZcraftDecorationsMod;
import com.zcraft.decorations.block.entity.CollisionProxyBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ZcraftDecorationsMod.MODID);

    public static final RegistryObject<BlockEntityType<CollisionProxyBlockEntity>> COLLISION_PROXY = BLOCK_ENTITIES.register("collision_proxy",
            () -> BlockEntityType.Builder.of(CollisionProxyBlockEntity::new, ModBlocks.COLLISION_PROXY.get()).build(null));

    private ModBlockEntities() {
    }
}

