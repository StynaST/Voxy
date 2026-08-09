package me.cortex.voxy.common.util.cpu;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.ThreadUtils;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.Kernel32;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

//Represents the layout of the current cpu running on
public class CpuLayout {
    private CpuLayout(){}

    public static void setThreadAffinity(Core... cores) {
        var affinity = new Affinity[cores.length];
        for (int i = 0; i < cores.length; i++) {
            affinity[i] = cores[i].affinity;
        }
        setThreadAffinity(affinity);
    }

    public static void setThreadAffinity(Affinity... affinities) {
        var platform = Platform.get();
        if (platform == Platform.WINDOWS) {
            long[] msks = new long[affinities.length];
            short[] groups = new short[affinities.length];Arrays.fill(groups, (short) -1);
            int i = 0;
            for (var a : affinities) {
                int idx;
                for (idx = 0; idx<i && groups[idx]!=a.group; idx++);
                if (idx == i) {groups[idx] = a.group; i++;}
                msks[idx] |= a.msk;
            }
            ThreadUtils.SetThreadSelectedCpuSetMasksWin32(Arrays.copyOf(msks, i), Arrays.copyOf(groups, i));
        } else if (platform == Platform.LINUX) {
            Arrays.sort(affinities, (a, b) -> a.group - b.group);
            long[] msks = new long[affinities.length];
            for (int i=0; i<affinities.length; i++) {
                msks[i] = affinities[i].msk;
            }
            ThreadUtils.schedSetaffinityLinux(msks);
        } else {
            Logger.error("Don't know how to set thread affinity on this platform.");
        }
    }

    /**
     * Port note: upstream enumerates cores through oshi, and on Windows through JNA's
     * {@code Kernel32Util.getLogicalProcessorInformationEx}. Neither is usable here. 1.12.2 Forge
     * bundles oshi 1.1 and JNA 4.4, both far older than the APIs upstream calls, and both live in
     * the same packages as any newer copy we might ship, so shading a modern one would collide.
     *
     * <p>Instead the topology is read straight from sysfs, which needs no dependency at all and is
     * the same data oshi would have parsed. Everything here mirrors what the oshi path produced:
     * one {@link Core} per physical core, its affinity mask covering that core's logical siblings,
     * and the efficiency flag set only on a hybrid machine.
     */
    private static Core[] generateCoreLayoutLinux() throws IOException {
        Path cpuRoot = Paths.get("/sys/devices/system/cpu");
        // Group logical cpus by the physical core they belong to. thread_siblings_list names every
        // logical cpu sharing a core, so the first entry works as a stable core key.
        Int2ObjectOpenHashMap<Affinity> affinityMsk = new Int2ObjectOpenHashMap<>();
        Int2IntOpenHashMap coreCapacity = new Int2IntOpenHashMap();
        coreCapacity.defaultReturnValue(-1);
        List<Integer> coreOrder = new ArrayList<>();

        for (int cpu = 0; cpu < 1024; cpu++) {
            Path cpuDir = cpuRoot.resolve("cpu" + cpu);
            if (!Files.isDirectory(cpuDir)) {
                if (cpu > Runtime.getRuntime().availableProcessors()) break;
                continue;
            }
            Path siblings = cpuDir.resolve("topology/thread_siblings_list");
            if (!Files.exists(siblings)) {
                continue;
            }
            int coreKey = firstIntIn(Files.readString(siblings).trim());
            if (coreKey < 0) {
                continue;
            }
            var existing = affinityMsk.get(coreKey);
            if (existing == null) {
                coreOrder.add(coreKey);
                existing = new Affinity(0, (short) 0);
            }
            // Linux has no processor-group concept; everything is group 0, and sched_setaffinity
            // takes a flat mask, which is what ThreadUtils#schedSetaffinityLinux expects.
            affinityMsk.put(coreKey, new Affinity(existing.msk() | (1L << cpu), (short) 0));

            Path capacityPath = cpuDir.resolve("cpu_capacity");
            if (Files.exists(capacityPath)) {
                try {
                    coreCapacity.put(coreKey, Integer.parseInt(Files.readString(capacityPath).trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (coreOrder.isEmpty()) {
            throw new IOException("No cpu topology found under " + cpuRoot);
        }

        // cpu_capacity is only meaningful on a hybrid part; when every core reports the same value
        // (or none report at all) there are no efficiency cores to mark, matching upstream's
        // allSameEfficiency check.
        int maxCapacity = 0;
        boolean anyCapacity = !coreCapacity.isEmpty();
        for (var key : coreOrder) {
            int capacity = coreCapacity.get((int) key);
            if (capacity >= 0) {
                maxCapacity = Math.max(maxCapacity, capacity);
            }
        }
        boolean hybrid = false;
        if (anyCapacity) {
            for (var key : coreOrder) {
                int capacity = coreCapacity.get((int) key);
                if (capacity >= 0 && capacity < maxCapacity) {
                    hybrid = true;
                    break;
                }
            }
        }

        var cores = new Core[coreOrder.size()];
        int i = 0;
        for (var key : coreOrder) {
            int capacity = coreCapacity.get((int) key);
            boolean isEfficiency = hybrid && capacity >= 0 && capacity < maxCapacity;
            cores[i++] = new Core(isEfficiency, affinityMsk.get((int) key));
        }
        sort(cores);
        return cores;
    }


    /**
     * Windows core enumeration, mirroring the Linux path above.
     *
     * <p>Port note: upstream reaches {@code GetLogicalProcessorInformationEx} through JNA's
     * {@code Kernel32Util}. Forge 1.12.2 bundles JNA 4.4, which does not expose it, and a newer JNA
     * cannot be shaded alongside (same packages). So the call goes through LWJGL's {@code JNI} the
     * same way {@link ThreadUtils} already resolves {@code SetThreadSelectedCpuSetMasks}.
     *
     * <p>Struct layout (x64): {@code SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX} is
     * {@code DWORD Relationship; DWORD Size;} then, for a processor relationship,
     * {@code BYTE Flags; BYTE EfficiencyClass; BYTE Reserved[20]; WORD GroupCount;} followed by
     * {@code GROUP_AFFINITY[]} of {@code ULONG_PTR Mask; WORD Group; WORD Reserved[3];}.
     */
    private static Core[] generateCoreLayoutWindows() {
        final int RelationProcessorCore = 0;
        long fn = Kernel32.getLibrary().getFunctionAddress("GetLogicalProcessorInformationEx");
        if (fn == 0) {
            return null;
        }

        try (var stack = MemoryStack.stackPush()) {
            var lengthBuffer = stack.callocInt(1);
            //First call sizes the buffer; it is expected to fail with ERROR_INSUFFICIENT_BUFFER
            JNI.invokePPI(RelationProcessorCore, 0L, MemoryUtil.memAddress(lengthBuffer), fn);
            int size = lengthBuffer.get(0);
            if (size <= 0) {
                return null;
            }

            var buffer = MemoryUtil.memAlloc(size);
            try {
                lengthBuffer.put(0, size);
                if (JNI.invokePPI(RelationProcessorCore, MemoryUtil.memAddress(buffer), MemoryUtil.memAddress(lengthBuffer), fn) == 0) {
                    return null;
                }

                var cores = new ArrayList<Core>();
                var efficiencyClasses = new IntArrayList();
                long ptr = MemoryUtil.memAddress(buffer);
                long end = ptr + size;
                while (ptr + 8 <= end) {
                    int relationship = MemoryUtil.memGetInt(ptr);
                    int entrySize = MemoryUtil.memGetInt(ptr + 4);
                    if (entrySize <= 0 || ptr + entrySize > end) {
                        break;
                    }
                    if (relationship == RelationProcessorCore) {
                        int efficiencyClass = Byte.toUnsignedInt(MemoryUtil.memGetByte(ptr + 9));
                        int groupCount = Short.toUnsignedInt(MemoryUtil.memGetShort(ptr + 30));
                        for (int i = 0; i < groupCount; i++) {
                            long groupAffinity = ptr + 32 + i * 16L;
                            long mask = MemoryUtil.memGetAddress(groupAffinity);
                            short group = MemoryUtil.memGetShort(groupAffinity + 8);
                            cores.add(new Core(false, new Affinity(mask, group)));
                            efficiencyClasses.add(efficiencyClass);
                        }
                    }
                    ptr += entrySize;
                }

                if (cores.isEmpty()) {
                    return null;
                }

                //Windows counts up: the highest efficiency class is the most performant. Only mark
                //efficiency cores when the classes actually differ, matching the Linux path.
                int maxClass = 0;
                for (int i = 0; i < efficiencyClasses.size(); i++) {
                    maxClass = Math.max(maxClass, efficiencyClasses.getInt(i));
                }
                var out = new Core[cores.size()];
                for (int i = 0; i < out.length; i++) {
                    boolean isEfficiency = efficiencyClasses.getInt(i) < maxClass;
                    out[i] = new Core(isEfficiency, cores.get(i).affinity());
                }
                sort(out);
                return out;
            } finally {
                MemoryUtil.memFree(buffer);
            }
        }
    }

    /** Parses the leading integer of a sysfs list such as "0,8" or "0-1". */
    private static int firstIntIn(String value) {
        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(value.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }


    private static void sort(Core[] cores) {
        Arrays.sort(cores, (a,b)->{
            if (a.isEfficiency == b.isEfficiency) {
                int c = Short.compareUnsigned(a.affinity.group, b.affinity.group);
                if (c==0) {
                    return Long.compareUnsigned(a.affinity.msk, b.affinity.msk);
                }
                return c;
            } else {
                return a.isEfficiency?1:-1;
            }
        });
    }

    public record Affinity(long msk, short group) {}
    public record Core(boolean isEfficiency, Affinity affinity) {

    }

    public static final Core[] CORES;
    static {
        Core[] cores = null;
        try {
            if (Platform.get() == Platform.LINUX) {
                cores = generateCoreLayoutLinux();
            } else if (Platform.get() == Platform.WINDOWS) {
                cores = generateCoreLayoutWindows();
            }
            // Any other platform (or a failed enumeration) leaves CORES null, which makes
            // getCoreCount fall back to availableProcessors and skips affinity pinning entirely.
        } catch (Exception e) {
            Logger.error("Failed to generate cpu core layout, falling back to null: ", e);
        }
        CORES = cores;
    }

    public static void main(String[] args) throws InterruptedException {
        System.err.println(Arrays.toString(CORES));
        setThreadAffinity(CORES[0], CORES[1]);
        for (int i = 0; i < 20; i++) {
            int finalI = i;
            new Thread(()->{
                setThreadAffinity(CORES[finalI&3]);
                Random r = new Random();
                int j= 0;
                while (r.nextLong()!=0) {
                    j++;
                }
                System.out.println(j);
            }).start();
        }
        while (true) {
            Thread.sleep(100);
        }
    }

    public static int getCoreCount() {
        if (CORES==null) {
            return Runtime.getRuntime().availableProcessors();
        } else {
            return CORES.length;
        }
    }
}
