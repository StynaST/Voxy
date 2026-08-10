package me.cortex.voxy.client.ingest;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.ChunkPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkUpdateQueue {
    private static final long DEBOUNCE_NANOS = 250_000_000L;
    private static final int MAX_SIZE = 4096;
    private static final int MAX_ATTEMPTS = 200;
    private static final int MAX_PER_TICK = 32;

    private static final class Entry {
        long readyAtNanos;
        int attempts;

        Entry(long readyAtNanos) {
            this.readyAtNanos = readyAtNanos;
        }
    }

    private static final Map<Long, Entry> QUEUE = new ConcurrentHashMap<>();

    private ChunkUpdateQueue() {}

    public static void enqueue(int cx, int cz) {
        if (!VoxyConfig.CONFIG.ingestEnabled) return;

        long key = ChunkPos.asLong(cx, cz);
        long readyAt = System.nanoTime() + DEBOUNCE_NANOS;

        var existing = QUEUE.get(key);
        if (existing != null) {
            existing.readyAtNanos = readyAt;
            return;
        }
        if (QUEUE.size() >= MAX_SIZE) {
            evictFurthest();
        }
        QUEUE.put(key, new Entry(readyAt));
    }

    public static void clear() {
        QUEUE.clear();
    }

    public static int size() {
        return QUEUE.size();
    }

    public static void tick() {
        if (QUEUE.isEmpty()) return;

        var world = Minecraft.getMinecraft().world;
        if (world == null) {
            QUEUE.clear();
            return;
        }

        long now = System.nanoTime();
        int budget = MAX_PER_TICK;
        var iterator = QUEUE.entrySet().iterator();
        while (iterator.hasNext() && budget > 0) {
            var mapEntry = iterator.next();
            var entry = mapEntry.getValue();
            if (entry.readyAtNanos > now) continue;

            long key = mapEntry.getKey();
            var chunk = world.getChunkProvider().getLoadedChunk((int) key, (int) (key >> 32));
            if (chunk == null) {
                iterator.remove();
                continue;
            }

            budget--;
            if (VoxelIngestService.tryAutoIngestChunk(chunk)) {
                iterator.remove();
                continue;
            }

            //Ingest refuses a chunk with no light data yet, so retry it on a later tick
            if (++entry.attempts >= MAX_ATTEMPTS) {
                iterator.remove();
                continue;
            }
            entry.readyAtNanos = now + DEBOUNCE_NANOS;
        }
    }

    private static void evictFurthest() {
        var player = Minecraft.getMinecraft().player;
        int px = player == null ? 0 : player.chunkCoordX;
        int pz = player == null ? 0 : player.chunkCoordZ;

        long furthestKey = 0;
        long furthestDist = -1;
        for (long key : QUEUE.keySet()) {
            long dx = (int) key - px;
            long dz = (int) (key >> 32) - pz;
            long dist = dx * dx + dz * dz;
            if (dist > furthestDist) {
                furthestDist = dist;
                furthestKey = key;
            }
        }
        if (furthestDist >= 0) {
            QUEUE.remove(furthestKey);
        }
    }
}
