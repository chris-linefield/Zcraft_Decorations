package com.zcraft.decorations.client;

import com.zcraft.decorations.ZcraftDecorationsMod;
import com.zcraft.decorations.client.model.NoAmbientOcclusionBakedModel;
import com.zcraft.decorations.model.ModelShapeCache;
import com.zcraft.decorations.registry.ModBlocks;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.util.Map;

@Mod.EventBusSubscriber(modid = ZcraftDecorationsMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {
    private ClientModEvents() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ModBlocks.BLOCKS.getEntries()
                .forEach(block -> ItemBlockRenderTypes.setRenderLayer(block.get(), RenderType.cutout())));
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ResourceLocation, BakedModel> models = event.getModels();
        for (Map.Entry<ResourceLocation, BakedModel> entry : models.entrySet()) {
            ResourceLocation location = entry.getKey();
            if (!ZcraftDecorationsMod.MODID.equals(location.getNamespace())) {
                continue;
            }

            BakedModel model = entry.getValue();
            if (model == null || !model.useAmbientOcclusion() || model instanceof NoAmbientOcclusionBakedModel) {
                continue;
            }

            entry.setValue(new NoAmbientOcclusionBakedModel(model));
        }
        ModelShapeCache.warmAllBlockShapesAsync(ModBlocks.getBlockNames(), "client model baking result");
    }
}
