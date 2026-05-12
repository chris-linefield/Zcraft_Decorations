package com.zcraft.decorations.model;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.zcraft.decorations.ZcraftDecorationsMod;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class ModelShapeCache {
    private static final Map<String, VoxelShape[]> SHAPES_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, VoxelShape> SPLIT_SHAPE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, SplitShapeSet> SPLIT_SHAPE_SET_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, IntBounds> INT_BOUNDS_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, ResourceLocation> BLOCK_PARENT_MODEL_CACHE = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, ModelGeometry> MODEL_GEOMETRY_CACHE = new ConcurrentHashMap<>();
    private static final String SHAPE_MODE_PROPERTY = "zcraft_decorations.shapeMode";
    private static final String PROXY_SNAP_16_PROPERTY = "zcraft_decorations.proxySnap16";
    private static final String VOXEL_STEP_16_PROPERTY = "zcraft_decorations.shapeVoxelStep16";
    // Build precise shapes from model elements by default. Use -Dzcraft_decorations.shapeMode=block to restore vanilla-sized collisions.
    private static final ShapeMode DEFAULT_SHAPE_MODE = ShapeMode.FULL;
    private static final int MAX_FULL_ELEMENTS = 4096;
    private static final double DEFAULT_PROXY_SNAP_16 = 4.0;
    private static final double DEFAULT_VOXEL_STEP_16 = 1.0;
    private static final double MIN_ELEMENT_THICKNESS_16 = 0.1;
    private static final double EPS = 1.0E-7;

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

    public static void warmAllBlockShapes(Iterable<String> blockNames) {
        ShapeMode mode = getShapeMode();
        for (String blockName : blockNames) {
            warmBlockShapes(blockName, mode);
        }
    }

    public static void warmBlockShapes(String blockName) {
        warmBlockShapes(blockName, getShapeMode());
    }

    public static void warmBlockShapes(String blockName, ShapeMode mode) {
        if (blockName == null || blockName.isBlank()) {
            return;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            getOrCreateLocalShapeSet(blockName, direction, mode);
        }
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
        String raw = System.getProperty(SHAPE_MODE_PROPERTY, "").trim().toLowerCase();
        return switch (raw) {
            case "full" -> ShapeMode.FULL;
            case "block", "none" -> ShapeMode.BLOCK;
            case "bounds" -> ShapeMode.BOUNDS;
            case "" -> DEFAULT_SHAPE_MODE;
            default -> DEFAULT_SHAPE_MODE;
        };
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

    private static SplitShapeSet buildLocalShapeSet(String blockName, net.minecraft.core.Direction facing, ShapeMode mode) {
        IntBounds bounds = getOrCreateIntBounds(blockName, facing, mode);
        Map<Long, VoxelShape> shapes = new ConcurrentHashMap<>();
        if (bounds.isEmpty()) {
            return new SplitShapeSet(bounds, shapes);
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
        return new SplitShapeSet(bounds, shapes);
    }

    private record SplitShapeSet(IntBounds bounds, Map<Long, VoxelShape> shapes) {
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

        VoxelShape out = Shapes.empty();
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
                    out = orLocalBox16(out, cellMinX, cellMinY, cellMinZ, vMinX, vMinY, vMinZ, vMaxX, vMaxY, vMaxZ);
                }
            } else {
                out = orLocalBox16(out, cellMinX, cellMinY, cellMinZ, iMinX, iMinY, iMinZ, iMaxX, iMaxY, iMaxZ);
            }
        }
        return out;
    }

    private static JsonObject readJson(String classpathPath) {
        try (InputStream in = ModelShapeCache.class.getClassLoader().getResourceAsStream(classpathPath)) {
            if (in == null) {
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
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

    private static VoxelShape orLocalBox16(
            VoxelShape out,
            double cellMinX,
            double cellMinY,
            double cellMinZ,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {
        return Shapes.or(out, Shapes.box(
                (minX - cellMinX) / 16.0,
                clamp((minY - cellMinY) / 16.0, 0.0, 1.0),
                (minZ - cellMinZ) / 16.0,
                (maxX - cellMinX) / 16.0,
                clamp((maxY - cellMinY) / 16.0, 0.0, 1.0),
                (maxZ - cellMinZ) / 16.0));
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

    private static double getProxySnap16() {
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
