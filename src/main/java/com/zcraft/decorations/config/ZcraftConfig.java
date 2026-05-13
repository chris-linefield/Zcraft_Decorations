package com.zcraft.decorations.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ZcraftConfig {
    public static final ModConfigSpec COMMON_SPEC;

    public static final ModConfigSpec.ConfigValue<String> SHAPE_MODE;
    public static final ModConfigSpec.DoubleValue PROXY_SNAP_16;
    public static final ModConfigSpec.DoubleValue SHAPE_VOXEL_STEP_16;
    public static final ModConfigSpec.IntValue SHAPE_CACHE_THREADS;
    public static final ModConfigSpec.BooleanValue WARM_VOXEL_SHAPE_CACHE;
    public static final ModConfigSpec.BooleanValue PROXY_COLLISION;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("model_shapes");

        SHAPE_MODE = builder
                .comment("Collision shape mode: full, bounds, or block. full uses model elements and voxelizes rotated elements.")
                .comment("碰撞形状模式：full、bounds或block。full使用模型元素并对旋转元素进行体素化。")
                .define("shapeMode", "full");

        SHAPE_VOXEL_STEP_16 = builder
                .comment("Voxel size in model pixel units for rotated model elements. Lower is more accurate but slower. Current default is 1.0.")
                .comment("旋转模型元素的体素大小（以模型像素单位为单位）。较低的精度更高，但速度更慢。当前默认值为1.0。")
                .defineInRange("shapeVoxelStep16", 1.0, 0.25, 4.0);

        SHAPE_CACHE_THREADS = builder
                .comment("Maximum threads used to build the persistent shape cache. -1 means max(1, CPU cores - 1).")
                .comment("用于构建持久形状缓存的最大线程数。-1表示max(1, CPU核心数-1)。")
                .defineInRange("shapeCacheThreads", -1, -1, 256);

        WARM_VOXEL_SHAPE_CACHE = builder
                .comment("After the file cache is ready, builds in-memory VoxelShape objects in the background. Disabled by default; VoxelShapes are normally built lazily on first use.")
                .comment("文件缓存就绪后，在后台构建内存VoxelShape对象。默认关闭；通常在第一次使用时懒加载构建。")
                .define("warmVoxelShapeCache", false);

        PROXY_SNAP_16 = builder
                .comment("Snaps multiblock proxy bounds to block cell edges when the overhang is within this many model pixels.")
                .comment("当超出块单元边缘的超悬距离小于此值时，将多块代理边界对齐到块单元边缘。")
                .defineInRange("proxySnap16", 4.0, 0.0, 16.0);

        PROXY_COLLISION = builder
                .comment("Places invisible proxy collision blocks for model shapes that extend outside the origin block.")
                .comment("为模型形状放置不可见的代理碰撞块，这些形状扩展到原块外部。")
                .define("proxyCollision", true);

        builder.pop();
        COMMON_SPEC = builder.build();
    }

    private ZcraftConfig() {
    }
}
