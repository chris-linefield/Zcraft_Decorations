package com.zcraft.decorations.client.model;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.BakedModelWrapper;

public final class NoAmbientOcclusionBakedModel extends BakedModelWrapper<BakedModel> {
    public NoAmbientOcclusionBakedModel(BakedModel originalModel) {
        super(originalModel);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return false;
    }

    @Override
    public boolean useAmbientOcclusion(BlockState state) {
        return false;
    }

    @Override
    public boolean useAmbientOcclusion(BlockState state, RenderType renderType) {
        return false;
    }
}

