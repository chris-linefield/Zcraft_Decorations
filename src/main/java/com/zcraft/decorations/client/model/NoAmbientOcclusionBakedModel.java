package com.zcraft.decorations.client.model;

import net.minecraft.client.resources.model.BakedModel;
import net.neoforged.neoforge.client.model.BakedModelWrapper;

public final class NoAmbientOcclusionBakedModel extends BakedModelWrapper<BakedModel> {
    public NoAmbientOcclusionBakedModel(BakedModel originalModel) {
        super(originalModel);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return false;
    }
}
