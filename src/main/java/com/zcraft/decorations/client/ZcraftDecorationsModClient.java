package com.zcraft.decorations.client;

import com.zcraft.decorations.ZcraftDecorationsMod;
import com.zcraft.decorations.client.model.NoAmbientOcclusionBakedModel;
import com.zcraft.decorations.model.ModelShapeCache;
import com.zcraft.decorations.registry.ModBlocks;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

import java.util.Map;

@Mod(value = ZcraftDecorationsMod.MODID, dist = Dist.CLIENT)
public final class ZcraftDecorationsModClient {

    public ZcraftDecorationsModClient(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(ZcraftDecorationsModClient::onClientSetup);
        modEventBus.addListener(ZcraftDecorationsModClient::onModifyBakingResult);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ModBlocks.BLOCKS.getEntries()
                .forEach(block -> ItemBlockRenderTypes.setRenderLayer(block.get(), RenderType.cutout())));
    }

    private static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        for (Map.Entry<ModelResourceLocation, BakedModel> entry : models.entrySet()) {
            ModelResourceLocation location = entry.getKey();
            if (!ZcraftDecorationsMod.MODID.equals(location.id().getNamespace())) {
                continue;
            }
            // Ne pas envelopper les modèles item / autres : seulement les modèles de blocs.
            if (!location.id().getPath().startsWith("block/")) {
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
