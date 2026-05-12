package com.zcraft.decorations.registry;

import com.zcraft.decorations.ZcraftDecorationsMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ZcraftDecorationsMod.MODID);

    public static final RegistryObject<CreativeModeTab> ZCRAFT_DECORATIONS_BLOCKS = TABS.register("zcraft_decorations_blocks",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.zcraft_decorations.zcraft_decorations_blocks"))
                    .icon(() -> new ItemStack(ModBlocks.ITEMS.getEntries().iterator().next().get()))
                    .displayItems((parameters, output) -> ModBlocks.ITEMS.getEntries().forEach(item -> output.accept(item.get())))
                    .build());

    private ModCreativeTabs() {
    }
}
