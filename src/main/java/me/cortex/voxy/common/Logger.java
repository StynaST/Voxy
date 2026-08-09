package me.cortex.voxy.common;

import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import org.apache.logging.log4j.LogManager;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Logger {
    public static boolean INSERT_CLASS = true;
    public static boolean SHUTUP = false;
    public static boolean SHUTUP_INFO = false;
    // Port note: 1.12.2 predates Minecraft's move to slf4j, so this is log4j2 instead.
    private static final org.apache.logging.log4j.Logger LOGGER = LogManager.getLogger("Voxy");


    private static String callClsName() {
        String className = "";
        if (INSERT_CLASS) {
            var stackEntry = new Throwable().getStackTrace()[2];
            className = stackEntry.getClassName();
            var builder = new StringBuilder();
            var parts = className.split("\\.");
            for (int i = 0; i < parts.length; i++) {
                var part = parts[i];
                if (i < parts.length-1) {//-2
                    builder.append(part.charAt(0)).append(part.charAt(part.length()-1));
                } else {
                    builder.append(part);
                }
                if (i!=parts.length-1) {
                    builder.append(".");
                }
            }
            className = builder.toString();
        }
        return className;
    }

    public static void error(Object... args) {
        if (SHUTUP) {
            return;
        }
        Throwable throwable = null;
        for (var i : args) {
            if (i instanceof Throwable) {
                throwable = (Throwable) i;
            }
        }

        String error = (INSERT_CLASS?("["+callClsName()+"]: "):"") + Stream.of(args).map(Logger::objToString).collect(Collectors.joining(" "));
        LOGGER.error(error, throwable);
        if (VoxyCommon.IS_IN_MINECRAFT && !VoxyCommon.IS_DEDICATED_SERVER) {
            showInHUD(error);//This is done so that on dedicated server, the Minecraft client class isnt loaded
        }
    }

    public static void showInHUD(String msg) {
        var instance = Minecraft.getMinecraft();
        if (instance != null) {
            // addScheduledTask is 1.12.2's equivalent of executeIfPossible: it hops to the client
            // thread, and runs inline if we are already on it.
            instance.addScheduledTask(() -> {
                var player = Minecraft.getMinecraft().player;
                if (player != null) instance.ingameGUI.getChatGUI().printChatMessage(new TextComponentString(msg));
            });
        }
    }

    public static void warn(Object... args) {
        if (SHUTUP) {
            return;
        }
        Throwable throwable = null;
        for (var i : args) {
            if (i instanceof Throwable) {
                throwable = (Throwable) i;
            }
        }
        LOGGER.warn((INSERT_CLASS?("["+callClsName()+"]: "):"") + Stream.of(args).map(Logger::objToString).collect(Collectors.joining(" ")), throwable);
    }

    public static String info(Object... args) {
        if (SHUTUP||SHUTUP_INFO) {
            return "";
        }
        Throwable throwable = null;
        for (var i : args) {
            if (i instanceof Throwable) {
                throwable = (Throwable) i;
            }
        }
        var val = (INSERT_CLASS?("["+callClsName()+"]: "):"") + Stream.of(args).map(Logger::objToString).collect(Collectors.joining(" "));
        LOGGER.info(val, throwable);
        return val;
    }

    private static String objToString(Object obj) {
        if (obj == null) {
            return "NULL";
        }
        if (obj.getClass().isArray()) {
            return Arrays.deepToString((Object[]) obj);
        }
        return obj.toString();
    }
}
