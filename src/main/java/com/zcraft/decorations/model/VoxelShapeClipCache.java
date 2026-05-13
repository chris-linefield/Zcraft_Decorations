package com.zcraft.decorations.model;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class VoxelShapeClipCache {
    private static final int GRID_SIZE = 16;
    private static final int GRID_MASK = GRID_SIZE - 1;
    private static final int GRID_MIN_BOXES = 32;
    private static final double EPS = 1.0E-7;

    private final List<AABB> boxes;
    private final int[][] buckets;
    private final int[] seen;
    private final boolean useGrid;
    private int queryId;
    private int lastVisitedCells;
    private int lastBucketCandidates;
    private int lastTestedBoxes;

    public VoxelShapeClipCache(int shapeId, List<AABB> boxes) {
        this.boxes = boxes;
        this.seen = new int[boxes.size()];
        this.useGrid = boxes.size() >= GRID_MIN_BOXES;
        if (!this.useGrid) {
            this.buckets = new int[0][];
            return;
        }

        @SuppressWarnings("unchecked")
        List<Integer>[] building = new List[GRID_SIZE * GRID_SIZE * GRID_SIZE];
        for (int i = 0; i < boxes.size(); i++) {
            AABB box = boxes.get(i);
            int minX = cell(box.minX);
            int minY = cell(box.minY);
            int minZ = cell(box.minZ);
            int maxX = cellForMax(box.maxX);
            int maxY = cellForMax(box.maxY);
            int maxZ = cellForMax(box.maxZ);
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        int bucket = bucketIndex(x, y, z);
                        List<Integer> list = building[bucket];
                        if (list == null) {
                            list = new ArrayList<>(2);
                            building[bucket] = list;
                        }
                        list.add(i);
                    }
                }
            }
        }

        this.buckets = new int[building.length][];
        for (int i = 0; i < building.length; i++) {
            List<Integer> list = building[i];
            if (list == null || list.isEmpty()) {
                this.buckets[i] = new int[0];
                continue;
            }
            int[] packed = new int[list.size()];
            for (int j = 0; j < list.size(); j++) {
                packed[j] = list.get(j);
            }
            this.buckets[i] = packed;
        }
    }

    public boolean isEmpty() {
        return boxes.isEmpty();
    }

    public int boxCount() {
        return boxes.size();
    }

    public boolean useGrid() {
        return useGrid;
    }

    public int lastVisitedCells() {
        return lastVisitedCells;
    }

    public int lastBucketCandidates() {
        return lastBucketCandidates;
    }

    public int lastTestedBoxes() {
        return lastTestedBoxes;
    }

    @Nullable
    public BlockHitResult clip(
            Vec3 worldFrom,
            BlockPos pos,
            double fromX,
            double fromY,
            double fromZ,
            double toX,
            double toY,
            double toZ) {
        resetLastStats();
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        if (!useGrid) {
            lastTestedBoxes = boxes.size();
            return AABB.clip(boxes, worldFrom, worldFrom.add(dx, dy, dz), pos);
        }
        double[] range = unitIntersection(fromX, fromY, fromZ, dx, dy, dz);
        if (range == null) {
            return null;
        }

        double[] best = new double[] {1.0};
        Direction direction = null;
        int query = nextQueryId();

        double t = range[0];
        double end = range[1];
        int cellX = cell(fromX + dx * t);
        int cellY = cell(fromY + dy * t);
        int cellZ = cell(fromZ + dz * t);
        int stepX = dx > EPS ? 1 : dx < -EPS ? -1 : 0;
        int stepY = dy > EPS ? 1 : dy < -EPS ? -1 : 0;
        int stepZ = dz > EPS ? 1 : dz < -EPS ? -1 : 0;
        double nextX = stepX == 0 ? Double.POSITIVE_INFINITY : (nextBoundary(cellX, stepX) - fromX) / dx;
        double nextY = stepY == 0 ? Double.POSITIVE_INFINITY : (nextBoundary(cellY, stepY) - fromY) / dy;
        double nextZ = stepZ == 0 ? Double.POSITIVE_INFINITY : (nextBoundary(cellZ, stepZ) - fromZ) / dz;
        double stepTx = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / (dx * GRID_SIZE));
        double stepTy = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / (dy * GRID_SIZE));
        double stepTz = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / (dz * GRID_SIZE));

        for (int guard = 0; guard < 64 && t <= end + EPS && t < best[0]; guard++) {
            lastVisitedCells++;
            direction = testBucket(cellX, cellY, cellZ, query, fromX, fromY, fromZ, dx, dy, dz, best, direction);

            if (nextX <= nextY && nextX <= nextZ) {
                t = nextX;
                nextX += stepTx;
                cellX += stepX;
                if (cellX < 0 || cellX >= GRID_SIZE) {
                    break;
                }
            } else if (nextY <= nextZ) {
                t = nextY;
                nextY += stepTy;
                cellY += stepY;
                if (cellY < 0 || cellY >= GRID_SIZE) {
                    break;
                }
            } else {
                t = nextZ;
                nextZ += stepTz;
                cellZ += stepZ;
                if (cellZ < 0 || cellZ >= GRID_SIZE) {
                    break;
                }
            }
        }

        if (direction == null) {
            return null;
        }
        double hit = best[0];
        return new BlockHitResult(worldFrom.add(hit * dx, hit * dy, hit * dz), direction, pos, false);
    }

    public boolean contains(double x, double y, double z) {
        resetLastStats();
        if (!useGrid) {
            for (AABB box : boxes) {
                lastTestedBoxes++;
                if (contains(box, x, y, z)) {
                    return true;
                }
            }
            return false;
        }
        if (x < -EPS || x > 1.0 + EPS || y < -EPS || y > 1.0 + EPS || z < -EPS || z > 1.0 + EPS) {
            return false;
        }
        int[] candidates = buckets[bucketIndex(cell(x), cell(y), cell(z))];
        lastVisitedCells = 1;
        lastBucketCandidates = candidates.length;
        for (int candidate : candidates) {
            lastTestedBoxes++;
            if (contains(boxes.get(candidate), x, y, z)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private Direction testBucket(
            int cellX,
            int cellY,
            int cellZ,
            int query,
            double fromX,
            double fromY,
            double fromZ,
            double dx,
            double dy,
            double dz,
            double[] best,
            @Nullable Direction direction) {
        int[] candidates = buckets[bucketIndex(cellX, cellY, cellZ)];
        lastBucketCandidates += candidates.length;
        for (int candidate : candidates) {
            if (seen[candidate] == query) {
                continue;
            }
            seen[candidate] = query;
            lastTestedBoxes++;
            direction = getDirection(boxes.get(candidate), fromX, fromY, fromZ, dx, dy, dz, best, direction);
        }
        return direction;
    }

    private void resetLastStats() {
        lastVisitedCells = 0;
        lastBucketCandidates = 0;
        lastTestedBoxes = 0;
    }

    private int nextQueryId() {
        queryId++;
        if (queryId == 0) {
            for (int i = 0; i < seen.length; i++) {
                seen[i] = 0;
            }
            queryId = 1;
        }
        return queryId;
    }

    private static boolean contains(AABB box, double x, double y, double z) {
        return x >= box.minX - EPS && x <= box.maxX + EPS
                && y >= box.minY - EPS && y <= box.maxY + EPS
                && z >= box.minZ - EPS && z <= box.maxZ + EPS;
    }

    private static int bucketIndex(int x, int y, int z) {
        return (x * GRID_SIZE + y) * GRID_SIZE + z;
    }

    private static int cell(double value) {
        int cell = (int) Math.floor(value * GRID_SIZE);
        if (cell < 0) {
            return 0;
        }
        if (cell > GRID_MASK) {
            return GRID_MASK;
        }
        return cell;
    }

    private static int cellForMax(double value) {
        return cell(Math.nextAfter(value, Double.NEGATIVE_INFINITY));
    }

    private static double nextBoundary(int cell, int step) {
        return step > 0 ? (cell + 1) / (double) GRID_SIZE : cell / (double) GRID_SIZE;
    }

    @Nullable
    private static Direction getDirection(
            AABB box,
            double startX,
            double startY,
            double startZ,
            double deltaX,
            double deltaY,
            double deltaZ,
            double[] best,
            @Nullable Direction direction) {
        if (deltaX > EPS) {
            direction = clipPoint(best, direction, deltaX, deltaY, deltaZ,
                    box.minX, box.minY, box.maxY, box.minZ, box.maxZ, Direction.WEST, startX, startY, startZ);
        } else if (deltaX < -EPS) {
            direction = clipPoint(best, direction, deltaX, deltaY, deltaZ,
                    box.maxX, box.minY, box.maxY, box.minZ, box.maxZ, Direction.EAST, startX, startY, startZ);
        }

        if (deltaY > EPS) {
            direction = clipPoint(best, direction, deltaY, deltaZ, deltaX,
                    box.minY, box.minZ, box.maxZ, box.minX, box.maxX, Direction.DOWN, startY, startZ, startX);
        } else if (deltaY < -EPS) {
            direction = clipPoint(best, direction, deltaY, deltaZ, deltaX,
                    box.maxY, box.minZ, box.maxZ, box.minX, box.maxX, Direction.UP, startY, startZ, startX);
        }

        if (deltaZ > EPS) {
            direction = clipPoint(best, direction, deltaZ, deltaX, deltaY,
                    box.minZ, box.minX, box.maxX, box.minY, box.maxY, Direction.NORTH, startZ, startX, startY);
        } else if (deltaZ < -EPS) {
            direction = clipPoint(best, direction, deltaZ, deltaX, deltaY,
                    box.maxZ, box.minX, box.maxX, box.minY, box.maxY, Direction.SOUTH, startZ, startX, startY);
        }

        return direction;
    }

    @Nullable
    private static Direction clipPoint(
            double[] best,
            @Nullable Direction current,
            double deltaMain,
            double deltaA,
            double deltaB,
            double plane,
            double minA,
            double maxA,
            double minB,
            double maxB,
            Direction hitDirection,
            double startMain,
            double startA,
            double startB) {
        double t = (plane - startMain) / deltaMain;
        double a = startA + t * deltaA;
        double b = startB + t * deltaB;
        if (0.0 < t && t < best[0]
                && minA - EPS < a && a < maxA + EPS
                && minB - EPS < b && b < maxB + EPS) {
            best[0] = t;
            return hitDirection;
        }
        return current;
    }

    @Nullable
    private static double[] unitIntersection(double fromX, double fromY, double fromZ, double dx, double dy, double dz) {
        double min = 0.0;
        double max = 1.0;
        double[] result = clipAxis(fromX, dx, min, max, 0.0, 1.0);
        if (result == null) {
            return null;
        }
        min = result[0];
        max = result[1];
        result = clipAxis(fromY, dy, min, max, 0.0, 1.0);
        if (result == null) {
            return null;
        }
        min = result[0];
        max = result[1];
        return clipAxis(fromZ, dz, min, max, 0.0, 1.0);
    }

    @Nullable
    private static double[] clipAxis(double start, double delta, double minT, double maxT, double min, double max) {
        if (Math.abs(delta) < EPS) {
            return start >= min - EPS && start <= max + EPS ? new double[] {minT, maxT} : null;
        }
        double a = (min - start) / delta;
        double b = (max - start) / delta;
        if (a > b) {
            double tmp = a;
            a = b;
            b = tmp;
        }
        minT = Math.max(minT, a);
        maxT = Math.min(maxT, b);
        return maxT >= minT - EPS ? new double[] {minT, maxT} : null;
    }
}
