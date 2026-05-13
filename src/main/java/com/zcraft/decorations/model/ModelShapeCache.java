package com.zcraft.decorations.model;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.mojang.logging.LogUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.zcraft.decorations.ZcraftDecorationsMod;
import com.zcraft.decorations.config.ZcraftConfig;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

public final class ModelShapeCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, VoxelShape[]> SHAPES_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, VoxelShape> SPLIT_SHAPE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, SplitShapeSet> SPLIT_SHAPE_SET_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Future<?>> SPLIT_SHAPE_BUILD_TASKS = new ConcurrentHashMap<>();
    private static final Map<String, VoxelShape> FALLBACK_SHAPE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CachedShapeSet> PERSISTENT_SHAPE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> LOCAL_OCCUPANCY_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, IntBounds> INT_BOUNDS_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, ResourceLocation> BLOCK_PARENT_MODEL_CACHE = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, ModelGeometry> MODEL_GEOMETRY_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> MODEL_FINGERPRINT_CACHE = new ConcurrentHashMap<>();
    private static final String SHAPE_MODE_PROPERTY = "zcraft_decorations.shapeMode";
    private static final String PROXY_SNAP_16_PROPERTY = "zcraft_decorations.proxySnap16";
    private static final String VOXEL_STEP_16_PROPERTY = "zcraft_decorations.shapeVoxelStep16";
    private static final String CACHE_THREADS_PROPERTY = "zcraft_decorations.shapeCacheThreads";
    private static final String CACHE_ALGORITHM_VERSION = "model-shape-cache-v3";
    // Build precise shapes from model elements by default.
    private static final ShapeMode DEFAULT_SHAPE_MODE = ShapeMode.FULL;
    private static final int MAX_FULL_ELEMENTS = 4096;
    private static final double DEFAULT_PROXY_SNAP_16 = 4.0;
    private static final double DEFAULT_VOXEL_STEP_16 = 1.0;
    private static final double MIN_ELEMENT_THICKNESS_16 = 0.1;
    private static final double EPS = 1.0E-7;
    private static volatile boolean persistentCacheLoaded;
    private static volatile boolean persistentCacheDirty;
    private static volatile boolean bulkWarming;
    private static final AtomicBoolean WARM_RUNNING = new AtomicBoolean(false);
    private static final ExecutorService ASYNC_SHAPE_BUILD_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, ZcraftDecorationsMod.MODID + "-shape-build");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
        return thread;
    });

    private ModelShapeCache() {
    }

    public static VoxelShape[] getOrCreateShapes(String blockName) {
        return getOrCreateShapes(blockName, getShapeMode());
    }

    public static VoxelShape[] getOrCreateShapes(String blockName, ShapeMode mode) {
        return SHAPES_CACHE.computeIfAbsent(blockName + "|" + mode.name(), k -> buildShapesForBlock(blockName, mode));
    }

    public static boolean isMultiBlock(String blockName, net.minecraft.core.Direction facing) {
        IntBounds bounds = getOrCreateIntBounds(blockName, facing, ShapeMode.BOUNDS);
        return bounds.minX < 0 || bounds.maxX > 0 || bounds.minZ < 0 || bounds.maxZ > 0 || bounds.minY < 0 || bounds.maxY > 0;
    }

    public static IntBounds getOrCreateIntBounds(String blockName, net.minecraft.core.Direction facing) {
        return getOrCreateIntBounds(blockName, facing, getShapeMode());
    }

    public static IntBounds getOrCreateIntBounds(String blockName, net.minecraft.core.Direction facing, ShapeMode mode) {
        String key = blockName + "|" + horizontalIndex(facing) + "|" + mode.name();
        return INT_BOUNDS_CACHE.computeIfAbsent(key, k -> computeIntBounds(blockName, facing, mode));
    }

    public static VoxelShape getOrCreateLocalShape(String blockName, net.minecraft.core.Direction facing, int offsetX, int offsetY, int offsetZ) {
        return getOrCreateLocalShape(blockName, facing, offsetX, offsetY, offsetZ, getShapeMode());
    }

    public static VoxelShape getOrCreateLocalShape(String blockName, net.minecraft.core.Direction facing, int offsetX, int offsetY, int offsetZ, ShapeMode mode) {
        SplitShapeSet shapes = getOrCreateLocalShapeSet(blockName, facing, mode);
        VoxelShape shape = shapes.shapes.get(packOffset(offsetX, offsetY, offsetZ));
        return shape == null ? Shapes.empty() : shape;
    }

    public static VoxelShape getOrScheduleLocalShape(String blockName, net.minecraft.core.Direction facing, int offsetX, int offsetY, int offsetZ) {
        return getOrScheduleLocalShape(blockName, facing, offsetX, offsetY, offsetZ, getShapeMode());
    }

    public static VoxelShape getOrScheduleLocalShape(String blockName, net.minecraft.core.Direction facing, int offsetX, int offsetY, int offsetZ, ShapeMode mode) {
        String key = blockName + "|" + horizontalIndex(facing) + "|" + mode.name();
        SplitShapeSet shapes = SPLIT_SHAPE_SET_CACHE.get(key);
        if (shapes != null) {
            VoxelShape shape = shapes.shapes.get(packOffset(offsetX, offsetY, offsetZ));
            return shape == null ? Shapes.empty() : shape;
        }
        scheduleLocalShapeSetBuild(blockName, facing, mode, key);
        return quickFallbackLocalShape(blockName, facing, offsetX, offsetY, offsetZ, mode);
    }

    public static boolean isLocalShapeSetReady(String blockName, net.minecraft.core.Direction facing) {
        return isLocalShapeSetReady(blockName, facing, getShapeMode());
    }

    public static boolean isLocalShapeSetReady(String blockName, net.minecraft.core.Direction facing, ShapeMode mode) {
        String key = blockName + "|" + horizontalIndex(facing) + "|" + mode.name();
        return SPLIT_SHAPE_SET_CACHE.containsKey(key);
    }

    public static boolean hasLocalShape(String blockName, net.minecraft.core.Direction facing, int offsetX, int offsetY, int offsetZ) {
        return hasLocalShape(blockName, facing, offsetX, offsetY, offsetZ, getShapeMode());
    }

    public static boolean hasLocalShape(String blockName, net.minecraft.core.Direction facing, int offsetX, int offsetY, int offsetZ, ShapeMode mode) {
        if (mode == ShapeMode.BLOCK) {
            return offsetX == 0 && offsetY == 0 && offsetZ == 0;
        }

        String setKey = blockName + "|" + horizontalIndex(facing) + "|" + mode.name();
        SplitShapeSet shapeSet = SPLIT_SHAPE_SET_CACHE.get(setKey);
        long packed = packOffset(offsetX, offsetY, offsetZ);
        if (shapeSet != null) {
            return shapeSet.shapes.containsKey(packed);
        }

        String cacheKey = persistentShapeKey(blockName, facing, mode);
        String fingerprint = modelFingerprint(blockName, mode);
        CachedShapeSet cached = loadPersistentCache().get(cacheKey);
        if (cached != null && fingerprint.equals(cached.fingerprint)) {
            List<AABB> boxes = cached.boxes.get(packed);
            return boxes != null && !boxes.isEmpty();
        }

        IntBounds bounds = getOrCreateIntBounds(blockName, facing, mode);
        if (bounds.isEmpty()
                || offsetX < bounds.minX() || offsetX > bounds.maxX()
                || offsetY < bounds.minY() || offsetY > bounds.maxY()
                || offsetZ < bounds.minZ() || offsetZ > bounds.maxZ()) {
            return false;
        }

        String occupancyKey = blockName + "|" + horizontalIndex(facing) + "|" + offsetX + "," + offsetY + "," + offsetZ + "|" + mode.name();
        return LOCAL_OCCUPANCY_CACHE.computeIfAbsent(occupancyKey,
                ignored -> !optimizeBoxes(computeLocalBoxes(blockName, facing, offsetX, offsetY, offsetZ, mode)).isEmpty());
    }

    public static void warmAllBlockShapes(Iterable<String> blockNames) {
        if (!WARM_RUNNING.compareAndSet(false, true)) {
            LOGGER.debug("[ZCraft shape cache] warm skipped: another warm task is already running");
            return;
        }
        long startNanos = System.nanoTime();
        ShapeMode mode = getShapeMode();
        loadPersistentCache();
        List<String> names = new ArrayList<>();
        for (String blockName : blockNames) {
            if (blockName != null && !blockName.isBlank()) {
                names.add(blockName);
            }
        }
        if (names.isEmpty()) {
            WARM_RUNNING.set(false);
            return;
        }

        bulkWarming = true;
        int threads = Math.min(getShapeCacheThreads(), names.size());
        LOGGER.debug("[ZCraft shape cache] warm start: blocks={}, mode={}, threads={}, cacheFile={}, persistentEntries={}",
                names.size(), mode.name(), threads, persistentCachePath(), PERSISTENT_SHAPE_CACHE.size());
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger rebuilt = new AtomicInteger();
        AtomicInteger cacheHits = new AtomicInteger();
        AtomicInteger voxelWarmed = new AtomicInteger();
        AtomicBoolean finished = new AtomicBoolean(false);
        startWarmWatchdog(finished, startNanos);
        ExecutorService executor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, ZcraftDecorationsMod.MODID + "-shape-cache");
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
            return thread;
        });
        try {
            List<Future<?>> futures = new ArrayList<>(names.size());
            for (String blockName : names) {
                futures.add(executor.submit(() -> {
                    long blockStart = System.nanoTime();
                    try {
                        WarmResult result = warmBlockShapes(blockName, mode);
                        rebuilt.addAndGet(result.rebuiltDirections());
                        cacheHits.addAndGet(result.cachedDirections());
                        voxelWarmed.addAndGet(result.voxelWarmedDirections());
                        long elapsedMs = elapsedMillis(blockStart);
                        if (elapsedMs >= 1000 || result.rebuiltDirections() > 0) {
                            LOGGER.debug("[ZCraft shape cache] block {} done: rebuiltDirections={}, cachedDirections={}, voxelWarmedDirections={}, time={}ms",
                                    blockName, result.rebuiltDirections(), result.cachedDirections(), result.voxelWarmedDirections(), elapsedMs);
                        }
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        LOGGER.error("[ZCraft shape cache] block {} failed", blockName, e);
                    } finally {
                        int done = completed.incrementAndGet();
                        if (done % 25 == 0 || done == names.size()) {
                            LOGGER.debug("[ZCraft shape cache] progress: {}/{} blocks, rebuiltDirections={}, cachedDirections={}, voxelWarmedDirections={}, failed={}, elapsed={}ms",
                                    done, names.size(), rebuilt.get(), cacheHits.get(), voxelWarmed.get(), failed.get(), elapsedMillis(startNanos));
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    failed.incrementAndGet();
                    LOGGER.error("[ZCraft shape cache] worker failed", e);
                }
            }
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            bulkWarming = false;
            savePersistentCacheIfDirty();
            finished.set(true);
            WARM_RUNNING.set(false);
            LOGGER.debug("[ZCraft shape cache] warm finished: blocks={}, rebuiltDirections={}, cachedDirections={}, voxelWarmedDirections={}, failed={}, elapsed={}ms",
                    names.size(), rebuilt.get(), cacheHits.get(), voxelWarmed.get(), failed.get(), elapsedMillis(startNanos));
        }
    }

    public static void warmAllBlockShapesAsync(Iterable<String> blockNames, String reason) {
        Thread thread = new Thread(() -> {
            LOGGER.debug("[ZCraft shape cache] async warm requested: {}", reason);
            warmAllBlockShapes(blockNames);
        }, ZcraftDecorationsMod.MODID + "-shape-cache-dispatch");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
        thread.start();
    }

    public static void warmBlockShapes(String blockName) {
        warmBlockShapes(blockName, getShapeMode());
    }

    public static WarmResult warmBlockShapes(String blockName, ShapeMode mode) {
        if (blockName == null || blockName.isBlank()) {
            return new WarmResult(0, 0, 0);
        }
        String fingerprint = modelFingerprint(blockName, mode);
        int cachedDirections = 0;
        int rebuiltDirections = 0;
        int voxelWarmedDirections = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            String cacheKey = persistentShapeKey(blockName, direction, mode);
            CachedShapeSet cached = loadPersistentCache().get(cacheKey);
            if (cached != null && fingerprint.equals(cached.fingerprint)) {
                cachedDirections++;
            } else {
                cachePersistentShapeSet(cacheKey, buildCachedShapeSet(blockName, direction, mode, fingerprint));
                rebuiltDirections++;
            }
            if (ZcraftConfig.WARM_VOXEL_SHAPE_CACHE.get()) {
                getOrCreateLocalShapeSet(blockName, direction, mode);
                voxelWarmedDirections++;
            }
        }
        return new WarmResult(rebuiltDirections, cachedDirections, voxelWarmedDirections);
    }

    public record WarmResult(int rebuiltDirections, int cachedDirections, int voxelWarmedDirections) {
    }

    public enum ShapeMode {
        BLOCK,
        BOUNDS,
        FULL
    }

    private static VoxelShape[] buildShapesForBlock(String blockName, ShapeMode mode) {
        return new VoxelShape[] {
                getOrCreateLocalShape(blockName, Direction.NORTH, 0, 0, 0, mode),
                getOrCreateLocalShape(blockName, Direction.EAST, 0, 0, 0, mode),
                getOrCreateLocalShape(blockName, Direction.SOUTH, 0, 0, 0, mode),
                getOrCreateLocalShape(blockName, Direction.WEST, 0, 0, 0, mode)
        };
    }

    private static ShapeMode getShapeMode() {
        String raw = getConfiguredShapeMode();
        return switch (raw) {
            case "full" -> ShapeMode.FULL;
            case "block", "none" -> ShapeMode.BLOCK;
            case "bounds" -> ShapeMode.BOUNDS;
            case "" -> DEFAULT_SHAPE_MODE;
            default -> DEFAULT_SHAPE_MODE;
        };
    }

    private static String getConfiguredShapeMode() {
        String configured = ZcraftConfig.SHAPE_MODE.get().trim().toLowerCase();
        if (!configured.isEmpty()) {
            return configured;
        }
        return System.getProperty(SHAPE_MODE_PROPERTY, "").trim().toLowerCase();
    }

    public record IntBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        public boolean isEmpty() {
            return maxX < minX || maxY < minY || maxZ < minZ;
        }
    }

    private static IntBounds computeIntBounds(String blockName, net.minecraft.core.Direction facing, ShapeMode mode) {
        final double eps = 1.0E-6;

        if (mode == ShapeMode.BLOCK) {
            return new IntBounds(0, 0, 0, 0, 0, 0);
        }

        ResourceLocation blockModel = resolveBlockModel(blockName);
        if (blockModel == null) {
            return new IntBounds(0, 0, 0, 0, 0, 0);
        }

        ModelGeometry geometry = MODEL_GEOMETRY_CACHE.computeIfAbsent(blockModel, ModelShapeCache::loadModelGeometry);
        if (geometry == null || geometry.bounds16 == null) {
            return new IntBounds(0, 0, 0, 0, 0, 0);
        }

        int turns = horizontalIndex(facing);
        AABB bounds16 = clampToReasonableRange(rotate90Y16(geometry.bounds16, turns));
        bounds16 = snapBounds16ToCellEdges(bounds16, getProxySnap16());

        int minX = (int) Math.floor((bounds16.minX / 16.0) + eps);
        int minY = (int) Math.floor((bounds16.minY / 16.0) + eps);
        int minZ = (int) Math.floor((bounds16.minZ / 16.0) + eps);
        int maxX = (int) Math.floor((bounds16.maxX / 16.0) - eps);
        int maxY = (int) Math.floor((bounds16.maxY / 16.0) - eps);
        int maxZ = (int) Math.floor((bounds16.maxZ / 16.0) - eps);

        if (maxY < 0) {
            return new IntBounds(0, -1, 0, -1, 0, -1);
        }
        minY = Math.max(0, minY);
        maxY = Math.max(0, maxY);
        return new IntBounds(minX, maxX, minY, maxY, minZ, maxZ);
    }

    private static VoxelShape computeLocalShape(String blockName, net.minecraft.core.Direction facing, int offsetX, int offsetY, int offsetZ, ShapeMode mode) {
        return computeLocalShapeFromModel(blockName, facing, offsetX, offsetY, offsetZ, mode);
    }

    private static SplitShapeSet getOrCreateLocalShapeSet(String blockName, net.minecraft.core.Direction facing, ShapeMode mode) {
        String key = blockName + "|" + horizontalIndex(facing) + "|" + mode.name();
        return SPLIT_SHAPE_SET_CACHE.computeIfAbsent(key, k -> buildLocalShapeSet(blockName, facing, mode));
    }

    private static void scheduleLocalShapeSetBuild(String blockName, net.minecraft.core.Direction facing, ShapeMode mode, String key) {
        if (SPLIT_SHAPE_SET_CACHE.containsKey(key)) {
            return;
        }
        SPLIT_SHAPE_BUILD_TASKS.computeIfAbsent(key, ignored -> ASYNC_SHAPE_BUILD_EXECUTOR.submit(() -> {
            try {
                SPLIT_SHAPE_SET_CACHE.computeIfAbsent(key, k -> buildLocalShapeSet(blockName, facing, mode));
            } catch (Exception e) {
                LOGGER.error("[ZCraft shape cache] async shape build failed: block={}, facing={}, mode={}", blockName, facing, mode, e);
            } finally {
                SPLIT_SHAPE_BUILD_TASKS.remove(key);
            }
        }));
    }

    private static SplitShapeSet buildLocalShapeSet(String blockName, net.minecraft.core.Direction facing, ShapeMode mode) {
        long startNanos = System.nanoTime();
        String cacheKey = persistentShapeKey(blockName, facing, mode);
        String fingerprint = modelFingerprint(blockName, mode);
        CachedShapeSet cached = loadPersistentCache().get(cacheKey);
        if (cached != null && fingerprint.equals(cached.fingerprint)) {
            CachedShapeSet optimized = cached.optimized();
            if (optimized.boxCount() < cached.boxCount()) {
                cachePersistentShapeSet(cacheKey, optimized);
                LOGGER.info("[ZCraft shape cache] optimized persisted shape: block={}, facing={}, boxes={} -> {}",
                        blockName, facing, cached.boxCount(), optimized.boxCount());
            }
            SplitShapeSet set = optimized.toSplitShapeSet();
            long elapsedMs = elapsedMillis(startNanos);
            if (elapsedMs >= 500) {
                LOGGER.debug("[ZCraft shape cache] loaded persisted shape: block={}, facing={}, boxes={}, time={}ms",
                        blockName, facing, optimized.boxCount(), elapsedMs);
            }
            return set;
        }

        IntBounds bounds = getOrCreateIntBounds(blockName, facing, mode);
        Map<Long, VoxelShape> shapes = new ConcurrentHashMap<>();
        if (bounds.isEmpty()) {
            SplitShapeSet empty = new SplitShapeSet(bounds, shapes);
            cachePersistentShapeSet(cacheKey, fingerprint, empty);
            return empty;
        }

        for (int dx = bounds.minX(); dx <= bounds.maxX(); dx++) {
            for (int dy = bounds.minY(); dy <= bounds.maxY(); dy++) {
                for (int dz = bounds.minZ(); dz <= bounds.maxZ(); dz++) {
                    final int localX = dx;
                    final int localY = dy;
                    final int localZ = dz;
                    VoxelShape shape = SPLIT_SHAPE_CACHE.computeIfAbsent(
                            blockName + "|" + horizontalIndex(facing) + "|" + localX + "," + localY + "," + localZ + "|" + mode.name(),
                            k -> computeLocalShape(blockName, facing, localX, localY, localZ, mode));
                    if (!shape.isEmpty()) {
                        shapes.put(packOffset(localX, localY, localZ), shape);
                    }
                }
            }
        }
        SplitShapeSet result = new SplitShapeSet(bounds, shapes);
        cachePersistentShapeSet(cacheKey, fingerprint, result);
        long elapsedMs = elapsedMillis(startNanos);
        LOGGER.debug("[ZCraft shape cache] rebuilt shape: block={}, facing={}, cells={}, time={}ms",
                blockName, facing, result.shapes.size(), elapsedMs);
        return result;
    }

    private static VoxelShape quickFallbackLocalShape(String blockName, Direction facing, int offsetX, int offsetY, int offsetZ, ShapeMode mode) {
        if (mode == ShapeMode.BLOCK) {
            return offsetX == 0 && offsetY == 0 && offsetZ == 0 ? Shapes.block() : Shapes.empty();
        }
        String fallbackKey = blockName + "|" + horizontalIndex(facing) + "|" + offsetX + "," + offsetY + "," + offsetZ + "|" + mode.name()
                + "|" + modelFingerprint(blockName, mode);
        return FALLBACK_SHAPE_CACHE.computeIfAbsent(fallbackKey, ignored -> buildQuickFallbackLocalShape(blockName, facing, offsetX, offsetY, offsetZ, mode));
    }

    private static VoxelShape buildQuickFallbackLocalShape(String blockName, Direction facing, int offsetX, int offsetY, int offsetZ, ShapeMode mode) {
        long packed = packOffset(offsetX, offsetY, offsetZ);
        String cacheKey = persistentShapeKey(blockName, facing, mode);
        String fingerprint = modelFingerprint(blockName, mode);
        CachedShapeSet cached = loadPersistentCache().get(cacheKey);
        if (cached != null && fingerprint.equals(cached.fingerprint)) {
            return boundsShapeFromBoxes(cached.boxes.get(packed));
        }

        IntBounds bounds = getOrCreateIntBounds(blockName, facing, mode);
        if (bounds.isEmpty()
                || offsetX < bounds.minX() || offsetX > bounds.maxX()
                || offsetY < bounds.minY() || offsetY > bounds.maxY()
                || offsetZ < bounds.minZ() || offsetZ > bounds.maxZ()) {
            return Shapes.empty();
        }
        return boundsShapeFromBoxes(computeLocalBoxes(blockName, facing, offsetX, offsetY, offsetZ, mode));
    }

    private static VoxelShape boundsShapeFromBoxes(List<AABB> boxes) {
        if (boxes == null || boxes.isEmpty()) {
            return Shapes.empty();
        }
        AABB bounds = null;
        for (AABB box : boxes) {
            if (box.maxX <= box.minX || box.maxY <= box.minY || box.maxZ <= box.minZ) {
                continue;
            }
            bounds = bounds == null ? box : bounds.minmax(box);
        }
        return bounds == null ? Shapes.empty() : Shapes.create(bounds);
    }

    private record SplitShapeSet(IntBounds bounds, Map<Long, VoxelShape> shapes) {
    }

    private record CachedShapeSet(String fingerprint, IntBounds bounds, Map<Long, List<AABB>> boxes) {
        int boxCount() {
            int total = 0;
            for (List<AABB> value : boxes.values()) {
                total += value.size();
            }
            return total;
        }

        SplitShapeSet toSplitShapeSet() {
            Map<Long, VoxelShape> shapes = new ConcurrentHashMap<>();
            for (Map.Entry<Long, List<AABB>> entry : boxes.entrySet()) {
                VoxelShape shape = shapeFromBoxes(entry.getValue());
                if (!shape.isEmpty()) {
                    shapes.put(entry.getKey(), shape);
                }
            }
            return new SplitShapeSet(bounds, shapes);
        }

        CachedShapeSet optimized() {
            Map<Long, List<AABB>> optimizedBoxes = new HashMap<>();
            for (Map.Entry<Long, List<AABB>> entry : boxes.entrySet()) {
                List<AABB> aabbs = optimizeBoxes(entry.getValue());
                if (!aabbs.isEmpty()) {
                    optimizedBoxes.put(entry.getKey(), aabbs);
                }
            }
            return new CachedShapeSet(fingerprint, bounds, optimizedBoxes);
        }
    }

    private record ModelGeometry(AABB bounds16, List<ModelElement> elements, int elementCount) {
    }

    private record ModelElement(AABB box16, ElementRotation rotation, AABB bounds16, List<AABB> voxelBoxes16) {
        boolean isRotated() {
            return rotation != null && Math.abs(rotation.angleRad()) > EPS;
        }
    }

    private record ElementRotation(String axis, double angleRad, double originX, double originY, double originZ) {
    }

    private static VoxelShape computeLocalShapeFromModel(String blockName, Direction facing, int offsetX, int offsetY, int offsetZ, ShapeMode mode) {
        if (mode == ShapeMode.BLOCK) {
            return (offsetX == 0 && offsetY == 0 && offsetZ == 0) ? Shapes.block() : Shapes.empty();
        }

        ResourceLocation blockModel = resolveBlockModel(blockName);
        if (blockModel == null) {
            return (offsetX == 0 && offsetY == 0 && offsetZ == 0) ? Shapes.block() : Shapes.empty();
        }

        ModelGeometry geometry = MODEL_GEOMETRY_CACHE.computeIfAbsent(blockModel, ModelShapeCache::loadModelGeometry);
        if (geometry == null || geometry.bounds16 == null) {
            return (offsetX == 0 && offsetY == 0 && offsetZ == 0) ? Shapes.block() : Shapes.empty();
        }

        int turns = horizontalIndex(facing);
        AABB bounds = rotate90Y16(geometry.bounds16, turns);
        bounds = clampToReasonableRange(bounds);

        if (mode == ShapeMode.BOUNDS || geometry.elements == null || geometry.elementCount > MAX_FULL_ELEMENTS) {
            return buildLocalShapeFromBox16(bounds, offsetX, offsetY, offsetZ);
        }

        double cellMinX = offsetX * 16.0;
        double cellMinY = offsetY * 16.0;
        double cellMinZ = offsetZ * 16.0;
        double cellMaxX = cellMinX + 16.0;
        double cellMaxY = cellMinY + 16.0;
        double cellMaxZ = cellMinZ + 16.0;

        List<AABB> boxes = new ArrayList<>();
        for (ModelElement element : geometry.elements) {
            AABB rotated = rotate90Y16(element.bounds16, turns);
            double iMinX = Math.max(rotated.minX, cellMinX);
            double iMinY = Math.max(rotated.minY, cellMinY);
            double iMinZ = Math.max(rotated.minZ, cellMinZ);
            double iMaxX = Math.min(rotated.maxX, cellMaxX);
            double iMaxY = Math.min(rotated.maxY, cellMaxY);
            double iMaxZ = Math.min(rotated.maxZ, cellMaxZ);
            if (iMaxX <= iMinX || iMaxY <= iMinY || iMaxZ <= iMinZ) {
                continue;
            }
            if (element.isRotated()) {
                for (AABB voxelBox : element.voxelBoxes16) {
                    AABB rotatedVoxel = rotate90Y16(voxelBox, turns);
                    double vMinX = Math.max(rotatedVoxel.minX, cellMinX);
                    double vMinY = Math.max(rotatedVoxel.minY, cellMinY);
                    double vMinZ = Math.max(rotatedVoxel.minZ, cellMinZ);
                    double vMaxX = Math.min(rotatedVoxel.maxX, cellMaxX);
                    double vMaxY = Math.min(rotatedVoxel.maxY, cellMaxY);
                    double vMaxZ = Math.min(rotatedVoxel.maxZ, cellMaxZ);
                    if (vMaxX <= vMinX || vMaxY <= vMinY || vMaxZ <= vMinZ) {
                        continue;
                    }
                    addLocalBox(boxes, cellMinX, cellMinY, cellMinZ, vMinX, vMinY, vMinZ, vMaxX, vMaxY, vMaxZ);
                }
            } else {
                addLocalBox(boxes, cellMinX, cellMinY, cellMinZ, iMinX, iMinY, iMinZ, iMaxX, iMaxY, iMaxZ);
            }
        }
        return shapeFromBoxes(boxes);
    }

    private static JsonObject readJson(String classpathPath) {
        String raw = readResourceString(classpathPath);
        if (raw == null) {
            return null;
        }
        try {
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static String readResourceString(String classpathPath) {
        try (InputStream in = ModelShapeCache.class.getClassLoader().getResourceAsStream(classpathPath)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static VoxelShape buildLocalShapeFromBox16(AABB box16, int offsetX, int offsetY, int offsetZ) {
        double cellMinX = offsetX * 16.0;
        double cellMinY = offsetY * 16.0;
        double cellMinZ = offsetZ * 16.0;
        double cellMaxX = cellMinX + 16.0;
        double cellMaxY = cellMinY + 16.0;
        double cellMaxZ = cellMinZ + 16.0;

        double iMinX = Math.max(box16.minX, cellMinX);
        double iMinY = Math.max(box16.minY, cellMinY);
        double iMinZ = Math.max(box16.minZ, cellMinZ);
        double iMaxX = Math.min(box16.maxX, cellMaxX);
        double iMaxY = Math.min(box16.maxY, cellMaxY);
        double iMaxZ = Math.min(box16.maxZ, cellMaxZ);
        if (iMaxX <= iMinX || iMaxY <= iMinY || iMaxZ <= iMinZ) {
            return Shapes.empty();
        }
        return Shapes.box(
                (iMinX - cellMinX) / 16.0,
                clamp((iMinY - cellMinY) / 16.0, 0.0, 1.0),
                (iMinZ - cellMinZ) / 16.0,
                (iMaxX - cellMinX) / 16.0,
                clamp((iMaxY - cellMinY) / 16.0, 0.0, 1.0),
                (iMaxZ - cellMinZ) / 16.0);
    }

    private static List<AABB> buildVoxelizedElementBoxes(AABB box, ElementRotation rotation, AABB bounds, double step16) {
        List<AABB> boxes = new ArrayList<>();
        double startX = Math.floor(bounds.minX / step16) * step16;
        double startY = Math.floor(bounds.minY / step16) * step16;
        double startZ = Math.floor(bounds.minZ / step16) * step16;
        double expansion = Math.max(step16 * 0.5, MIN_ELEMENT_THICKNESS_16);

        for (double x = startX; x < bounds.maxX - EPS; x += step16) {
            double vx0 = Math.max(x, bounds.minX);
            double vx1 = Math.min(x + step16, bounds.maxX);
            if (vx1 <= vx0) {
                continue;
            }
            for (double y = startY; y < bounds.maxY - EPS; y += step16) {
                double vy0 = Math.max(y, bounds.minY);
                double vy1 = Math.min(y + step16, bounds.maxY);
                if (vy1 <= vy0) {
                    continue;
                }
                for (double z = startZ; z < bounds.maxZ - EPS; z += step16) {
                    double vz0 = Math.max(z, bounds.minZ);
                    double vz1 = Math.min(z + step16, bounds.maxZ);
                    if (vz1 <= vz0) {
                        continue;
                    }
                    double cx = (vx0 + vx1) * 0.5;
                    double cy = (vy0 + vy1) * 0.5;
                    double cz = (vz0 + vz1) * 0.5;
                    if (rotatedVoxelCenterHitsBox(box, rotation, cx, cy, cz, expansion)) {
                        boxes.add(new AABB(vx0, vy0, vz0, vx1, vy1, vz1));
                    }
                }
            }
        }
        return boxes;
    }

    private static boolean rotatedVoxelCenterHitsBox(AABB box, ElementRotation rotation, double x, double y, double z, double expansion) {
        double[] localPoint = rotatePoint(
                x,
                y,
                z,
                rotation.originX,
                rotation.originY,
                rotation.originZ,
                rotation.axis,
                -rotation.angleRad);
        return localPoint[0] >= box.minX - expansion && localPoint[0] <= box.maxX + expansion
                && localPoint[1] >= box.minY - expansion && localPoint[1] <= box.maxY + expansion
                && localPoint[2] >= box.minZ - expansion && localPoint[2] <= box.maxZ + expansion;
    }

    private static void addLocalBox(
            List<AABB> boxes,
            double cellMinX,
            double cellMinY,
            double cellMinZ,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {
        boxes.add(new AABB(
                (minX - cellMinX) / 16.0,
                clamp((minY - cellMinY) / 16.0, 0.0, 1.0),
                (minZ - cellMinZ) / 16.0,
                (maxX - cellMinX) / 16.0,
                clamp((maxY - cellMinY) / 16.0, 0.0, 1.0),
                (maxZ - cellMinZ) / 16.0));
    }

    private static VoxelShape shapeFromBoxes(List<AABB> rawBoxes) {
        List<AABB> boxes = optimizeBoxes(rawBoxes);
        if (boxes.isEmpty()) {
            return Shapes.empty();
        }
        if (boxes.size() == 1) {
            return Shapes.create(boxes.get(0));
        }

        VoxelShape shape = Shapes.empty();
        for (AABB box : boxes) {
            shape = Shapes.joinUnoptimized(shape, Shapes.create(box), BooleanOp.OR);
        }
        return shape.optimize();
    }

    private static List<AABB> optimizeBoxes(List<AABB> boxes) {
        if (boxes == null || boxes.size() <= 1) {
            return boxes == null || boxes.isEmpty() ? List.of() : List.of(boxes.get(0));
        }

        List<AABB> current = new ArrayList<>(boxes.size());
        for (AABB box : boxes) {
            if (box.maxX > box.minX && box.maxY > box.minY && box.maxZ > box.minZ) {
                current.add(box);
            }
        }
        if (current.size() <= 1) {
            return current;
        }

        int previousSize;
        do {
            previousSize = current.size();
            current = mergeBoxesOnAxis(current, Direction.Axis.X);
            current = mergeBoxesOnAxis(current, Direction.Axis.Z);
            current = mergeBoxesOnAxis(current, Direction.Axis.Y);
        } while (current.size() < previousSize);
        return current;
    }

    private static List<AABB> mergeBoxesOnAxis(List<AABB> boxes, Direction.Axis axis) {
        if (boxes.size() <= 1) {
            return boxes;
        }
        List<AABB> sorted = new ArrayList<>(boxes);
        sorted.sort((a, b) -> compareForMerge(a, b, axis));

        List<AABB> merged = new ArrayList<>(sorted.size());
        AABB current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            AABB next = sorted.get(i);
            if (canMerge(current, next, axis)) {
                current = merge(current, next, axis);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private static int compareForMerge(AABB a, AABB b, Direction.Axis axis) {
        int cmp;
        if (axis == Direction.Axis.X) {
            cmp = compareD(a.minY, b.minY);
            if (cmp != 0) return cmp;
            cmp = compareD(a.maxY, b.maxY);
            if (cmp != 0) return cmp;
            cmp = compareD(a.minZ, b.minZ);
            if (cmp != 0) return cmp;
            cmp = compareD(a.maxZ, b.maxZ);
            if (cmp != 0) return cmp;
            cmp = compareD(a.minX, b.minX);
            return cmp != 0 ? cmp : compareD(a.maxX, b.maxX);
        }
        if (axis == Direction.Axis.Y) {
            cmp = compareD(a.minX, b.minX);
            if (cmp != 0) return cmp;
            cmp = compareD(a.maxX, b.maxX);
            if (cmp != 0) return cmp;
            cmp = compareD(a.minZ, b.minZ);
            if (cmp != 0) return cmp;
            cmp = compareD(a.maxZ, b.maxZ);
            if (cmp != 0) return cmp;
            cmp = compareD(a.minY, b.minY);
            return cmp != 0 ? cmp : compareD(a.maxY, b.maxY);
        }
        cmp = compareD(a.minX, b.minX);
        if (cmp != 0) return cmp;
        cmp = compareD(a.maxX, b.maxX);
        if (cmp != 0) return cmp;
        cmp = compareD(a.minY, b.minY);
        if (cmp != 0) return cmp;
        cmp = compareD(a.maxY, b.maxY);
        if (cmp != 0) return cmp;
        cmp = compareD(a.minZ, b.minZ);
        return cmp != 0 ? cmp : compareD(a.maxZ, b.maxZ);
    }

    private static boolean canMerge(AABB a, AABB b, Direction.Axis axis) {
        if (axis == Direction.Axis.X) {
            return same(a.minY, b.minY) && same(a.maxY, b.maxY)
                    && same(a.minZ, b.minZ) && same(a.maxZ, b.maxZ)
                    && same(a.maxX, b.minX);
        }
        if (axis == Direction.Axis.Y) {
            return same(a.minX, b.minX) && same(a.maxX, b.maxX)
                    && same(a.minZ, b.minZ) && same(a.maxZ, b.maxZ)
                    && same(a.maxY, b.minY);
        }
        return same(a.minX, b.minX) && same(a.maxX, b.maxX)
                && same(a.minY, b.minY) && same(a.maxY, b.maxY)
                && same(a.maxZ, b.minZ);
    }

    private static AABB merge(AABB a, AABB b, Direction.Axis axis) {
        if (axis == Direction.Axis.X) {
            return new AABB(a.minX, a.minY, a.minZ, b.maxX, a.maxY, a.maxZ);
        }
        if (axis == Direction.Axis.Y) {
            return new AABB(a.minX, a.minY, a.minZ, a.maxX, b.maxY, a.maxZ);
        }
        return new AABB(a.minX, a.minY, a.minZ, a.maxX, a.maxY, b.maxZ);
    }

    private static int compareD(double a, double b) {
        if (same(a, b)) {
            return 0;
        }
        return Double.compare(a, b);
    }

    private static boolean same(double a, double b) {
        return Math.abs(a - b) <= EPS;
    }

    private static void cachePersistentShapeSet(String cacheKey, String fingerprint, SplitShapeSet shapeSet) {
        Map<Long, List<AABB>> boxes = new HashMap<>();
        for (Map.Entry<Long, VoxelShape> entry : shapeSet.shapes.entrySet()) {
            List<AABB> aabbs = optimizeBoxes(entry.getValue().toAabbs());
            if (!aabbs.isEmpty()) {
                boxes.put(entry.getKey(), new ArrayList<>(aabbs));
            }
        }
        PERSISTENT_SHAPE_CACHE.put(cacheKey, new CachedShapeSet(fingerprint, shapeSet.bounds, boxes));
        persistentCacheDirty = true;
        if (!bulkWarming) {
            savePersistentCacheIfDirty();
        }
    }

    private static void cachePersistentShapeSet(String cacheKey, CachedShapeSet shapeSet) {
        PERSISTENT_SHAPE_CACHE.put(cacheKey, shapeSet);
        persistentCacheDirty = true;
        if (!bulkWarming) {
            savePersistentCacheIfDirty();
        }
    }

    private static CachedShapeSet buildCachedShapeSet(String blockName, Direction facing, ShapeMode mode, String fingerprint) {
        long startNanos = System.nanoTime();
        IntBounds bounds = getOrCreateIntBounds(blockName, facing, mode);
        Map<Long, List<AABB>> boxes = new HashMap<>();
        if (!bounds.isEmpty()) {
            for (int dx = bounds.minX(); dx <= bounds.maxX(); dx++) {
                for (int dy = bounds.minY(); dy <= bounds.maxY(); dy++) {
                    for (int dz = bounds.minZ(); dz <= bounds.maxZ(); dz++) {
                        List<AABB> cellBoxes = optimizeBoxes(computeLocalBoxes(blockName, facing, dx, dy, dz, mode));
                        if (!cellBoxes.isEmpty()) {
                            boxes.put(packOffset(dx, dy, dz), cellBoxes);
                        }
                    }
                }
            }
        }
        CachedShapeSet result = new CachedShapeSet(fingerprint, bounds, boxes);
        LOGGER.debug("[ZCraft shape cache] rebuilt persistent boxes: block={}, facing={}, cells={}, boxes={}, time={}ms",
                blockName, facing, boxes.size(), result.boxCount(), elapsedMillis(startNanos));
        return result;
    }

    private static List<AABB> computeLocalBoxes(String blockName, Direction facing, int offsetX, int offsetY, int offsetZ, ShapeMode mode) {
        if (mode == ShapeMode.BLOCK) {
            return offsetX == 0 && offsetY == 0 && offsetZ == 0 ? List.of(new AABB(0, 0, 0, 1, 1, 1)) : List.of();
        }

        ResourceLocation blockModel = resolveBlockModel(blockName);
        if (blockModel == null) {
            return offsetX == 0 && offsetY == 0 && offsetZ == 0 ? List.of(new AABB(0, 0, 0, 1, 1, 1)) : List.of();
        }

        ModelGeometry geometry = MODEL_GEOMETRY_CACHE.computeIfAbsent(blockModel, ModelShapeCache::loadModelGeometry);
        if (geometry == null || geometry.bounds16 == null) {
            return offsetX == 0 && offsetY == 0 && offsetZ == 0 ? List.of(new AABB(0, 0, 0, 1, 1, 1)) : List.of();
        }

        int turns = horizontalIndex(facing);
        double cellMinX = offsetX * 16.0;
        double cellMinY = offsetY * 16.0;
        double cellMinZ = offsetZ * 16.0;
        double cellMaxX = cellMinX + 16.0;
        double cellMaxY = cellMinY + 16.0;
        double cellMaxZ = cellMinZ + 16.0;
        List<AABB> boxes = new ArrayList<>();

        if (mode == ShapeMode.BOUNDS || geometry.elements == null || geometry.elementCount > MAX_FULL_ELEMENTS) {
            AABB bounds = clampToReasonableRange(rotate90Y16(geometry.bounds16, turns));
            addLocalBoxIfIntersects(boxes, cellMinX, cellMinY, cellMinZ, cellMaxX, cellMaxY, cellMaxZ, bounds);
            return boxes;
        }

        for (ModelElement element : geometry.elements) {
            if (element.isRotated()) {
                for (AABB voxelBox : element.voxelBoxes16) {
                    addLocalBoxIfIntersects(boxes, cellMinX, cellMinY, cellMinZ, cellMaxX, cellMaxY, cellMaxZ, rotate90Y16(voxelBox, turns));
                }
            } else {
                addLocalBoxIfIntersects(boxes, cellMinX, cellMinY, cellMinZ, cellMaxX, cellMaxY, cellMaxZ, rotate90Y16(element.bounds16, turns));
            }
        }
        return boxes;
    }

    private static void addLocalBoxIfIntersects(
            List<AABB> boxes,
            double cellMinX,
            double cellMinY,
            double cellMinZ,
            double cellMaxX,
            double cellMaxY,
            double cellMaxZ,
            AABB box16) {
        double minX = Math.max(box16.minX, cellMinX);
        double minY = Math.max(box16.minY, cellMinY);
        double minZ = Math.max(box16.minZ, cellMinZ);
        double maxX = Math.min(box16.maxX, cellMaxX);
        double maxY = Math.min(box16.maxY, cellMaxY);
        double maxZ = Math.min(box16.maxZ, cellMaxZ);
        if (maxX <= minX || maxY <= minY || maxZ <= minZ) {
            return;
        }
        boxes.add(new AABB(
                (minX - cellMinX) / 16.0,
                clamp((minY - cellMinY) / 16.0, 0.0, 1.0),
                (minZ - cellMinZ) / 16.0,
                (maxX - cellMinX) / 16.0,
                clamp((maxY - cellMinY) / 16.0, 0.0, 1.0),
                (maxZ - cellMinZ) / 16.0));
    }

    private static Map<String, CachedShapeSet> loadPersistentCache() {
        if (persistentCacheLoaded) {
            return PERSISTENT_SHAPE_CACHE;
        }
        synchronized (PERSISTENT_SHAPE_CACHE) {
            if (persistentCacheLoaded) {
                return PERSISTENT_SHAPE_CACHE;
            }
            long startNanos = System.nanoTime();
            Path path = persistentCachePath();
            if (Files.isRegularFile(path)) {
                LOGGER.debug("[ZCraft shape cache] loading persistent cache: {}", path);
                try (InputStream in = new GZIPInputStream(Files.newInputStream(path));
                     InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    if (root.has("version") && root.get("version").getAsInt() == 1) {
                        JsonObject entries = root.getAsJsonObject("entries");
                        if (entries != null) {
                            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                                CachedShapeSet cached = readCachedShapeSet(entry.getValue());
                                if (cached != null) {
                                    PERSISTENT_SHAPE_CACHE.put(entry.getKey(), cached);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("[ZCraft shape cache] failed to load persistent cache {}; ignoring it", path, e);
                    PERSISTENT_SHAPE_CACHE.clear();
                }
            } else {
                LOGGER.debug("[ZCraft shape cache] no persistent cache found at {}", path);
            }
            persistentCacheLoaded = true;
            LOGGER.debug("[ZCraft shape cache] persistent cache load complete: entries={}, time={}ms",
                    PERSISTENT_SHAPE_CACHE.size(), elapsedMillis(startNanos));
            return PERSISTENT_SHAPE_CACHE;
        }
    }

    private static void savePersistentCacheIfDirty() {
        if (!persistentCacheDirty || !persistentCacheLoaded) {
            return;
        }
        synchronized (PERSISTENT_SHAPE_CACHE) {
            if (!persistentCacheDirty) {
                return;
            }
            try {
                long startNanos = System.nanoTime();
                Path path = persistentCachePath();
                Files.createDirectories(path.getParent());
                LOGGER.debug("[ZCraft shape cache] saving persistent cache: entries={}, path={}", PERSISTENT_SHAPE_CACHE.size(), path);
                JsonObject root = new JsonObject();
                root.addProperty("version", 1);
                JsonObject entries = new JsonObject();
                for (Map.Entry<String, CachedShapeSet> entry : PERSISTENT_SHAPE_CACHE.entrySet()) {
                    entries.add(entry.getKey(), writeCachedShapeSet(entry.getValue()));
                }
                root.add("entries", entries);
                try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(path));
                     OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                    writer.write(root.toString());
                }
                persistentCacheDirty = false;
                LOGGER.debug("[ZCraft shape cache] persistent cache save complete: entries={}, time={}ms",
                        PERSISTENT_SHAPE_CACHE.size(), elapsedMillis(startNanos));
            } catch (Exception e) {
                persistentCacheDirty = true;
                LOGGER.error("[ZCraft shape cache] failed to save persistent cache", e);
            }
        }
    }

    private static CachedShapeSet readCachedShapeSet(JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) {
            return null;
        }
        JsonObject json = raw.getAsJsonObject();
        JsonElement fingerprintEl = json.get("fingerprint");
        JsonArray boundsEl = json.getAsJsonArray("bounds");
        JsonObject shapesEl = json.getAsJsonObject("shapes");
        if (fingerprintEl == null || boundsEl == null || boundsEl.size() < 6 || shapesEl == null) {
            return null;
        }

        IntBounds bounds = new IntBounds(
                boundsEl.get(0).getAsInt(),
                boundsEl.get(1).getAsInt(),
                boundsEl.get(2).getAsInt(),
                boundsEl.get(3).getAsInt(),
                boundsEl.get(4).getAsInt(),
                boundsEl.get(5).getAsInt());
        Map<Long, List<AABB>> boxes = new HashMap<>();
        for (Map.Entry<String, JsonElement> shapeEntry : shapesEl.entrySet()) {
            long packed = Long.parseLong(shapeEntry.getKey());
            JsonArray boxesEl = shapeEntry.getValue().getAsJsonArray();
            List<AABB> aabbs = new ArrayList<>(boxesEl.size());
            for (JsonElement boxEl : boxesEl) {
                JsonArray box = boxEl.getAsJsonArray();
                if (box.size() >= 6) {
                    aabbs.add(new AABB(
                            box.get(0).getAsDouble(),
                            box.get(1).getAsDouble(),
                            box.get(2).getAsDouble(),
                            box.get(3).getAsDouble(),
                            box.get(4).getAsDouble(),
                            box.get(5).getAsDouble()));
                }
            }
            if (!aabbs.isEmpty()) {
                boxes.put(packed, aabbs);
            }
        }
        return new CachedShapeSet(fingerprintEl.getAsString(), bounds, boxes);
    }

    private static JsonObject writeCachedShapeSet(CachedShapeSet cached) {
        JsonObject json = new JsonObject();
        json.addProperty("fingerprint", cached.fingerprint);
        JsonArray bounds = new JsonArray();
        bounds.add(cached.bounds.minX());
        bounds.add(cached.bounds.maxX());
        bounds.add(cached.bounds.minY());
        bounds.add(cached.bounds.maxY());
        bounds.add(cached.bounds.minZ());
        bounds.add(cached.bounds.maxZ());
        json.add("bounds", bounds);

        JsonObject shapes = new JsonObject();
        for (Map.Entry<Long, List<AABB>> shapeEntry : cached.boxes.entrySet()) {
            JsonArray boxes = new JsonArray();
            for (AABB box : shapeEntry.getValue()) {
                JsonArray arr = new JsonArray();
                arr.add(box.minX);
                arr.add(box.minY);
                arr.add(box.minZ);
                arr.add(box.maxX);
                arr.add(box.maxY);
                arr.add(box.maxZ);
                boxes.add(arr);
            }
            shapes.add(Long.toString(shapeEntry.getKey()), boxes);
        }
        json.add("shapes", shapes);
        return json;
    }

    private static Path persistentCachePath() {
        return FMLPaths.GAMEDIR.get().resolve(ZcraftDecorationsMod.MODID).resolve("shape_cache_v1.json.gz");
    }

    private static String persistentShapeKey(String blockName, net.minecraft.core.Direction facing, ShapeMode mode) {
        return blockName + "|" + horizontalIndex(facing) + "|" + mode.name();
    }

    private static String modelFingerprint(String blockName, ShapeMode mode) {
        String key = blockName + "|" + mode.name() + "|voxel=" + getVoxelStep16() + "|snap=" + getProxySnap16();
        return MODEL_FINGERPRINT_CACHE.computeIfAbsent(key, ignored -> computeModelFingerprint(blockName, mode));
    }

    private static String computeModelFingerprint(String blockName, ShapeMode mode) {
        StringBuilder data = new StringBuilder(4096);
        data.append(CACHE_ALGORITHM_VERSION)
                .append("|mode=").append(mode.name())
                .append("|voxel=").append(getVoxelStep16())
                .append("|snap=").append(getProxySnap16());

        String blockPath = "assets/%s/models/block/%s.json".formatted(ZcraftDecorationsMod.MODID, blockName);
        appendModelChainFingerprint(data, new ResourceLocation(ZcraftDecorationsMod.MODID, "block/" + blockName), blockPath);
        return sha256(data.toString());
    }

    private static void appendModelChainFingerprint(StringBuilder data, ResourceLocation initial, String firstPath) {
        ResourceLocation current = initial;
        String path = firstPath;
        for (int i = 0; i < 16; i++) {
            String raw = readResourceString(path);
            data.append("|path=").append(path).append("|json=");
            if (raw == null) {
                data.append("<missing>");
                return;
            }
            data.append(raw);

            JsonObject json;
            try {
                json = JsonParser.parseString(raw).getAsJsonObject();
            } catch (Exception e) {
                return;
            }
            JsonArray elements = json.getAsJsonArray("elements");
            if (elements != null && !elements.isEmpty()) {
                return;
            }
            JsonElement parentEl = json.get("parent");
            if (parentEl == null || !parentEl.isJsonPrimitive()) {
                return;
            }
            String parent = parentEl.getAsString();
            try {
                current = parent.contains(":") ? new ResourceLocation(parent) : new ResourceLocation(current.getNamespace(), parent);
                path = "assets/%s/models/%s.json".formatted(current.getNamespace(), current.getPath());
            } catch (Exception e) {
                return;
            }
        }
    }

    private static String sha256(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(data.hashCode());
        }
    }

    private static ResourceLocation resolveBlockModel(String blockName) {
        return BLOCK_PARENT_MODEL_CACHE.computeIfAbsent(blockName, name -> {
            String blockModelPath = "assets/%s/models/block/%s.json".formatted(ZcraftDecorationsMod.MODID, name);
            JsonObject blockModel = readJson(blockModelPath);
            if (blockModel == null) {
                return null;
            }
            JsonArray elements = blockModel.getAsJsonArray("elements");
            if (elements != null && !elements.isEmpty()) {
                return new ResourceLocation(ZcraftDecorationsMod.MODID, "block/" + name);
            }
            JsonElement parentEl = blockModel.get("parent");
            if (parentEl == null || !parentEl.isJsonPrimitive()) {
                return null;
            }
            String parent = parentEl.getAsString();
            try {
                return parent.contains(":") ? new ResourceLocation(parent) : new ResourceLocation(ZcraftDecorationsMod.MODID, parent);
            } catch (Exception e) {
                return null;
            }
        });
    }

    private static ModelGeometry loadModelGeometry(ResourceLocation modelLocation) {
        JsonArray elements = resolveElementsFromModel(modelLocation, 16);
        if (elements == null || elements.isEmpty()) {
            return new ModelGeometry(new AABB(0, 0, 0, 16, 16, 16), null, 0);
        }

        int elementCount = elements.size();
        List<ModelElement> modelElements = elementCount > MAX_FULL_ELEMENTS ? null : new ArrayList<>(elementCount);
        AABB bounds = null;

        for (JsonElement el : elements) {
            if (!el.isJsonObject()) {
                continue;
            }
            ModelElement element = parseModelElement(el.getAsJsonObject());
            if (element == null) {
                continue;
            }
            bounds = bounds == null ? element.bounds16 : bounds.minmax(element.bounds16);
            if (modelElements != null) {
                modelElements.add(element);
            }
        }

        if (bounds == null) {
            bounds = new AABB(0, 0, 0, 16, 16, 16);
        }
        bounds = clampToReasonableRange(bounds);
        return new ModelGeometry(bounds, modelElements, elementCount);
    }

    private static JsonArray resolveElementsFromModel(ResourceLocation modelLocation, int maxDepth) {
        ResourceLocation current = modelLocation;
        for (int i = 0; i < maxDepth; i++) {
            String modelPath = "assets/%s/models/%s.json".formatted(current.getNamespace(), current.getPath());
            JsonObject json = readJson(modelPath);
            if (json == null) {
                return null;
            }
            JsonArray elements = json.getAsJsonArray("elements");
            if (elements != null && !elements.isEmpty()) {
                return elements;
            }
            JsonElement parentEl = json.get("parent");
            if (parentEl == null || !parentEl.isJsonPrimitive()) {
                return null;
            }
            String parent = parentEl.getAsString();
            try {
                current = parent.contains(":") ? new ResourceLocation(parent) : new ResourceLocation(current.getNamespace(), parent);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static AABB clampToReasonableRange(AABB box) {
        double minX = clamp(box.minX, -64.0, 80.0);
        double minY = clamp(box.minY, -64.0, 80.0);
        double minZ = clamp(box.minZ, -64.0, 80.0);
        double maxX = clamp(box.maxX, -64.0, 80.0);
        double maxY = clamp(box.maxY, -64.0, 80.0);
        double maxZ = clamp(box.maxZ, -64.0, 80.0);
        if (maxX <= minX || maxY <= minY || maxZ <= minZ) {
            return new AABB(0, 0, 0, 16, 16, 16);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long packOffset(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (long) (z & 0x1FFFFF);
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static void startWarmWatchdog(AtomicBoolean finished, long startNanos) {
        Thread watchdog = new Thread(() -> {
            while (!finished.get()) {
                try {
                    Thread.sleep(30000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (finished.get()) {
                    return;
                }
                LOGGER.warn("[ZCraft shape cache] still warming after {}ms; dumping shape-cache worker stacks", elapsedMillis(startNanos));
                for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                    Thread thread = entry.getKey();
                    if (!thread.getName().startsWith(ZcraftDecorationsMod.MODID + "-shape-cache")) {
                        continue;
                    }
                    StringBuilder stack = new StringBuilder();
                    stack.append(thread.getName()).append(" state=").append(thread.getState());
                    for (StackTraceElement element : entry.getValue()) {
                        stack.append("\n    at ").append(element);
                    }
                    LOGGER.warn("[ZCraft shape cache] worker stack:\n{}", stack);
                }
            }
        }, ZcraftDecorationsMod.MODID + "-shape-cache-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static double getProxySnap16() {
        double configured = ZcraftConfig.PROXY_SNAP_16.get();
        if (Double.isFinite(configured)) {
            return clamp(configured, 0.0, 16.0);
        }
        String raw = System.getProperty(PROXY_SNAP_16_PROPERTY, "").trim();
        if (raw.isEmpty()) {
            return DEFAULT_PROXY_SNAP_16;
        }
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value) || value < 0.0) {
                return DEFAULT_PROXY_SNAP_16;
            }
            return Math.min(value, 16.0);
        } catch (NumberFormatException e) {
            return DEFAULT_PROXY_SNAP_16;
        }
    }

    private static double getVoxelStep16() {
        double configured = ZcraftConfig.SHAPE_VOXEL_STEP_16.get();
        if (Double.isFinite(configured)) {
            return clamp(configured, 0.25, 4.0);
        }
        String raw = System.getProperty(VOXEL_STEP_16_PROPERTY, "").trim();
        if (raw.isEmpty()) {
            return DEFAULT_VOXEL_STEP_16;
        }
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value) || value <= 0.0) {
                return DEFAULT_VOXEL_STEP_16;
            }
            return clamp(value, 0.25, 4.0);
        } catch (NumberFormatException e) {
            return DEFAULT_VOXEL_STEP_16;
        }
    }

    private static int getShapeCacheThreads() {
        int fallback = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        int configured = ZcraftConfig.SHAPE_CACHE_THREADS.get();
        if (configured > 0) {
            return Math.min(configured, Math.max(1, Runtime.getRuntime().availableProcessors()));
        }
        if (configured == -1) {
            return fallback;
        }
        String raw = System.getProperty(CACHE_THREADS_PROPERTY, "").trim();
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value <= 0) {
                return fallback;
            }
            return Math.min(value, Math.max(1, Runtime.getRuntime().availableProcessors()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static AABB snapBounds16ToCellEdges(AABB box16, double snapThreshold16) {
        if (snapThreshold16 <= 0.0) {
            return box16;
        }
        return new AABB(
                snapMin16(box16.minX, snapThreshold16),
                snapMin16(box16.minY, snapThreshold16),
                snapMin16(box16.minZ, snapThreshold16),
                snapMax16(box16.maxX, snapThreshold16),
                snapMax16(box16.maxY, snapThreshold16),
                snapMax16(box16.maxZ, snapThreshold16));
    }

    private static double snapMin16(double min16, double snapThreshold16) {
        int cell = (int) Math.floor(min16 / 16.0);
        if (cell >= 0) {
            return min16;
        }
        double nextEdge = (cell + 1) * 16.0;
        return (nextEdge - min16) <= snapThreshold16 ? nextEdge : min16;
    }

    private static double snapMax16(double max16, double snapThreshold16) {
        int cell = (int) Math.floor(max16 / 16.0);
        if (cell <= 0) {
            return max16;
        }
        double prevEdge = cell * 16.0;
        return (max16 - prevEdge) <= snapThreshold16 ? prevEdge : max16;
    }

    private static AABB rotate90Y16(AABB box16, int quarterTurns) {
        int turns = ((quarterTurns % 4) + 4) % 4;
        AABB out = box16;
        for (int i = 0; i < turns; i++) {
            out = rotate90OnceY16(out);
        }
        return out;
    }

    private static AABB rotate90OnceY16(AABB box16) {
        double nMinX = 16.0 - box16.maxZ;
        double nMaxX = 16.0 - box16.minZ;
        double nMinZ = box16.minX;
        double nMaxZ = box16.maxX;
        return new AABB(nMinX, box16.minY, nMinZ, nMaxX, box16.maxY, nMaxZ);
    }

    private static ModelElement parseModelElement(JsonObject element) {
        double[] from = readVec3(element.getAsJsonArray("from"));
        double[] to = readVec3(element.getAsJsonArray("to"));
        if (from == null || to == null) {
            return null;
        }

        double minX = Math.min(from[0], to[0]);
        double minY = Math.min(from[1], to[1]);
        double minZ = Math.min(from[2], to[2]);
        double maxX = Math.max(from[0], to[0]);
        double maxY = Math.max(from[1], to[1]);
        double maxZ = Math.max(from[2], to[2]);

        if (maxX - minX < MIN_ELEMENT_THICKNESS_16) {
            double center = (minX + maxX) * 0.5;
            minX = center - (MIN_ELEMENT_THICKNESS_16 * 0.5);
            maxX = center + (MIN_ELEMENT_THICKNESS_16 * 0.5);
        }
        if (maxY - minY < MIN_ELEMENT_THICKNESS_16) {
            double center = (minY + maxY) * 0.5;
            minY = center - (MIN_ELEMENT_THICKNESS_16 * 0.5);
            maxY = center + (MIN_ELEMENT_THICKNESS_16 * 0.5);
        }
        if (maxZ - minZ < MIN_ELEMENT_THICKNESS_16) {
            double center = (minZ + maxZ) * 0.5;
            minZ = center - (MIN_ELEMENT_THICKNESS_16 * 0.5);
            maxZ = center + (MIN_ELEMENT_THICKNESS_16 * 0.5);
        }

        AABB box = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        JsonObject rotation = element.getAsJsonObject("rotation");
        if (rotation == null) {
            return new ModelElement(box, null, box, null);
        }

        JsonElement angleEl = rotation.get("angle");
        JsonElement axisEl = rotation.get("axis");
        JsonArray originEl = rotation.getAsJsonArray("origin");
        if (angleEl == null || axisEl == null || originEl == null) {
            return new ModelElement(box, null, box, null);
        }

        double angleRad = Math.toRadians(angleEl.getAsDouble());
        String axis = axisEl.getAsString();
        double[] origin = readVec3(originEl);
        if (origin == null) {
            return new ModelElement(box, null, box, null);
        }
        if (Math.abs(angleRad) <= EPS) {
            return new ModelElement(box, null, box, null);
        }

        double[][] corners = new double[][] {
                { minX, minY, minZ },
                { minX, minY, maxZ },
                { minX, maxY, minZ },
                { minX, maxY, maxZ },
                { maxX, minY, minZ },
                { maxX, minY, maxZ },
                { maxX, maxY, minZ },
                { maxX, maxY, maxZ }
        };

        double rMinX = Double.POSITIVE_INFINITY;
        double rMinY = Double.POSITIVE_INFINITY;
        double rMinZ = Double.POSITIVE_INFINITY;
        double rMaxX = Double.NEGATIVE_INFINITY;
        double rMaxY = Double.NEGATIVE_INFINITY;
        double rMaxZ = Double.NEGATIVE_INFINITY;

        for (double[] c : corners) {
            double[] p = rotatePoint(c[0], c[1], c[2], origin[0], origin[1], origin[2], axis, angleRad);
            rMinX = Math.min(rMinX, p[0]);
            rMinY = Math.min(rMinY, p[1]);
            rMinZ = Math.min(rMinZ, p[2]);
            rMaxX = Math.max(rMaxX, p[0]);
            rMaxY = Math.max(rMaxY, p[1]);
            rMaxZ = Math.max(rMaxZ, p[2]);
        }

        if (rMaxX <= rMinX || rMaxY <= rMinY || rMaxZ <= rMinZ) {
            return null;
        }
        ElementRotation elementRotation = new ElementRotation(axis, angleRad, origin[0], origin[1], origin[2]);
        AABB bounds = new AABB(rMinX, rMinY, rMinZ, rMaxX, rMaxY, rMaxZ);
        return new ModelElement(
                box,
                elementRotation,
                bounds,
                buildVoxelizedElementBoxes(box, elementRotation, bounds, getVoxelStep16()));
    }

    private static double[] rotatePoint(double x, double y, double z, double ox, double oy, double oz, String axis, double angleRad) {
        double dx = x - ox;
        double dy = y - oy;
        double dz = z - oz;

        double sin = Math.sin(angleRad);
        double cos = Math.cos(angleRad);

        double rx = dx;
        double ry = dy;
        double rz = dz;

        switch (axis) {
            case "x" -> {
                ry = dy * cos - dz * sin;
                rz = dy * sin + dz * cos;
            }
            case "y" -> {
                rx = dx * cos + dz * sin;
                rz = -dx * sin + dz * cos;
            }
            case "z" -> {
                rx = dx * cos - dy * sin;
                ry = dx * sin + dy * cos;
            }
            default -> {
            }
        }

        return new double[] { rx + ox, ry + oy, rz + oz };
    }

    private static double[] readVec3(JsonArray arr) {
        if (arr == null || arr.size() < 3) {
            return null;
        }
        return new double[] { arr.get(0).getAsDouble(), arr.get(1).getAsDouble(), arr.get(2).getAsDouble() };
    }

    private static int horizontalIndex(net.minecraft.core.Direction dir) {
        return switch (dir) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }
}
