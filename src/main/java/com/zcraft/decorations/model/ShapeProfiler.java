package com.zcraft.decorations.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.mojang.logging.LogUtils;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

public final class ShapeProfiler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ENABLED_PROPERTY = "zcraft_decorations.shapeProfiler";
    private static final String REPORT_INTERVAL_MS_PROPERTY = "zcraft_decorations.shapeProfilerIntervalMs";
    private static final long DEFAULT_REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"));
    private static final long REPORT_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(
            Long.getLong(REPORT_INTERVAL_MS_PROPERTY, TimeUnit.NANOSECONDS.toMillis(DEFAULT_REPORT_INTERVAL_NANOS)));

    private static final Map<Integer, String> LABELS = new ConcurrentHashMap<>();
    private static final Map<Integer, Stats> STATS = new ConcurrentHashMap<>();
    private static volatile long lastReportNanos = System.nanoTime();

    private ShapeProfiler() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void registerShape(VoxelShape shape, String label) {
        if (!ENABLED || shape == null || shape.isEmpty()) {
            return;
        }
        LABELS.putIfAbsent(System.identityHashCode(shape), label);
    }

    public static void recordClipBuild(int shapeId, int boxCount, boolean useGrid, long nanos) {
        if (!ENABLED) {
            return;
        }
        Stats stats = stats(shapeId);
        synchronized (stats) {
            stats.boxCount = Math.max(stats.boxCount, boxCount);
            stats.useGrid = stats.useGrid || useGrid;
            stats.builds++;
            stats.buildNanos += nanos;
            stats.maxBuildNanos = Math.max(stats.maxBuildNanos, nanos);
        }
        maybeReport();
    }

    public static void recordClip(
            int shapeId,
            int boxCount,
            boolean useGrid,
            long nanos,
            int visitedCells,
            int bucketCandidates,
            int testedBoxes,
            boolean hit) {
        if (!ENABLED) {
            return;
        }
        Stats stats = stats(shapeId);
        synchronized (stats) {
            stats.boxCount = Math.max(stats.boxCount, boxCount);
            stats.useGrid = stats.useGrid || useGrid;
            stats.calls++;
            stats.totalNanos += nanos;
            stats.maxNanos = Math.max(stats.maxNanos, nanos);
            stats.visitedCells += visitedCells;
            stats.bucketCandidates += bucketCandidates;
            stats.testedBoxes += testedBoxes;
            if (hit) {
                stats.hits++;
            }
        }
        maybeReport();
    }

    public static void recordShapeGet(String label, long nanos) {
        if (!ENABLED) {
            return;
        }
        if (nanos >= TimeUnit.MILLISECONDS.toNanos(1)) {
            LOGGER.info("[ZCraft shape profiler] slow getShape: label={}, time={}us",
                    label, TimeUnit.NANOSECONDS.toMicros(nanos));
        }
    }

    public static void recordEdges(int shapeId, long nanos) {
        if (!ENABLED) {
            return;
        }
        Stats stats = stats(shapeId);
        synchronized (stats) {
            stats.edgeCalls++;
            stats.edgeNanos += nanos;
            stats.maxEdgeNanos = Math.max(stats.maxEdgeNanos, nanos);
        }
        maybeReport();
    }

    private static Stats stats(int shapeId) {
        return STATS.computeIfAbsent(shapeId, ignored -> new Stats());
    }

    private static void maybeReport() {
        long now = System.nanoTime();
        if (now - lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        synchronized (ShapeProfiler.class) {
            now = System.nanoTime();
            if (now - lastReportNanos < REPORT_INTERVAL_NANOS) {
                return;
            }
            lastReportNanos = now;
            report();
        }
    }

    private static void report() {
        for (Map.Entry<Integer, Stats> entry : STATS.entrySet()) {
            Stats snapshot;
            Stats stats = entry.getValue();
            synchronized (stats) {
                if (stats.calls == 0 && stats.builds == 0) {
                    continue;
                }
                snapshot = stats.copyAndReset();
            }
            String label = LABELS.getOrDefault(entry.getKey(), "shape@" + Integer.toHexString(entry.getKey()));
            long calls = Math.max(1L, snapshot.calls);
            long edgeCalls = Math.max(1L, snapshot.edgeCalls);
            LOGGER.info("[ZCraft shape profiler] label={}, boxes={}, grid={}, clipCalls={}, avgClip={}us, maxClip={}us, hits={}, avgCells={}, avgCandidates={}, avgTested={}, edgeCalls={}, avgEdges={}us, maxEdges={}us, builds={}, avgBuild={}us, maxBuild={}us",
                    label,
                    snapshot.boxCount,
                    snapshot.useGrid,
                    snapshot.calls,
                    TimeUnit.NANOSECONDS.toMicros(snapshot.totalNanos / calls),
                    TimeUnit.NANOSECONDS.toMicros(snapshot.maxNanos),
                    snapshot.hits,
                    snapshot.visitedCells / calls,
                    snapshot.bucketCandidates / calls,
                    snapshot.testedBoxes / calls,
                    snapshot.edgeCalls,
                    TimeUnit.NANOSECONDS.toMicros(snapshot.edgeNanos / edgeCalls),
                    TimeUnit.NANOSECONDS.toMicros(snapshot.maxEdgeNanos),
                    snapshot.builds,
                    snapshot.builds == 0 ? 0 : TimeUnit.NANOSECONDS.toMicros(snapshot.buildNanos / snapshot.builds),
                    TimeUnit.NANOSECONDS.toMicros(snapshot.maxBuildNanos));
        }
    }

    private static final class Stats {
        private int boxCount;
        private boolean useGrid;
        private long calls;
        private long hits;
        private long totalNanos;
        private long maxNanos;
        private long visitedCells;
        private long bucketCandidates;
        private long testedBoxes;
        private long edgeCalls;
        private long edgeNanos;
        private long maxEdgeNanos;
        private long builds;
        private long buildNanos;
        private long maxBuildNanos;

        private Stats copyAndReset() {
            Stats copy = new Stats();
            copy.boxCount = boxCount;
            copy.useGrid = useGrid;
            copy.calls = calls;
            copy.hits = hits;
            copy.totalNanos = totalNanos;
            copy.maxNanos = maxNanos;
            copy.visitedCells = visitedCells;
            copy.bucketCandidates = bucketCandidates;
            copy.testedBoxes = testedBoxes;
            copy.edgeCalls = edgeCalls;
            copy.edgeNanos = edgeNanos;
            copy.maxEdgeNanos = maxEdgeNanos;
            copy.builds = builds;
            copy.buildNanos = buildNanos;
            copy.maxBuildNanos = maxBuildNanos;
            calls = 0;
            hits = 0;
            totalNanos = 0;
            maxNanos = 0;
            visitedCells = 0;
            bucketCandidates = 0;
            testedBoxes = 0;
            edgeCalls = 0;
            edgeNanos = 0;
            maxEdgeNanos = 0;
            builds = 0;
            buildNanos = 0;
            maxBuildNanos = 0;
            return copy;
        }
    }
}
