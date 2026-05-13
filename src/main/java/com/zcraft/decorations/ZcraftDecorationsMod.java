package com.zcraft.decorations;

import com.zcraft.decorations.model.ModelShapeCache;
import com.zcraft.decorations.config.ZcraftConfig;
import com.zcraft.decorations.registry.ModBlocks;
import com.zcraft.decorations.registry.ModBlockEntities;
import com.zcraft.decorations.registry.ModCreativeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod(ZcraftDecorationsMod.MODID)
public class ZcraftDecorationsMod {
    public static final String MODID = "zcraft_decorations";

    public ZcraftDecorationsMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, ZcraftConfig.COMMON_SPEC, "zcraft_decorations-common.toml");
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> ModelShapeCache.warmAllBlockShapesAsync(ModBlocks.getBlockNames(), "common setup"));
    }
}
