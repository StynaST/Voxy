package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.Logger;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.KHRDebug;
import org.lwjgl.system.MemoryUtil;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Optional GL debug output, enabled with {@code -Dvoxy.glDebug=true}.
 *
 * <p>Port note: upstream instead wraps blaze3d's {@code GlDebug#printDebugLog} so that a GL error
 * raised from Voxy code gets a Java stack trace attached. 1.12.2 has no blaze3d and no debug
 * plumbing of its own, so Voxy installs its own callback rather than decorating someone else's.
 * The interesting half — "was this our fault, and was it just a shader compile probe" — is kept.
 */
public class GlDebugCallback {
    private static boolean installed = false;

    public static void installIfRequested() {
        if (installed || !System.getProperty("voxy.glDebug", "false").equalsIgnoreCase("true")) {
            return;
        }
        installed = true;

        var caps = GL.getCapabilities();
        if (!(caps.OpenGL43 || caps.GL_KHR_debug)) {
            Logger.warn("voxy.glDebug requested but neither GL 4.3 nor KHR_debug is available");
            return;
        }

        GL43C.glEnable(GL43C.GL_DEBUG_OUTPUT);
        GL43C.glEnable(GL43C.GL_DEBUG_OUTPUT_SYNCHRONOUS);
        KHRDebug.glDebugMessageCallback((source, type, id, severity, length, message, userParam)->{
            if (severity == KHRDebug.GL_DEBUG_SEVERITY_NOTIFICATION) {
                return;
            }
            String text = MemoryUtil.memUTF8(MemoryUtil.memByteBuffer(message, length));
            var trace = new Throwable(text);
            if (!isCausedByVoxy(trace.getStackTrace())) {
                Logger.warn("GL: " + text);
                return;
            }
            if (isCausedByShaderCompileTest(trace.getStackTrace())) {
                //Capabilities probes deliberately compile shaders that are expected to fail
                return;
            }
            Logger.error("GL (voxy): " + text + "\n" + getStackTraceAsString(trace));
        }, 0);
    }

    private static String getStackTraceAsString(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }

    private static boolean isCausedByVoxy(StackTraceElement[] trace) {
        for (var elem : trace) {
            if (elem.getClassName().startsWith("me.cortex.voxy")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCausedByShaderCompileTest(StackTraceElement[] trace) {
        for (var elem : trace) {
            if (elem.getClassName().equals(Capabilities.class.getName()) && elem.getMethodName().equals("testShaderCompilesOk")) {
                return true;
            }
        }
        return false;
    }
}
