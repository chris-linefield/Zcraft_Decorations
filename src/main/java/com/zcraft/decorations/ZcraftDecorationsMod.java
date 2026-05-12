package com.zcraft.decorations;

import com.zcraft.decorations.model.ModelShapeCache;
import com.zcraft.decorations.registry.ModBlocks;
import com.zcraft.decorations.registry.ModBlockEntities;
import com.zcraft.decorations.registry.ModCreativeTabs;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(ZcraftDecorationsMod.MODID)
public class ZcraftDecorationsMod {
    public static final String MODID = "zcraft_decorations";

    public ZcraftDecorationsMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> ModelShapeCache.warmAllBlockShapes(ModBlocks.getBlockNames()));
    }
}
