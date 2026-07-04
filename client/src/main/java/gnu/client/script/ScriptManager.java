package gnu.client.script;

import gnu.client.common.GnuLog;
import gnu.client.config.ConfigManager;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.runtime.NativeBootstrap;
import gnu.client.runtime.mc.McAccess;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Runtime Java script compiler + loader for user-authored scripts.
 *
 * <p>Reads bare-body {@code .java} files from {@code ~/.config/gnuclient/scripts/},
 * wraps each in a generated {@link Module} subclass (per the feasibility report's
 * template), compiles with {@link ToolProvider#getSystemJavaCompiler()} (GraalVM 8
 * JDK — confirmed available), loads via a fresh {@link URLClassLoader} per script,
 * registers the resulting {@link Module} with {@link ModuleManager}, and calls the
 * user's {@code onLoad()} hook. After all scripts are registered, re-runs
 * {@link ConfigManager#load()} so previously-saved script settings are applied.
 *
 * <p>Three deviations from the original spec, all forced by the "extends Module
 * directly" design and flagged for confirmation:
 * <ol>
 *   <li><b>onDisable collision</b>: the generated class must override Module's
 *       abstract {@code onDisable}. The user cannot also declare {@code onDisable}
 *       (duplicate method). The user-facing cleanup hook is therefore
 *       {@code onScriptDisable}, invoked reflectively from the generated
 *       {@code onDisable} override.</li>
 *   <li><b>onLoad timing</b>: {@code onLoad} is called by this manager at
 *       registration (not by the generated {@code onEnable}) so that settings
 *       register before the post-load {@link ConfigManager#load()} pass. The
 *       generated {@code onEnable} is a no-op.</li>
 *   <li><b>ClassLoader parent</b>: {@code ScriptManager.class.getClassLoader()}
 *       (not {@link McAccess#gameLoader()}) to guarantee the script's
 *       {@code Module} superclass is the same {@code Class} object that
 *       {@link ModuleManager} uses — otherwise {@code (Module) instance} throws
 *       {@code ClassCastException} when the two LaunchClassLoader instances differ
 *       (see McAccess.java header comment on the observed dual-CL case).</li>
 * </ol>
 *
 * <p>Manual refresh trigger: {@link #reloadAll()} is the entry point. No chat
 * command / debug keybind / native GUI button exists in this codebase today, so
 * the only call site is the initial load from {@code NativeBootstrap.init()}.
 * A manual trigger mechanism needs a separate design decision — flagged.
 */
public final class ScriptManager {

    private static final ScriptManager INSTANCE = new ScriptManager();

    private static final String SCRIPTS_DIR;
    private static final String COMPILED_DIR;
    private static final String SCRIPT_PACKAGE = "gnu.client.script.generated";

    static {
        String home = System.getProperty("user.home");
        String base = (home != null)
                ? home + "/.config/gnuclient"
                : "/tmp/gnuclient";
        SCRIPTS_DIR = base + "/scripts";
        COMPILED_DIR = base + "/scripts_compiled";
    }

    public static ScriptManager instance() {
        return INSTANCE;
    }

    private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    private final Map<String, LoadedScript> loaded = new LinkedHashMap<>();
    private final Random random = new Random();

    private static final class LoadedScript {
        final String scriptName;
        final Module module;
        final URLClassLoader loader;
        final int startingLine;

        LoadedScript(String scriptName, Module module, URLClassLoader loader, int startingLine) {
            this.scriptName = scriptName;
            this.module = module;
            this.loader = loader;
            this.startingLine = startingLine;
        }
    }

    /**
     * Synchronous full reload: unregister + close all currently loaded scripts,
     * then compile + load every {@code .java} file in the scripts directory, then
     * re-run {@link ConfigManager#load()} to apply saved script settings.
     */
    public void reloadAll() {
        // 1. Tear down currently loaded scripts.
        for (LoadedScript ls : loaded.values()) {
            try {
                ModuleManager.INSTANCE.unregister(ls.scriptName);
            } catch (Throwable t) {
                GnuLog.log("JAVA_ script unregister failed: " + ls.scriptName + " " + t);
            }
            try {
                ls.loader.close();
            } catch (Throwable t) {
                GnuLog.log("JAVA_ script loader close failed: " + ls.scriptName + " " + t);
            }
        }
        loaded.clear();

        // 2. Ensure directories exist.
        File scriptsDir = new File(SCRIPTS_DIR);
        if (!scriptsDir.exists()) {
            scriptsDir.mkdirs();
            GnuLog.log("JAVA_ scripts dir created: " + SCRIPTS_DIR);
            return; // no scripts yet
        }
        File compiledDir = new File(COMPILED_DIR);
        if (!compiledDir.exists())
            compiledDir.mkdirs();

        // 3. Compile + load each .java file.
        File[] files = scriptsDir.listFiles((d, name) -> name.endsWith(".java"));
        if (files == null || files.length == 0) {
            GnuLog.log("JAVA_ no scripts found in " + SCRIPTS_DIR);
            return;
        }
        Arrays.sort(files);
        int successCount = 0;
        for (File f : files) {
            try {
                if (compileAndLoad(f))
                    successCount++;
            } catch (Throwable t) {
                GnuLog.log("JAVA_ script load failed: " + f.getName() + " " + t);
            }
        }
        GnuLog.log("JAVA_ scripts loaded: " + successCount + "/" + files.length);

        // 4. Re-run ConfigManager.load() so saved script settings are applied.
        //    The loading=true guard in ConfigManager prevents re-save during this pass.
        try {
            ConfigManager.INSTANCE.load();
        } catch (Throwable t) {
            GnuLog.log("JAVA_ post-script ConfigManager.load failed: " + t);
        }

        // 5. Refresh NativeBootstrap.GUI_MODULES so the native GUI and JAVA_READY
        //    count include the just-registered script modules. Covers both the
        //    initial boot load (called from NativeBootstrap.init()) and RShift+R
        //    reloads — without this, script modules are live in ModuleManager
        //    but invisible to the GUI.
        try {
            NativeBootstrap.refreshGuiModules();
        } catch (Throwable t) {
            GnuLog.log("JAVA_ refreshGuiModules failed: " + t);
        }
    }

    /**
     * Compile + load a single script file. Returns true on success.
     */
    private boolean compileAndLoad(File scriptFile) {
        String fileName = scriptFile.getName();
        String safeName = sanitizeScriptName(fileName.substring(0, fileName.length() - 5));
        if (safeName.isEmpty())
            return false;

        if (compiler == null) {
            GnuLog.log("JAVA_ no system JavaCompiler (JRE, not JDK) — cannot compile scripts");
            return false;
        }

        String userBody = readFile(scriptFile);
        if (userBody == null || userBody.isEmpty())
            return false;

        String simpleName = "sc_" + safeName + "_" + randomSuffix(5);
        String fqn = SCRIPT_PACKAGE + "." + simpleName;
        GeneratedSource gs = generateWrapper(simpleName, safeName, userBody);

        // Compile to COMPILED_DIR.
        List<String> options = new ArrayList<>();
        options.add("-d");
        options.add(COMPILED_DIR);
        options.add("-XDuseUnsharedTable");
        String jarPath = ourJarPath();
        if (jarPath != null) {
            options.add("-classpath");
            options.add(jarPath);
        }

        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        JavaFileObject source = new StringSource(fqn, gs.source);
        boolean ok = compiler.getTask(null, compiler.getStandardFileManager(diags, null, null),
                diags, options, null, Arrays.asList(source)).call();
        if (!ok) {
            for (Diagnostic<?> d : diags.getDiagnostics()) {
                long userLine = d.getLineNumber() - gs.startingLine;
                GnuLog.log("JAVA_ script compile error [" + safeName + "] line=" + userLine
                        + " " + d.getKind() + " " + d.getMessage(null));
            }
            return false;
        }

        // Load via fresh URLClassLoader.
        URLClassLoader loader;
        Class<?> clazz;
        Module module;
        try {
            URL compiledUrl = new File(COMPILED_DIR).toURI().toURL();
            loader = new URLClassLoader(new URL[] { compiledUrl },
                    ScriptManager.class.getClassLoader());
            clazz = loader.loadClass(fqn);
            Object instance = clazz.newInstance();
            if (!(instance instanceof Module)) {
                GnuLog.log("JAVA_ script " + safeName + " loaded class is not a Module: "
                        + instance.getClass());
                loader.close();
                return false;
            }
            module = (Module) instance;
        } catch (Throwable t) {
            GnuLog.log("JAVA_ script load failed: " + safeName + " " + t);
            return false;
        }

        // Register with ModuleManager.
        ModuleManager.INSTANCE.register(module);

        // Cache optional generated packet hooks before ConfigManager.load() can enable the script.
        invokeGeneratedMethod(module, clazz, "__gnuCachePacketHooks");

        // Call onLoad — registers settings before ConfigManager.load() runs.
        invokeUserMethod(module, clazz, "onLoad", gs.startingLine);

        loaded.put(safeName, new LoadedScript(safeName, module, loader, gs.startingLine));
        GnuLog.log("JAVA_ script loaded: " + safeName + " class=" + fqn);
        return true;
    }

    // ===================== wrapper generation =====================

    private static final class GeneratedSource {
        final String source;
        final int startingLine; // 1-based line in generated source where user body begins

        GeneratedSource(String source, int startingLine) {
            this.source = source;
            this.startingLine = startingLine;
        }
    }

    /**
     * Build the full compilable source for a script. The user body (bare fields +
     * methods) is pasted at the bottom of a generated {@code Module} subclass.
     *
     * <p>User-facing hooks:
     * <ul>
     *   <li>{@code onLoad()} — called once by ScriptManager at registration</li>
     *   <li>{@code onPreUpdate()} — called per tick from generated {@code onTick}</li>
     *   <li>{@code onScriptDisable()} — called on disable from generated {@code onDisable}
     *       (NOT {@code onDisable} — that name collides with the Module override)</li>
     * </ul>
     */
    private GeneratedSource generateWrapper(String className, String safeName, String userBody) {
        StringBuilder sb = new StringBuilder();
        int line = 0;

        sb.append("package gnu.client.script.generated;\n"); line++;
        sb.append("\n"); line++;
        sb.append("import gnu.client.common.GnuLog;\n"); line++;
        sb.append("import gnu.client.module.Category;\n"); line++;
        sb.append("import gnu.client.module.Module;\n"); line++;
        sb.append("import gnu.client.module.setting.BoolSetting;\n"); line++;
        sb.append("import gnu.client.module.setting.SliderSetting;\n"); line++;
        sb.append("import gnu.client.runtime.NativeBootstrap;\n"); line++;
        sb.append("import gnu.client.runtime.mc.McAccess;\n"); line++;
        sb.append("import gnu.client.runtime.packet.PacketEvents;\n"); line++;
        sb.append("import gnu.client.runtime.packet.PacketListener;\n"); line++;
        sb.append("import gnu.client.script.Client;\n"); line++;
        sb.append("import gnu.client.script.Inventory;\n"); line++;
        sb.append("import gnu.client.script.Keybinds;\n"); line++;
        sb.append("import gnu.client.script.Modules;\n"); line++;
        sb.append("import gnu.client.script.Packets;\n"); line++;
        sb.append("import gnu.client.script.Status;\n"); line++;
        sb.append("import gnu.client.script.Util;\n"); line++;
        sb.append("import gnu.client.script.World;\n"); line++;
        sb.append("import java.util.ArrayList;\n"); line++;
        sb.append("import java.util.HashMap;\n"); line++;
        sb.append("import java.util.List;\n"); line++;
        sb.append("import java.util.Map;\n"); line++;
        sb.append("import java.util.Random;\n"); line++;
        sb.append("\n"); line++;

        sb.append("public final class ").append(className).append(" extends Module implements PacketListener {\n"); line++;
        sb.append("\n"); line++;
        sb.append("    public static final String scriptName = \"").append(safeName).append("\";\n"); line++;
        sb.append("    public static final Client client = Client.INSTANCE;\n"); line++;
        sb.append("    public static final World world = World.INSTANCE;\n"); line++;
        sb.append("    public static final Keybinds keybinds = Keybinds.INSTANCE;\n"); line++;
        sb.append("    public static final Inventory inventory = Inventory.INSTANCE;\n"); line++;
        sb.append("    public static final Packets packets = Packets.INSTANCE;\n"); line++;
        sb.append("    public static final Status status = Status.INSTANCE;\n"); line++;
        sb.append("    public static final Util util = Util.INSTANCE;\n"); line++;
        sb.append("\n"); line++;
        sb.append("    private final Modules modules;\n"); line++;
        sb.append("    private java.lang.reflect.Method onPacketSendMethod;\n"); line++;
        sb.append("    private java.lang.reflect.Method onPacketReceiveMethod;\n"); line++;
        sb.append("    private java.lang.reflect.Method packetSendPriorityMethod;\n"); line++;
        sb.append("    private boolean hasPacketHooks;\n"); line++;
        sb.append("    private boolean packetEventsRegistered;\n"); line++;
        sb.append("\n"); line++;

        // Constructor
        sb.append("    public ").append(className).append("() {\n"); line++;
        sb.append("        super(scriptName, \"User script: \" + scriptName, Category.SCRIPTS);\n"); line++;
        sb.append("        modules = new Modules(scriptName, this);\n"); line++;
        sb.append("    }\n"); line++;
        sb.append("\n"); line++;

        // onEnable — packet listener registration is lazy for scripts that declare packet hooks.
        sb.append("    @Override\n"); line++;
        sb.append("    public void onEnable() {\n"); line++;
        sb.append("        if (hasPacketHooks && !packetEventsRegistered) {\n"); line++;
        sb.append("            PacketEvents.register(this);\n"); line++;
        sb.append("            packetEventsRegistered = true;\n"); line++;
        sb.append("        }\n"); line++;
        sb.append("    }\n"); line++;
        sb.append("\n"); line++;

        // onTick — invokes user's onPreUpdate
        sb.append("    @Override\n"); line++;
        sb.append("    public void onTick() {\n"); line++;
        sb.append("        invokeScript(\"onPreUpdate\");\n"); line++;
        sb.append("    }\n"); line++;
        sb.append("\n"); line++;

        // onDisable — invokes user's onScriptDisable (NOT onDisable — name collision)
        sb.append("    @Override\n"); line++;
        sb.append("    public void onDisable() {\n"); line++;
        sb.append("        PacketEvents.unregister(this);\n"); line++;
        sb.append("        packetEventsRegistered = false;\n"); line++;
        sb.append("        invokeScript(\"onScriptDisable\");\n"); line++;
        sb.append("    }\n"); line++;
        sb.append("\n"); line++;

        // PacketListener — optional script hooks cached once at load time.
        sb.append("    /**\n"); line++;
        sb.append("     * Packet hooks follow native PacketListener threading. onPacketReceive(Object)\n"); line++;
        sb.append("     * runs on Netty's channel thread, not the main client thread; script state\n"); line++;
        sb.append("     * shared with onPreUpdate() must be treated as cross-thread state.\n"); line++;
        sb.append("     */\n"); line++;
        sb.append("    public void __gnuCachePacketHooks() {\n"); line++;
        sb.append("        onPacketSendMethod = findScriptMethod(\"onPacketSend\", boolean.class, Object.class);\n"); line++;
        sb.append("        onPacketReceiveMethod = findScriptMethod(\"onPacketReceive\", boolean.class, Object.class);\n"); line++;
        sb.append("        packetSendPriorityMethod = findScriptMethod(\"packetSendPriority\", int.class);\n"); line++;
        sb.append("        hasPacketHooks = onPacketSendMethod != null || onPacketReceiveMethod != null;\n"); line++;
        sb.append("    }\n"); line++;
        sb.append("\n"); line++;
        sb.append("    @Override\n"); line++;
        sb.append("    public boolean onSend(Object packet) {\n"); line++;
        sb.append("        return invokePacketHook(onPacketSendMethod, packet, \"onPacketSend\");\n"); line++;
        sb.append("    }\n"); line++;
        sb.append("\n"); line++;
        sb.append("    @Override\n"); line++;
        sb.append("    public boolean onReceive(Object packet) {\n"); line++;
        sb.append("        return invokePacketHook(onPacketReceiveMethod, packet, \"onPacketReceive\");\n"); line++;
        sb.append("    }\n"); line++;
        sb.append("\n"); line++;
        sb.append("    @Override\n"); line++;
        sb.append("    public int sendPriority() {\n"); line++;
        sb.append("        if (packetSendPriorityMethod == null)\n"); line++;
        sb.append("            return -100;\n"); line++;
        sb.append("        try {\n"); line++;
        sb.append("            Object result = packetSendPriorityMethod.invoke(this);\n"); line++;
        sb.append("            return result instanceof Integer ? (Integer) result : -100;\n"); line++;
        sb.append("        } catch (Throwable t) {\n"); line++;
        sb.append("            GnuLog.log(\"JAVA_ script '\" + scriptName + \"' packetSendPriority threw: \" + t);\n"); line++;
        sb.append("            return -100;\n"); line++;
        sb.append("        }\n"); line++;
        sb.append("    }\n"); line++;
        sb.append("\n"); line++;
        sb.append("    private java.lang.reflect.Method findScriptMethod(String name, Class<?> returnType, Class<?>... params) {\n"); line++;
        sb.append("        try {\n"); line++;
        sb.append("            java.lang.reflect.Method m = getClass().getDeclaredMethod(name, params);\n"); line++;
        sb.append("            if (m.getReturnType() != returnType)\n"); line++;
        sb.append("                return null;\n"); line++;
        sb.append("            m.setAccessible(true);\n"); line++;
        sb.append("            return m;\n"); line++;
        sb.append("        } catch (NoSuchMethodException ignored) {\n"); line++;
        sb.append("            return null;\n"); line++;
        sb.append("        }\n"); line++;
        sb.append("    }\n"); line++;
        sb.append("\n"); line++;
        sb.append("    private boolean invokePacketHook(java.lang.reflect.Method method, Object packet, String methodName) {\n"); line++;
        sb.append("        if (method == null)\n"); line++;
        sb.append("            return false;\n"); line++;
        sb.append("        try {\n"); line++;
        sb.append("            Object result = method.invoke(this, packet);\n"); line++;
        sb.append("            return result instanceof Boolean && (Boolean) result;\n"); line++;
        sb.append("        } catch (Throwable t) {\n"); line++;
        sb.append("            GnuLog.log(\"JAVA_ script '\" + scriptName + \"' \" + methodName + \" threw: \" + t);\n"); line++;
        sb.append("            try { setEnabled(false); } catch (Throwable ignored) {}\n"); line++;
        sb.append("            return false;\n"); line++;
        sb.append("        }\n"); line++;
        sb.append("    }\n"); line++;
        sb.append("\n"); line++;

        // invokeScript — reflective dispatcher with try/catch
        sb.append("    private void invokeScript(String methodName) {\n"); line++;
        sb.append("        try {\n"); line++;
        sb.append("            java.lang.reflect.Method target = null;\n"); line++;
        sb.append("            for (java.lang.reflect.Method m : getClass().getDeclaredMethods()) {\n"); line++;
        sb.append("                if (m.getName().equals(methodName) && m.getParameterCount() == 0\n"); line++;
        sb.append("                        && m.getReturnType() == void.class) {\n"); line++;
        sb.append("                    target = m;\n"); line++;
        sb.append("                    break;\n"); line++;
        sb.append("                }\n"); line++;
        sb.append("            }\n"); line++;
        sb.append("            if (target != null) {\n"); line++;
        sb.append("                target.setAccessible(true);\n"); line++;
        sb.append("                target.invoke(this);\n"); line++;
        sb.append("            }\n"); line++;
        sb.append("        } catch (Throwable t) {\n"); line++;
        sb.append("            GnuLog.log(\"JAVA_ script '\" + scriptName + \"' \" + methodName + \" threw: \" + t);\n"); line++;
        sb.append("            if (!\"onScriptDisable\".equals(methodName)) {\n"); line++;
        sb.append("                setEnabled(false);\n"); line++;
        sb.append("            }\n"); line++;
        sb.append("        }\n"); line++;
        sb.append("    }\n"); line++;
        sb.append("\n"); line++;

        int startingLine = line + 1; // user body starts on the next line (1-based)

        sb.append(userBody);
        if (!userBody.endsWith("\n"))
            sb.append("\n");
        sb.append("}\n");

        return new GeneratedSource(sb.toString(), startingLine);
    }

    // ===================== reflective invocation =====================

    /** Invoke a generated no-arg method on the script wrapper itself. */
    private void invokeGeneratedMethod(Module module, Class<?> clazz, String methodName) {
        try {
            java.lang.reflect.Method target = clazz.getDeclaredMethod(methodName);
            target.setAccessible(true);
            target.invoke(module);
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable t) {
            GnuLog.log("JAVA_ script generated method '" + methodName + "' failed for "
                    + module.getName() + ": " + t);
            try { module.setEnabled(false); } catch (Throwable ignored) {}
        }
    }

    /**
     * Invoke a user-declared no-arg void method on the script instance.
     * Used for onLoad (called by ScriptManager at registration).
     */
    private void invokeUserMethod(Module module, Class<?> clazz, String methodName, int startingLine) {
        try {
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == 0
                        && m.getReturnType() == void.class) {
                    target = m;
                    break;
                }
            }
            if (target != null) {
                target.setAccessible(true);
                target.invoke(module);
            }
        } catch (Throwable t) {
            GnuLog.log("JAVA_ script '" + module.getName() + "' " + methodName
                    + " threw (startingLine=" + startingLine + "): " + t);
            try { module.setEnabled(false); } catch (Throwable ignored) {}
        }
    }

    // ===================== helpers =====================

    private String sanitizeScriptName(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_')
                sb.append(c);
            else
                sb.append('_');
        }
        return sb.toString();
    }

    private String randomSuffix(int len) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++)
            sb.append(chars.charAt(random.nextInt(chars.length())));
        return sb.toString();
    }

    private String readFile(File f) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = br.readLine()) != null)
                sb.append(l).append('\n');
        } catch (Throwable t) {
            GnuLog.log("JAVA_ script read failed: " + f.getName() + " " + t);
            return null;
        }
        return sb.toString();
    }

    /**
     * Resolve the filesystem path to {@code gnu-client.jar} for the compiler
     * classpath. The jar was added to Forge's LaunchClassLoader at startup via
     * {@code URLClassLoader.addURL()} (see {@code jni_bridge.cpp}
     * {@code add_jar_to_classloader} — the path is computed on the native side
     * via {@code dladdr} on {@code gnu-agent.so} and resolved as the sibling
     * {@code gnu-client.jar}).
     *
     * <p>{@code getProtectionDomain().getCodeSource().getLocation()} is
     * <b>unreliable</b> under LaunchClassLoader — the jar is not on the system
     * classpath property, so the code source is null and the compiler falls back
     * to {@code java.class.path} (Minecraft/Forge/Mixin jars), producing
     * "package gnu.client.* does not exist" errors.
     *
     * <p>Instead, walk the classloader chain from the one that loaded
     * {@code ScriptManager} upward, and for each {@link URLClassLoader} scan
     * {@link URLClassLoader#getURLs()} for the entry whose path ends in
     * {@code gnu-client.jar}. This reads the exact URL that {@code addURL()}
     * registered — the same path the native side computed.
     */
    private String ourJarPath() {
        try {
            ClassLoader cl = ScriptManager.class.getClassLoader();
            while (cl != null) {
                if (cl instanceof URLClassLoader) {
                    URL[] urls = ((URLClassLoader) cl).getURLs();
                    if (urls != null) {
                        for (URL url : urls) {
                            if (url == null)
                                continue;
                            String path = url.getPath();
                            if (path != null && path.endsWith("gnu-client.jar")) {
                                try {
                                    path = URLDecoder.decode(path, "UTF-8");
                                } catch (Throwable ignored) {}
                                GnuLog.log("JAVA_ script classpath resolved: " + path
                                        + " (from " + cl.getClass().getName() + ")");
                                return path;
                            }
                        }
                    }
                }
                cl = cl.getParent();
            }
            GnuLog.log("JAVA_ script ourJarPath: gnu-client.jar not found in any"
                    + " URLClassLoader on the classloader chain — script compilation"
                    + " will fail (compiler cannot see gnu.client.* packages)");
        } catch (Throwable t) {
            GnuLog.log("JAVA_ script ourJarPath failed: " + t);
        }
        return null;
    }

    /** In-memory Java source for the compiler. */
    private static final class StringSource extends SimpleJavaFileObject {
        private final String code;

        StringSource(String className, String code) {
            super(java.net.URI.create("string:///" + className.replace('.', '/')
                    + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
