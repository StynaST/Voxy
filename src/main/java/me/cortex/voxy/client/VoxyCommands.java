package me.cortex.voxy.client;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.common.DebugUtils;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.DHImporter;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Port note: upstream builds a Fabric client-side brigadier tree. 1.12.2 has no brigadier at all, so
 * this is a Forge {@link CommandBase} registered with {@code ClientCommandHandler}, which runs it
 * purely client side and never contacts the server.
 *
 * <p>The world/bobby/raw/zip/current import subcommands are gone: every one of them is a front end
 * for {@code WorldImporter}, which has not been ported. Distant Horizons / Vintage Horizons import
 * is the whole import surface for now.
 */
public class VoxyCommands extends CommandBase {
    private static final String NOT_ENABLED = "Voxy must be enabled in settings to use this";

    @Override
    public String getName() {
        return "voxy";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/voxy <reload|import|debug>";
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;//Client side command, always available
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        if (args.length == 0) {
            error(sender, getUsage(sender));
            return;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> reloadInstance(sender);
            case "import" -> runImport(sender, args);
            case "debug" -> runDebug(sender, args);
            default -> error(sender, getUsage(sender));
        }
    }

    private static void runImport(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            error(sender, "/voxy import <distant_horizons|cancel>");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "distant_horizons" -> {
                if (!DHImporter.HasRequiredLibraries) {
                    error(sender, "sqlite/lzma libraries are unavailable, cannot import");
                    return;
                }
                if (args.length < 3) {
                    error(sender, "/voxy import distant_horizons <sqlDbPath>");
                    return;
                }
                importDistantHorizons(sender, String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
            }
            case "cancel" -> cancelImport(sender);
            default -> error(sender, "/voxy import <distant_horizons|cancel>");
        }
    }

    private static void runDebug(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            error(sender, "/voxy debug <verifyTLNChildMask|gpuTimings>");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "verifytlnchildmask" -> verifyTLNs(sender, args.length > 2 && Boolean.parseBoolean(args[2]));
            case "gputimings" -> {
                if (args.length < 3) {
                    error(sender, "/voxy debug gpuTimings <true|false>");
                    return;
                }
                boolean enable = Boolean.parseBoolean(args[2]);
                DebugEntries.setGpuDebugEnabled(enable);
                info(sender, "Voxy gpu timings " + (enable ? "enabled" : "disabled"));
            }
            default -> error(sender, "/voxy debug <verifyTLNChildMask|gpuTimings>");
        }
    }

    private static void reloadInstance(ICommandSender sender) {
        if (getClientInstance(sender) == null) {
            return;
        }

        var vrsh = IVoxyRenderSystemHolder.getNullableHolder();
        if (vrsh != null) {
            vrsh.voxy$shutdownRenderer();
        }

        VoxyCommon.shutdownInstance();
        System.gc();
        VoxyCommon.createInstance();

        var renderer = Minecraft.getMinecraft().renderGlobal;
        if (renderer != null) renderer.loadRenderers();
    }

    private static void verifyTLNs(ICommandSender sender, boolean attemptRepair) {
        if (VoxyCommon.getInstance() == null) {
            error(sender, NOT_ENABLED);
            return;
        }
        if (Minecraft.getMinecraft().world == null) {
            throw new IllegalStateException("How you even do this");
        }
        var engine = WorldIdentifier.ofEngine(Minecraft.getMinecraft().world);
        if (engine != null) {
            DebugUtils.verifyAllTopLevelNodes(engine, attemptRepair);
        }
    }

    private static void importDistantHorizons(ICommandSender sender, String path) {
        var instance = getClientInstance(sender);
        if (instance == null) {
            return;
        }
        var dbFile = new File(stripQuotes(path));
        if (!dbFile.exists()) {
            error(sender, "No such file: " + dbFile);
            return;
        }
        if (dbFile.isDirectory()) {
            //Upstream only ever looks for DistantHorizons.sqlite. Vintage Horizons is a backport and
            //may not use that exact name, so any single .sqlite in the directory is accepted too.
            var named = dbFile.toPath().resolve("DistantHorizons.sqlite").toFile();
            if (named.exists()) {
                dbFile = named;
            } else {
                var candidates = dbFile.listFiles(f->f.isFile() && f.getName().toLowerCase().endsWith(".sqlite"));
                if (candidates == null || candidates.length == 0) {
                    error(sender, "No .sqlite database in that directory");
                    return;
                }
                if (candidates.length > 1) {
                    error(sender, "Multiple .sqlite databases in that directory, name the one you want");
                    return;
                }
                dbFile = candidates[0];
                info(sender, "Using " + dbFile.getName());
            }
        }

        File dbFile_ = dbFile;
        var engine = WorldIdentifier.ofEngine(Minecraft.getMinecraft().world);
        if (engine == null) {
            error(sender, "No voxy world engine for the current dimension");
            return;
        }
        if (!instance.getImportManager().makeAndRunIfNone(engine, ()->
                new DHImporter(dbFile_, engine, Minecraft.getMinecraft().world, instance.getServiceManager(), instance.savingServiceRateLimiter))) {
            error(sender, "An import is already running for this world, cancel it first");
        }
    }

    private static void cancelImport(ICommandSender sender) {
        var instance = getClientInstance(sender);
        if (instance == null) {
            return;
        }
        var world = WorldIdentifier.ofEngineNullable(Minecraft.getMinecraft().world);
        if (world == null || !instance.getImportManager().cancelImport(world)) {
            error(sender, "No import is running for this world");
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "reload", "import", "debug");
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("import")) {
                return DHImporter.HasRequiredLibraries
                        ? getListOfStringsMatchingLastWord(args, "distant_horizons", "cancel")
                        : getListOfStringsMatchingLastWord(args, "cancel");
            }
            if (args[0].equalsIgnoreCase("debug")) {
                return getListOfStringsMatchingLastWord(args, "verifyTLNChildMask", "gpuTimings");
            }
        }
        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("debug")) {
                return getListOfStringsMatchingLastWord(args, "true", "false");
            }
            if (args[0].equalsIgnoreCase("import") && args[1].equalsIgnoreCase("distant_horizons")) {
                return completePath(args[2]);
            }
        }
        return Collections.emptyList();
    }

    //Completes directories and .sqlite files, so the db can be tabbed to the same way the fabric
    // suggestion provider does it upstream
    private static List<String> completePath(String partial) {
        partial = stripQuotes(partial).replace('\\', '/');
        int split = partial.lastIndexOf('/');
        String dirPart = split == -1 ? "" : partial.substring(0, split + 1);
        String namePart = split == -1 ? partial : partial.substring(split + 1);

        File dir = dirPart.isEmpty() ? Minecraft.getMinecraft().gameDir : new File(dirPart);
        var children = dir.listFiles();
        if (children == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (var child : children) {
            if (!child.getName().toLowerCase().startsWith(namePart.toLowerCase())) {
                continue;
            }
            if (child.isDirectory()) {
                out.add(dirPart + child.getName() + "/");
            } else if (child.getName().endsWith(".sqlite")) {
                out.add(dirPart + child.getName());
            }
        }
        return out;
    }

    private static String stripQuotes(String str) {
        if (str.startsWith("\"")) {
            str = str.substring(1);
        }
        if (str.endsWith("\"")) {
            str = str.substring(0, str.length() - 1);
        }
        return str;
    }

    private static @Nullable VoxyClientInstance getClientInstance(ICommandSender sender) {
        var instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            error(sender, NOT_ENABLED);
        }
        return instance;
    }

    private static void error(ICommandSender sender, String msg) {
        sender.sendMessage(new TextComponentString(TextFormatting.RED + msg));
    }

    private static void info(ICommandSender sender, String msg) {
        sender.sendMessage(new TextComponentString(msg));
    }
}
