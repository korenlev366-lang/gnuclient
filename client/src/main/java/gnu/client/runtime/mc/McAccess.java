package gnu.client.runtime.mc;

import gnu.client.common.GnuLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import gnu.client.module.modules.combat.AntiBotModule;
import gnu.client.module.modules.combat.RavenAntiBot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection-based access to Minecraft, anchored on Forge's NON-obfuscated
 * {@code net.minecraftforge.fml.client.FMLClientHandler}.
 *
 * Why reflection: this client is injected via addURL into a fully obfuscated
 * (notch) runtime, and our jar is NOT processed by FML's deobfuscating remapper.
 * Therefore SRG type references such as {@code net.minecraft.client.Minecraft}
 * never link (confirmed: ClassNotFoundException; the real class is {@code bao}).
 * FMLClientHandler is a Forge class (never obfuscated), so it loads by name and
 * its {@code getClient()} yields both the Minecraft instance and its runtime
 * Class with no hardcoded notch names or SRG type refs.
 */
public final class McAccess {

    private static volatile Object mc;
    private static volatile Class<?> mcClass;
    private static volatile boolean resolved;

    private McAccess() {}

    /**
     * Resolve the live Minecraft instance via FMLClientHandler. Safe to call
     * repeatedly; returns false until the client singleton exists.
     *
     * The injected jar lives on Launch.classLoader, which can be a DIFFERENT
     * LaunchClassLoader instance than the one the running game uses (observed:
     * our CL @4dfa3a9d vs render-thread context CL @1750fbeb). FMLClientHandler
     * resolved through the wrong loader is a duplicate whose client field is
     * never set. So we try several loaders (render-thread context first, then our
     * own + parents) and accept the first that yields a non-null client.
     */
    public static boolean resolve(ClassLoader own) {
        if (resolved)
            return true;

        java.util.List<ClassLoader> loaders = candidateLoaders(own);
        ClientProfile profile = ClientDetector.detect(loaders.toArray(new ClassLoader[0]));
        ClientProfile.setCurrent(profile);

        if (profile == ClientProfile.FORGE_18) {
            for (ClassLoader cl : loaders) {
                if (cl == null)
                    continue;
                try {
                    Class<?> fml = Class.forName(
                            "net.minecraftforge.fml.client.FMLClientHandler", false, cl);
                    Object handler = fml.getMethod("instance").invoke(null);
                    if (handler == null)
                        continue;
                    Object client = fml.getMethod("getClient").invoke(handler);
                    if (client == null)
                        continue;
                    markResolved(client, cl, profile);
                    return true;
                } catch (Throwable ignored) {
                    // Expected for loaders that don't define FMLClientHandler.
                }
            }
            return false;
        }

        return resolveViaMinecraftSingleton(loaders, profile);
    }

    private static boolean resolveViaMinecraftSingleton(java.util.List<ClassLoader> loaders,
                                                        ClientProfile profile) {
        for (ClassLoader cl : loaders) {
            if (cl == null)
                continue;
            for (String className : MappingTable.minecraftClassNames()) {
                try {
                    Class<?> mcCls = Class.forName(className, false, cl);
                    for (String method : MappingTable.getMinecraftSingletonMethods()) {
                        try {
                            Object instance = mcCls.getMethod(method).invoke(null);
                            if (instance != null) {
                                markResolved(instance, cl, profile);
                                return true;
                            }
                        } catch (NoSuchMethodException ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    private static void markResolved(Object client, ClassLoader cl, ClientProfile profile) {
        mc = client;
        mcClass = client.getClass();
        resolved = true;
        boolean mcp = mcClass.getName().startsWith("net.minecraft.");
        ClientProfile.setMcpRuntime(mcp);
        GnuLog.log("JAVA_ detected: " + profile.label() + " mcClass=" + mcClass.getName()
                + " mcpRuntime=" + mcp + " via " + cl);
    }

    public static ClientProfile profile() {
        return ClientProfile.current();
    }

    private static java.util.List<ClassLoader> candidateLoaders(ClassLoader own) {
        java.util.LinkedHashSet<ClassLoader> set = new java.util.LinkedHashSet<>();
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        addChain(set, ctx);
        addChain(set, own);
        return new java.util.ArrayList<>(set);
    }

    private static void addChain(java.util.Set<ClassLoader> set, ClassLoader cl) {
        for (ClassLoader c = cl; c != null; c = c.getParent())
            set.add(c);
    }

    public static boolean isResolved() {
        return resolved;
    }

    /** The Minecraft instance as a raw Object (obf type at runtime). */
    public static Object minecraft() {
        return mc;
    }

    /** Alias for {@link #minecraft()}. */
    public static Object getMinecraft() {
        return mc;
    }

    /** The runtime Minecraft Class (obf, e.g. {@code bao}). */
    public static Class<?> minecraftClass() {
        return mcClass;
    }

    // ===================== reflection helpers =====================
    //
    // The runtime is SRG-named (class names like net.minecraft.client.Minecraft,
    // but members are field_NNNNN / func_NNNNN — confirmed at runtime). We resolve
    // members by SRG name via reflection, walking the superclass chain (e.g.
    // motionX is declared on Entity but read off an EntityOtherPlayerMP instance).
    // All lookups are cached; all access is failure-tolerant (returns a default /
    // null rather than throwing into the tick loop).

    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
    /** One-time diagnostic: which (startClass, srg) was resolved on which declaring class. */
    private static final java.util.Set<String> FIND_METHOD_DIAG =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final double[] VP_BUF = new double[3];
    private static Object cachedRve;
    private static int rveTickCount;

    /** The class loader that defines the live game classes (deobf game loader). */
    public static ClassLoader gameLoader() {
        Class<?> c = mcClass;
        return c == null ? null : c.getClassLoader();
    }

    /** Load a game class by SRG binary name (e.g. {@code net.minecraft.util.Vec3}). */
    public static Class<?> gameClass(String binaryName) {
        Class<?> cached = CLASS_CACHE.get(binaryName);
        if (cached != null)
            return cached;
        ClassLoader cl = gameLoader();
        if (cl == null)
            return null;
        for (String candidate : MappingTable.classCandidates(binaryName)) {
            try {
                Class<?> c = Class.forName(candidate, false, cl);
                CLASS_CACHE.put(binaryName, c);
                return c;
            } catch (Throwable ignored) {
            }
        }
        GnuLog.log("JAVA_ McAccess gameClass failed " + binaryName);
        return null;
    }

    private static Field findField(Class<?> start, String srg) {
        if (start == null)
            return null;
        for (String name : MappingTable.memberCandidates(srg)) {
            String key = start.getName() + '#' + name;
            Field cached = FIELD_CACHE.get(key);
            if (cached != null)
                return cached;
            for (Class<?> c = start; c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    FIELD_CACHE.put(key, f);
                    return f;
                } catch (NoSuchFieldException ignored) {
                }
            }
        }
        return null;
    }

    public static Object getObject(Object owner, String srg) {
        if (owner == null)
            return null;
        Field f = findField(owner.getClass(), srg);
        if (f == null)
            return null;
        try {
            return f.get(owner);
        } catch (Throwable t) {
            return null;
        }
    }

    public static double getDouble(Object owner, String srg) {
        if (owner == null)
            return 0.0;
        Field f = findField(owner.getClass(), srg);
        if (f == null)
            return 0.0;
        try {
            return f.getDouble(owner);
        } catch (Throwable t) {
            return 0.0;
        }
    }

    public static int getInt(Object owner, String srg) {
        if (owner == null)
            return 0;
        Field f = findField(owner.getClass(), srg);
        if (f == null)
            return 0;
        try {
            return f.getInt(owner);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static float getFloat(Object owner, String srg) {
        if (owner == null)
            return 0.0f;
        Field f = findField(owner.getClass(), srg);
        if (f == null)
            return 0.0f;
        try {
            return f.getFloat(owner);
        } catch (Throwable t) {
            return 0.0f;
        }
    }

    public static boolean getBool(Object owner, String srg) {
        if (owner == null)
            return false;
        Field f = findField(owner.getClass(), srg);
        if (f == null)
            return false;
        try {
            return f.getBoolean(owner);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setDouble(Object owner, String srg, double value) {
        if (owner == null)
            return;
        Field f = findField(owner.getClass(), srg);
        if (f == null)
            return;
        try {
            f.setDouble(owner, value);
        } catch (Throwable ignored) {
        }
    }

    public static void setInt(Object owner, String srg, int value) {
        if (owner == null)
            return;
        Field f = findField(owner.getClass(), srg);
        if (f == null)
            return;
        try {
            f.setInt(owner, value);
        } catch (Throwable ignored) {
        }
    }

    public static void setBool(Object owner, String srg, boolean value) {
        if (owner == null)
            return;
        Field f = findField(owner.getClass(), srg);
        if (f == null)
            return;
        try {
            f.setBoolean(owner, value);
        } catch (Throwable ignored) {
        }
    }

    public static void setFloat(Object owner, String srg, float value) {
        if (owner == null)
            return;
        Field f = findField(owner.getClass(), srg);
        if (f == null)
            return;
        try {
            f.setFloat(owner, value);
        } catch (Throwable ignored) {
        }
    }

    public static void setObject(Object owner, String srg, Object value) {
        if (owner == null)
            return;
        Field f = findField(owner.getClass(), srg);
        if (f == null)
            return;
        try {
            f.set(owner, value);
        } catch (Throwable ignored) {
        }
    }

    private static Method findMethod(Class<?> start, String srg, Class<?>[] params) {
        if (start == null)
            return null;
        for (String name : MappingTable.memberCandidates(srg)) {
            StringBuilder kb = new StringBuilder(start.getName()).append('#').append(name);
            for (Class<?> p : params)
                kb.append(',').append(p == null ? "null" : p.getName());
            String key = kb.toString();
            Method cached = METHOD_CACHE.get(key);
            if (cached != null)
                return cached;
            // Walk the superclass chain with an explicit while loop (not for-loop)
            // so the superclass advance is unambiguous after the name-only fallback.
            Class<?> c = start;
            while (c != null) {
                try {
                    Method m = c.getDeclaredMethod(name, params);
                    m.setAccessible(true);
                    METHOD_CACHE.put(key, m);
                    // One-time log: which class level resolved this method
                    if (FIND_METHOD_DIAG.add(start.getName() + "#" + srg)) {
                        GnuLog.log("JAVA_ findMethod: " + start.getName() + "#" + srg
                                + " resolved via getDeclaredMethod on " + c.getName());
                    }
                    return m;
                } catch (NoSuchMethodException e) {
                    // Fallback: name-only match within same class before moving to superclass.
                    // Handles OptiFine param type mismatches (e.g., EntityPlayerSP vs EntityPlayer).
                    // Method.invoke handles subtype compatibility at call time.
                    // Also requires matching parameter COUNT to skip OptiFine overloads with
                    // different arity (e.g. PlayerControllerOF.func_78769_a has a 2-param variant
                    // that is not the real sendUseItem).
                    for (Method m2 : c.getDeclaredMethods()) {
                        if (m2.getName().equals(name)
                                && m2.getParameterTypes().length == params.length) {
                            m2.setAccessible(true);
                            METHOD_CACHE.put(key, m2);
                            // One-time log: which class level resolved this method (fallback)
                            if (FIND_METHOD_DIAG.add(start.getName() + "#" + srg)) {
                                GnuLog.log("JAVA_ findMethod: " + start.getName() + "#" + srg
                                        + " resolved via name-only fallback on " + c.getName());
                            }
                            return m2;
                        }
                    }
                }
                // Explicit superclass advance — after the fallback scan found no match
                // on this class level, continue up the chain.
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** Invoke an SRG-named instance method; returns null on any failure. */
    public static Object invoke(Object owner, String srg, Class<?>[] params, Object... args) {
        if (owner == null)
            return null;
        Method m = findMethod(owner.getClass(), srg, params == null ? new Class<?>[0] : params);
        if (m == null)
            return null;
        try {
            return m.invoke(owner, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Invoke by deobf or SRG method name, walking the owner class hierarchy. */
    public static Object invokeNamed(Object owner, String methodName, Class<?>[] paramTypes, Object... args) {
        if (owner == null)
            return null;
        Class<?>[] params = paramTypes == null ? new Class<?>[0] : paramTypes;
        Method m = findMethod(owner.getClass(), methodName, params);
        if (m == null) {
            if ("setSneaking".equals(methodName))
                return invokeNamed(owner, "func_70095_a", paramTypes, args);
            return null;
        }
        try {
            return m.invoke(owner, args);
        } catch (Throwable t) {
            GnuLog.log("JAVA_ McAccess.invokeNamed(" + methodName + ") error: " + t);
            return null;
        }
    }

    /** EntityPlayerSP.setSneaking — speed reduction and server sneak sync (1.8.9). */
    public static void setSneaking(Object player, boolean sneak) {
        invokeNamed(player, "setSneaking", new Class<?>[] { boolean.class }, sneak);
    }

    /** Invoke an SRG-named static method; returns null on any failure. */
    public static Object invokeStatic(Class<?> cls, String srg, Class<?>[] params, Object... args) {
        if (cls == null)
            return null;
        Method m = findMethod(cls, srg, params == null ? new Class<?>[0] : params);
        if (m == null)
            return null;
        try {
            return m.invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Construct a game object by SRG class name; returns null on any failure. */
    public static Object newInstance(String className, Class<?>[] params, Object... args) {
        Class<?> c = gameClass(className);
        if (c == null)
            return null;
        try {
            Constructor<?> ctor = c.getDeclaredConstructor(params == null ? new Class<?>[0] : params);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (Throwable t) {
            GnuLog.log("JAVA_ McAccess newInstance failed " + className + ": " + t);
            return null;
        }
    }

    // ----- convenience accessors on the Minecraft singleton -----

    public static Object thePlayer() {
        return getObject(mc, "field_71439_g");
    }

    public static Object thePlayer(Object minecraft) {
        return getObject(minecraft, "field_71439_g");
    }

    public static Object theWorld() {
        return getObject(mc, "field_71441_e");
    }

    public static Object theWorld(Object minecraft) {
        return getObject(minecraft, "field_71441_e");
    }

    public static Object currentScreen(Object minecraft) {
        return getObject(minecraft, "field_71462_r");
    }

    public static Object fontRenderer() {
        return getObject(mc, "field_71466_p");
    }

    @SuppressWarnings("unchecked")
    public static List<?> getList(Object owner, String srg) {
        Object value = getObject(owner, srg);
        if (value instanceof List)
            return (List<?>) value;
        return Collections.emptyList();
    }

    /** Player/entity list on WorldClient, with loadedEntityList fallback. */
    public static List<?> getWorldEntities(Object world) {
        if (world == null)
            return Collections.emptyList();
        for (String srg : new String[] { "field_73010_i", "field_72996_f" }) {
            List<?> list = getList(world, srg);
            if (!list.isEmpty())
                return list;
        }
        return Collections.emptyList();
    }

    /** World entities with RavenAntiBot entries removed only while AntiBot is enabled. */
    @SuppressWarnings("unchecked")
    public static List<?> getWorldEntitiesFiltered(Object world) {
        List<?> raw = getWorldEntities(world);
        if (!AntiBotModule.isActive())
            return raw;

        List<Object> filtered = new ArrayList<>();
        for (Object entity : raw) {
            if (RavenAntiBot.isBot(entity))
                continue;
            filtered.add(entity);
        }
        return filtered;
    }

    /** Read all player names currently in the tab list (playerInfoMap -> GameProfile -> name). */
    @SuppressWarnings("unchecked")
    public static Set<String> getTablistNames() {
        Object mc = getMinecraft();
        if (mc == null)
            return Collections.emptySet();
        Object netHandler = getNetHandler(mc);
        if (netHandler == null)
            return Collections.emptySet();
        Object infoMap = invoke(netHandler, "func_175106_d", new Class<?>[0]);
        if (!(infoMap instanceof Collection))
            return Collections.emptySet();
        Set<String> names = new HashSet<>();
        for (Object info : (Collection<?>) infoMap) {
            if (info == null) continue;
            Object profile = invoke(info, "func_178845_a", new Class<?>[0]);
            if (profile == null) continue;
            Object name = invokeNamed(profile, "getName", new Class<?>[0]);
            if (name != null)
                names.add(name.toString());
        }
        return names;
    }

    /** One-shot sanity log after entering a world (Lunar mapping debug). */
    public static void logRuntimeProbe() {
        Object player = thePlayer();
        Object world = theWorld();
        Object settings = gameSettings();
        List<?> entities = getWorldEntities(world);
        GnuLog.log("JAVA_ McAccess probe: player=" + (player != null)
                + " world=" + (world != null)
                + " settings=" + (settings != null)
                + " entities=" + entities.size()
                + " profile=" + ClientProfile.current().label()
                + " mcpRuntime=" + ClientProfile.mcpRuntime());
    }

    public static Object getRenderManager() {
        return invoke(mc, "func_175598_ae", new Class<?>[0]);
    }

    public static Object renderViewEntity(Object minecraft) {
        if (minecraft == null)
            return null;
        if (cachedRve == null || rveTickCount++ % 20 == 0) {
            Object r = invokeNamed(minecraft, "getRenderViewEntity", new Class<?>[0]);
            if (r != null)
                cachedRve = r;
            else
                cachedRve = invoke(minecraft, "func_175606_aa", new Class<?>[0]);
        }
        return cachedRve;
    }

    public static Object renderViewEntity() {
        return renderViewEntity(mc);
    }

    /** Interpolated render-view entity position (camera anchor for world overlays). */
    public static double[] getViewerPos(Object minecraft, float partialTicks) {
        Object rve = renderViewEntity(minecraft);
        if (rve == null)
            rve = thePlayer(minecraft);
        if (rve == null) {
            VP_BUF[0] = 0.0;
            VP_BUF[1] = 0.0;
            VP_BUF[2] = 0.0;
            return VP_BUF;
        }

        double lx = entityLastX(rve);
        double ly = entityLastY(rve);
        double lz = entityLastZ(rve);
        double px = entityPosX(rve);
        double py = entityPosY(rve);
        double pz = entityPosZ(rve);

        VP_BUF[0] = lerp(lx, px, partialTicks);
        VP_BUF[1] = lerp(ly, py, partialTicks);
        VP_BUF[2] = lerp(lz, pz, partialTicks);
        return VP_BUF;
    }

    public static double entityLastX(Object entity) {
        return getDouble(entity, "field_70142_S");
    }

    public static double entityLastY(Object entity) {
        return getDouble(entity, "field_70137_T");
    }

    public static double entityLastZ(Object entity) {
        return getDouble(entity, "field_70136_U");
    }

    public static double entityPosX(Object entity) {
        return getDouble(entity, "field_70165_t");
    }

    public static double entityPosY(Object entity) {
        return getDouble(entity, "field_70163_u");
    }

    public static double entityPosZ(Object entity) {
        return getDouble(entity, "field_70161_v");
    }

    public static double lerp(double last, double pos, double partialTicks) {
        return last + (pos - last) * partialTicks;
    }

    public static float playerViewY() {
        return getFloat(getRenderManager(), "field_78735_i");
    }

    public static float playerViewX() {
        return getFloat(getRenderManager(), "field_78732_j");
    }

    public static boolean isInGame() {
        Object minecraft = getMinecraft();
        if (minecraft == null)
            return false;
        Object player = thePlayer(minecraft);
        Object world = theWorld(minecraft);
        Object screen = currentScreen(minecraft);
        return player != null && world != null && screen == null;
    }

    private static volatile Field cachedTimerSpeedField;
    private static volatile Field cachedMcTimerField;
    private static volatile boolean timerSpeedResolveLogged;

    /** Live {@code Minecraft.timer} instance. */
    public static Object getTimer() {
        Object minecraft = getMinecraft();
        if (minecraft == null)
            return null;
        Object timer = getObject(minecraft, "field_71428_T");
        if (timer != null)
            return timer;
        Field mcTimer = resolveMcTimerField(minecraft);
        if (mcTimer == null)
            return null;
        try {
            return mcTimer.get(minecraft);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field resolveMcTimerField(Object minecraft) {
        Field cached = cachedMcTimerField;
        if (cached != null)
            return cached;
        Class<?> timerClass = gameClass("net.minecraft.util.Timer");
        if (timerClass == null)
            return null;
        for (Class<?> c = minecraft.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!timerClass.isAssignableFrom(f.getType()))
                    continue;
                f.setAccessible(true);
                cachedMcTimerField = f;
                GnuLog.log("JAVA_ Minecraft.timer resolved via type scan -> " + f.getName()
                        + " mcClass=" + minecraft.getClass().getName());
                return f;
            }
        }
        return null;
    }

    /** Timer.renderPartialTicks for world/render modules on non-Forge clients. */
    public static float getPartialTicks() {
        Object timer = getTimer();
        if (timer == null)
            return 1.0f;
        return getFloat(timer, "field_74281_c");
    }

    private static Field resolveTimerSpeedField(Object timer) {
        if (timer == null)
            return null;
        Field cached = cachedTimerSpeedField;
        if (cached != null)
            return cached;

        Class<?> timerClass = timer.getClass();
        for (String srg : new String[] { "field_74278_d", "field_74278_a" }) {
            Field mapped = findField(timerClass, srg);
            if (mapped != null) {
                cachedTimerSpeedField = mapped;
                if (!timerSpeedResolveLogged) {
                    timerSpeedResolveLogged = true;
                    GnuLog.log("JAVA_ Timer.timerSpeed resolved via " + srg + " -> "
                            + mapped.getName() + " timerClass=" + timerClass.getName());
                }
                return mapped;
            }
        }

        for (Field f : timerClass.getDeclaredFields()) {
            if (f.getType() != float.class)
                continue;
            String name = f.getName();
            if (!"timerSpeed".equals(name) && !"d".equals(name) && !"e".equals(name)
                    && !"a".equals(name) && !"field_74278_d".equals(name))
                continue;
            f.setAccessible(true);
            cachedTimerSpeedField = f;
            if (!timerSpeedResolveLogged) {
                timerSpeedResolveLogged = true;
                GnuLog.log("JAVA_ Timer.timerSpeed resolved by name -> " + name
                        + " timerClass=" + timerClass.getName());
            }
            return f;
        }

        if (!timerSpeedResolveLogged) {
            timerSpeedResolveLogged = true;
            StringBuilder fields = new StringBuilder();
            for (Field f : timerClass.getDeclaredFields())
                fields.append(f.getName()).append(':').append(f.getType().getSimpleName()).append(' ');
            GnuLog.log("JAVA_ Timer.timerSpeed field not found timerClass=" + timerClass.getName()
                    + " fields=[" + fields + "]");
        }
        return null;
    }

    /** {@code Timer.timerSpeed} — game tick multiplier (1.0 = vanilla). */
    public static float getTimerSpeed() {
        Object timer = getTimer();
        if (timer == null)
            return 1.0f;
        Field f = resolveTimerSpeedField(timer);
        if (f == null)
            return 1.0f;
        try {
            return f.getFloat(timer);
        } catch (Throwable t) {
            return 1.0f;
        }
    }

    public static void setTimerSpeed(float speed) {
        Object timer = getTimer();
        if (timer == null)
            return;
        writeTimerSpeed(timer, speed);
    }

    /** Write {@code timerSpeed} and verify; retries with alternate SRG names if needed. */
    public static boolean setTimerSpeedVerified(float speed) {
        Object timer = getTimer();
        if (timer == null)
            return false;
        writeTimerSpeed(timer, speed);
        if (valuesNear(readTimerSpeed(timer), speed))
            return true;

        cachedTimerSpeedField = null;
        setFloat(timer, "field_74278_d", speed);
        setFloat(timer, "field_74278_d", speed);
        writeTimerSpeed(timer, speed);
        if (valuesNear(readTimerSpeed(timer), speed))
            return true;

        if (!timerSpeedResolveLogged) {
            timerSpeedResolveLogged = true;
            GnuLog.log("JAVA_ Timer.setTimerSpeedVerified failed wanted=" + speed
                    + " got=" + readTimerSpeed(timer) + " timer=" + timer.getClass().getName());
        }
        return false;
    }

    public static void resetTimer() {
        setTimerSpeedVerified(1.0f);
    }

    private static void writeTimerSpeed(Object timer, float speed) {
        Field f = resolveTimerSpeedField(timer);
        if (f != null) {
            try {
                f.setFloat(timer, speed);
                return;
            } catch (Throwable ignored) {
            }
        }
        setFloat(timer, "field_74278_d", speed);
        setFloat(timer, "field_74278_d", speed);
    }

    private static boolean valuesNear(float a, float b) {
        return Math.abs(a - b) <= 0.001f;
    }

    private static float readTimerSpeed(Object timer) {
        Field f = resolveTimerSpeedField(timer);
        if (f == null)
            return 1.0f;
        try {
            return f.getFloat(timer);
        } catch (Throwable t) {
            return 1.0f;
        }
    }

    /** Construct {@code ScaledResolution} via reflection (no compile-time MC types). */
    public static Object createScaledResolution() {
        Object minecraft = getMinecraft();
        if (minecraft == null)
            return null;
        Class<?> sr = gameClass("net.minecraft.client.gui.ScaledResolution");
        if (sr == null)
            return null;
        Class<?> mcCls = mcClass;
        if (mcCls == null)
            mcCls = minecraft.getClass();
        try {
            java.lang.reflect.Constructor<?> ctor = sr.getDeclaredConstructor(mcCls);
            ctor.setAccessible(true);
            return ctor.newInstance(minecraft);
        } catch (Throwable t) {
            return null;
        }
    }

    public static double lerpPos(Object entity, String lastSrg, String posSrg, double partialTicks) {
        double last = getDouble(entity, lastSrg);
        double pos = getDouble(entity, posSrg);
        return last + (pos - last) * partialTicks;
    }

    public static double interpX(Object entity, double partialTicks) {
        return lerpPos(entity, "field_70142_S", "field_70165_t", partialTicks);
    }

    public static double interpY(Object entity, double partialTicks) {
        return lerpPos(entity, "field_70137_T", "field_70163_u", partialTicks);
    }

    public static double interpZ(Object entity, double partialTicks) {
        return lerpPos(entity, "field_70136_U", "field_70161_v", partialTicks);
    }

    public static boolean isSneaking(Object entity) {
        Object result = invokeNamed(entity, "isSneaking", new Class<?>[0]);
        return result instanceof Boolean && (Boolean) result;
    }

    public static int entityId(Object entity) {
        if (entity == null)
            return -1;
        int id = getInt(entity, "field_70157_k");
        if (id == 0) {
            Object boxed = invoke(entity, "func_145782_y", new Class<?>[0]);
            if (boxed instanceof Integer)
                id = (Integer) boxed;
        }
        return id;
    }

    public static Object playerController() {
        return getObject(mc, "field_71442_b");
    }
    /** One-shot diagnostic flag for sendUseItem method enumeration. */
    private static volatile boolean sendUseItemDiagLogged;

    /** PlayerControllerMP.sendUseItem — processes right-click item use (sword blocking, placing blocks, etc.). */
    public static boolean sendUseItem() {
        return sendUseItem(false);
    }

    /**
     * PlayerControllerMP.sendUseItem with optional blockHitTimer clear.
     *
     * @param clearBlockHitTimer if true, zero {@code PlayerControllerMP.field_78781_i} (blockHitTimer)
     *                           before calling sendUseItem — prevents the 5-tick silent rejection
     *                           after {@code attackEntity()} sets it.
     */
    public static boolean sendUseItem(boolean clearBlockHitTimer) {
        Object controller = playerController();
        if (controller == null) {
            GnuLog.log("JAVA_ McAccess.sendUseItem: controller=null");
            return false;
        }
        Object player = thePlayer();
        Object world = theWorld();
        if (player == null || world == null) {
            GnuLog.log("JAVA_ McAccess.sendUseItem: player=" + (player != null) + " world=" + (world != null));
            return false;
        }
        Object stack = invoke(player, "func_70694_bm", new Class<?>[0]);
        if (stack == null) {
            GnuLog.log("JAVA_ McAccess.sendUseItem: getCurrentItem()=null (no item held)");
            return false;
        }
        // Log what item was retrieved
        String stackInfo;
        try {
            Object item = invoke(stack, "func_77973_b", new Class<?>[0]);
            stackInfo = "item=" + (item != null ? item.getClass().getName() : "null");
            if (item != null) {
                Object itemId = invoke(item, "func_77658_a", new Class<?>[0]); // getUnlocalizedName
                if (itemId != null) stackInfo += " id=" + itemId;
            }
            int count = getInt(stack, "field_77994_a");
            int meta = getInt(stack, "field_77991_e");
            stackInfo += " count=" + count + " meta=" + meta;
        } catch (Throwable t) {
            stackInfo = "stack-class=" + stack.getClass().getName() + " error=" + t.getMessage();
        }
        GnuLog.log("JAVA_ McAccess.sendUseItem: currentItem=" + stackInfo);

        if (clearBlockHitTimer)
            clearBlockHitTimer(controller);

        // Use the DECLARED abstract parameter types (EntityPlayer, World, ItemStack)
        // not the concrete runtime types (EntityPlayerSP, WorldClient, ItemStack).
        // Java getDeclaredMethod does EXACT type matching, not subtype matching.
        Class<?> entityPlayerCls = gameClass("net.minecraft.entity.player.EntityPlayer");
        Class<?> worldCls = gameClass("net.minecraft.world.World");
        Class<?> itemStackCls = gameClass("net.minecraft.item.ItemStack");
        if (entityPlayerCls == null || worldCls == null || itemStackCls == null) {
            GnuLog.log("JAVA_ McAccess.sendUseItem: failed to resolve parameter types" +
                    " entityPlayer=" + (entityPlayerCls != null) +
                    " world=" + (worldCls != null) +
                    " itemStack=" + (itemStackCls != null));
            return false;
        }
        GnuLog.log("JAVA_ McAccess.sendUseItem: trying func_78769_a on controller=" + controller.getClass().getName());

        // One-time diagnostic: enumerate controller methods containing 'send'/'Use'/'78768'
        // to detect void-return overrides (e.g. PlayerControllerOF overriding sendUseItem as void).
        if (!sendUseItemDiagLogged) {
            sendUseItemDiagLogged = true;
            StringBuilder diag = new StringBuilder(
                    "JAVA_ McAccess.sendUseItem: controller methods matching 'send'/'Use'/'78768':");
            for (java.lang.reflect.Method m : controller.getClass().getDeclaredMethods()) {
                String name = m.getName();
                if (name.contains("send") || name.contains("Use") || name.contains("78768")) {
                    String params = java.util.Arrays.stream(m.getParameterTypes())
                            .map(Class::getSimpleName)
                            .collect(java.util.stream.Collectors.joining(","));
                    diag.append("\n  ").append(name).append(" -> ")
                            .append(m.getReturnType().getSimpleName())
                            .append(" params=[").append(params).append("]")
                            .append(" (declaringClass=")
                            .append(m.getDeclaringClass().getSimpleName()).append(")");
                }
            }
            GnuLog.log(diag.toString());
        }

        // Find the method manually instead of using invoke() so we can detect void return:
        // when PlayerControllerOF overrides sendUseItem as void, Method.invoke returns null,
        // which invoke() would treat as "not found" — we fix that here.
        Method method = findMethod(controller.getClass(), "func_78769_a",
                new Class<?>[] { entityPlayerCls, worldCls, itemStackCls });
        if (method == null) {
            GnuLog.log("JAVA_ McAccess.sendUseItem: func_78769_a not found on "
                    + controller.getClass().getName());
            return false;
        }

        boolean isVoid = method.getReturnType() == void.class;
        GnuLog.log("JAVA_ McAccess.sendUseItem: invoking with args=[player=" + player + " world=" + world + " stack=" + stack + "]");
        try {
            Object result = method.invoke(controller, player, world, stack);
            if (isVoid) {
                // Void method ran successfully (null from invoke is expected, not failure)
                GnuLog.log("JAVA_ McAccess.sendUseItem: func_78769_a invoked (void return)");
                return true;
            }
            boolean ok = result instanceof Boolean && (Boolean) result;
            GnuLog.log("JAVA_ McAccess.sendUseItem: controller returned=" + result +
                    " ok=" + ok + " resultType=" + (result != null ? result.getClass().getName() : "null"));
            if (ok) {
                return true;
            }
            // OptiFine fallback: PlayerControllerOF.func_78769_a may return false when OptiFine
            // rejects the item use. Try the PARENT class method directly to bypass the override.
            if (controller.getClass().getName().contains("PlayerControllerOF")) {
                Class<?> parentController = gameClass("net.minecraft.client.multiplayer.PlayerControllerMP");
                if (parentController != null) {
                    try {
                        java.lang.reflect.Method parentMethod = parentController.getDeclaredMethod(
                                method.getName(), entityPlayerCls, worldCls, itemStackCls);
                        parentMethod.setAccessible(true);
                        Object parentResult = parentMethod.invoke(controller, player, world, stack);
                        boolean parentOk = parentResult instanceof Boolean && (Boolean) parentResult;
                        if (parentOk) {
                            GnuLog.log("JAVA_ McAccess.sendUseItem: parent fallback succeeded");
                            return true;
                        }
                    } catch (Throwable t2) {
                        GnuLog.log("JAVA_ McAccess.sendUseItem: parent fallback failed: " + t2);
                    }
                }
            }
            GnuLog.log("JAVA_ McAccess.sendUseItem returned false");
            return false;
        } catch (Throwable t) {
            GnuLog.log("JAVA_ McAccess.sendUseItem: func_78769_a threw " + t);
            return false;
        }
    }

    /** Zero the 5-tick blockHitDelay after attack so sendUseItem does not silently drop C08. SRG field_78781_i (blockHitDelay per CSV stable_22). */
    public static void clearBlockHitTimer() {
        Object controller = playerController();
        if (controller != null)
            clearBlockHitTimer(controller);
    }

    private static void clearBlockHitTimer(Object controller) {
        if (!trySetInt(controller, "field_78781_i", 0))
            trySetInt(controller, "blockHitTimer", 0);
    }


    public static Object objectMouseOver() {
        return getObject(mc, "field_71476_x");
    }

    /** MCP 1.8.9: {@code Minecraft.pointedEntity} = {@code field_147125_j}. */
    public static Object pointedEntity() {
        return getObject(mc, "field_147125_j");
    }

    public static void setObjectMouseOver(Object mop) {
        setObject(mc, "field_71476_x", mop);
    }

    public static void setPointedEntity(Object entity) {
        setObject(mc, "field_147125_j", entity);
    }

    public static Object currentScreen() {
        return getObject(mc, "field_71462_r");
    }

    public static Object gameSettings() {
        return getObject(mc, "field_71474_y");
    }

    /** Vanilla mouse GCD factor — {@code GameSettings.mouseSensitivity} (SRG field_74341_c). */
    public static float getMouseSensitivityGcd() {
        try {
            Object settings = gameSettings();
            if (settings == null)
                return 0.15f;
            float sens = getFloat(settings, "field_74341_c");
            float f = sens * 0.6f + 0.2f;
            return f * f * f * 1.2f;
        } catch (Throwable t) {
            return 0.15f;
        }
    }

    /**
     * Force the local player's sneak state from a tick event.
     *
     * Writing {@code movementInput.sneak} alone does NOT work from
     * {@code ClientTickEvent}: {@code MovementInputFromOptions.updatePlayerMoveState()}
     * re-reads {@code sneak} from {@code keyBindSneak} every tick (before it is
     * consumed), so an external write is overwritten before use. The value that
     * actually survives is the sneak keybind's pressed state, so we drive that via
     * the static {@code KeyBinding.setKeyBindState(keyCode, pressed)}. We also set
     * the {@code movementInput.sneak} field directly (harmless, helps any same-tick
     * read).
     *
     * SRG (1.8.9): movementInput=field_71158_b (EntityPlayerSP); sneak=field_78899_d
     * (MovementInput); keyBindSneak=field_74311_E (GameSettings);
     * KeyBinding.getKeyCode=func_151463_i, KeyBinding.setKeyBindState=func_74510_a.
     */
    public static Object getNetHandler(Object minecraft) {
        if (minecraft == null)
            return null;
        Object handler = invoke(minecraft, "func_147114_u", new Class<?>[0]);
        if (handler == null)
            handler = invokeNamed(minecraft, "getNetHandler", new Class<?>[0]);
        return handler;
    }

    public static Object getNetworkManager(Object netHandler) {
        if (netHandler == null)
            return null;
        Object nm = invoke(netHandler, "func_147298_b", new Class<?>[0]);
        if (nm == null)
            nm = invokeNamed(netHandler, "getNetworkManager", new Class<?>[0]);
        return nm;
    }

    /** Raven {@code EnumLagDirection.OUTBOUND} — {@code NetHandlerPlayClient.addToSendQueue}. */
    public static void addToSendQueue(Object packet) {
        if (packet == null)
            return;
        Object netHandler = getNetHandler(getMinecraft());
        if (netHandler == null) {
            gnu.client.runtime.packet.PacketUtil.sendPacket(packet);
            return;
        }
        Class<?> packetCls = gameClass("net.minecraft.network.Packet");
        if (packetCls == null)
            packetCls = packet.getClass();
        try {
            invoke(netHandler, "func_147297_a", new Class<?>[] { packetCls }, packet);
        } catch (Throwable t) {
            try {
                invokeNamed(netHandler, "addToSendQueue", new Class<?>[] { packetCls }, packet);
            } catch (Throwable t2) {
                GnuLog.log("JAVA_ McAccess.addToSendQueue fallback sendPacket: " + t2);
                gnu.client.runtime.packet.PacketUtil.sendPacket(packet);
            }
        }
    }

    /**
     * Drive jump through vanilla movement input + jump keybind (same idea as
     * LiquidBounce {@code MovementInputEvent.jump}), not raw {@code motionY}.
     *
     * SRG (1.8.9): movementInput=field_71158_b; jump=field_78901_c (MovementInput);
     * keyBindJump=field_74314_A; KeyBinding.setKeyBindState=func_74510_a.
     */
    public static void setJumpInput(Object player, boolean jump) {
        try {
            if (player == null)
                return;

            Object movInput = getObject(player, "field_71158_b");
            if (movInput == null)
                movInput = getObject(player, "field_71159_q");
            if (movInput != null)
                setBool(movInput, "field_78901_c", jump);

            Object keyBindJump = getObject(gameSettings(), "field_74314_A");
            if (keyBindJump != null) {
                Object keyCode = invoke(keyBindJump, "func_151463_i", new Class<?>[0]);
                if (keyCode instanceof Integer) {
                    Class<?> kb = gameClass("net.minecraft.client.settings.KeyBinding");
                    invokeStatic(kb, "func_74510_a",
                            new Class<?>[] { int.class, boolean.class }, keyCode, jump);
                }
            }
        } catch (Throwable t) {
            GnuLog.log("JAVA_ McAccess setJumpInput error: " + t);
        }
    }

    /** Press/release back keybind. keyBindBack=field_74368_y (CSV stable_22). */
    public static void setBackKeyState(boolean pressed) {
        setKeyBindState("field_74368_y", pressed);
    }

    /** Press/release forward keybind. keyBindForward=field_74351_w. */
    public static void setForwardKeyState(boolean pressed) {
        setKeyBindState("field_74351_w", pressed);
    }

    /** Press/release left keybind. keyBindLeft=field_74370_x. */
    public static void setLeftKeyState(boolean pressed) {
        setKeyBindState("field_74370_x", pressed);
    }

    /** Press/release right keybind. keyBindRight=field_74366_z. */
    public static void setRightKeyState(boolean pressed) {
        setKeyBindState("field_74366_z", pressed);
    }

    /** Press/release attack keybind. keyBindAttack=field_74312_F. */
    public static void setAttackKeyState(boolean pressed) {
        setKeyBindState("field_74312_F", pressed);
    }


    /** Press/release use-item keybind. keyBindUseItem=field_74313_G (CSV stable_22: field_74313_G=keyBindUseItem). */
    public static void setUseItemKeyState(boolean pressed) {
        setKeyBindState("field_74313_G", pressed);
    }

    /** One synthetic use-item key press via KeyBinding.onTick — no OS input injection. */
    public static void pressUseItemKeyOnce() {
        try {
            Object keyBind = getObject(gameSettings(), "field_74313_G");
            if (keyBind == null)
                return;
            Object keyCode = invoke(keyBind, "func_151463_i", new Class<?>[0]);
            if (!(keyCode instanceof Integer))
                return;
            Class<?> kb = gameClass("net.minecraft.client.settings.KeyBinding");
            invokeStatic(kb, "func_74507_a", new Class<?>[] { int.class }, keyCode);
        } catch (Throwable t) {
            GnuLog.log("JAVA_ McAccess pressUseItemKeyOnce error: " + t);
        }
    }

    /** MC attack key held (KeyBinding.isKeyDown). Works on Wayland via GLFW. */
    public static boolean isAttackKeyDown() {
        return isKeyBindDown("field_74312_F");
    }

    public static boolean isForwardKeyHeld() {
        return isPhysicalKeyBindDown("field_74351_w");
    }

    public static boolean isBackKeyHeld() {
        return isPhysicalKeyBindDown("field_74368_y");
    }

    public static boolean isLeftKeyHeld() {
        return isPhysicalKeyBindDown("field_74370_x");
    }

    public static boolean isRightKeyHeld() {
        return isPhysicalKeyBindDown("field_74366_z");
    }

    public static boolean isMovementKeyHeld() {
        return isForwardKeyHeld() || isBackKeyHeld() || isLeftKeyHeld() || isRightKeyHeld();
    }

    /**
     * Physical LMB from LWJGL/GLFW (Wayland-safe). Falls back to evdev only if LWJGL
     * is unavailable.
     */
    public static boolean isPhysicalLmbDown() {
        try {
            ClassLoader cl = gameLoader();
            if (cl != null) {
                Class<?> mouse = Class.forName("org.lwjgl.input.Mouse", false, cl);
                Object down = mouse.getMethod("isButtonDown", int.class).invoke(null, 0);
                if (down instanceof Boolean)
                    return (Boolean) down;
            }
        } catch (Throwable ignored) {
        }
        return gnu.client.runtime.NativeBootstrap.isLeftMouseDown();
    }

    /** Sync attack keybind from physical LMB (OpenMyau {@code KeyBindUtil.updateKeyState}). */
    public static void updateAttackKeyState() {
        setAttackKeyState(isPhysicalLmbDown());
    }

    /** One synthetic attack press via KeyBinding.onTick — no OS input injection. */
    public static void pressAttackKeyOnce() {
        try {
            Object keyBind = getObject(gameSettings(), "field_74312_F");
            if (keyBind == null)
                return;
            Object keyCode = invoke(keyBind, "func_151463_i", new Class<?>[0]);
            if (!(keyCode instanceof Integer))
                return;
            Class<?> kb = gameClass("net.minecraft.client.settings.KeyBinding");
            invokeStatic(kb, "func_74507_a", new Class<?>[] { int.class }, keyCode);
        } catch (Throwable t) {
            GnuLog.log("JAVA_ McAccess pressAttackKeyOnce error: " + t);
        }
    }

    public static boolean isKeyBindDown(String keyBindSrg) {
        try {
            Object keyBind = getObject(gameSettings(), keyBindSrg);
            if (keyBind == null)
                return false;
            Object down = invoke(keyBind, "func_151470_d", new Class<?>[0]);
            return down instanceof Boolean && (Boolean) down;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Physical keyboard/mouse state for a keybind (LWJGL/GLFW — Wayland-safe). */
    public static boolean isPhysicalKeyBindDown(String keyBindSrg) {
        try {
            Object keyBind = getObject(gameSettings(), keyBindSrg);
            if (keyBind == null)
                return false;
            Object keyCode = invoke(keyBind, "func_151463_i", new Class<?>[0]);
            if (!(keyCode instanceof Integer))
                return false;
            int code = (Integer) keyCode;
            ClassLoader cl = gameLoader();
            if (cl == null)
                return isKeyBindDown(keyBindSrg);
            if (code < 0) {
                Class<?> mouse = Class.forName("org.lwjgl.input.Mouse", false, cl);
                Object down = mouse.getMethod("isButtonDown", int.class).invoke(null, code + 100);
                return down instanceof Boolean && (Boolean) down;
            }
            Class<?> keyboard = Class.forName("org.lwjgl.input.Keyboard", false, cl);
            Object down = keyboard.getMethod("isKeyDown", int.class).invoke(null, code);
            return down instanceof Boolean && (Boolean) down;
        } catch (Throwable t) {
            return isKeyBindDown(keyBindSrg);
        }
    }

    /** Minecraft.leftClickCounter — SRG field_71429_W (1.8.9). */
    public static void clearLeftClickCounter() {
        Object mc = minecraft();
        if (mc == null)
            return;
        if (!trySetInt(mc, "field_71429_W", 0))
            trySetInt(mc, "leftClickCounter", 0);
    }

    /** Minecraft.rightClickDelayTimer — SRG field_71467_ac (1.8.9). */
    public static void clearRightClickDelay() {
        Object mc = minecraft();
        if (mc == null)
            return;
        if (!trySetInt(mc, "field_71467_ac", 0))
            trySetInt(mc, "rightClickDelayTimer", 0);
    }

    /** Reset activeItemStack (field_71074_e) and itemInUseCount (field_71072_f) to zero.
     * Ensures isUsingItem() returns false so rightClickMouse() sends C08 instead of C07
     * when re-blocking after an attack during autoblock lag windows. */
    public static void clearItemInUse(Object player) {
        if (player == null)
            return;
        if (!trySetInt(player, "field_71072_f", 0))
            trySetInt(player, "itemInUseCount", 0);
        setObject(player, "field_71074_e", null);
    }

    /** Set or clear the player's active item stack (field_71074_e) and itemInUseCount (field_71072_f).
     * When {@code active} is true, sets activeItemStack to the currently held item and
     * itemInUseCount to 72000 (simulating item-in-use). When false, resets both to null/0.
     * Matches Raven's onRenderTick: setItemInUse(isBlocking || isLagging). */
    public static void setItemInUse(Object player, boolean active) {
        if (player == null)
            return;
        if (active) {
            Object stack = invoke(player, "func_70694_bm", new Class<?>[0]);
            if (stack == null)
                return;
            setObject(player, "field_71074_e", stack);
            setInt(player, "field_71072_f", 72000);
        } else {
            clearItemInUse(player);
        }
    }

    public static boolean isUsingItem() {
        return isUsingItem(thePlayer());
    }

    public static boolean isUsingItem(Object player) {
        if (player == null)
            return false;
        return getObject(player, "field_71074_e") != null && getInt(player, "field_71072_f") > 0;
    }

    public static boolean isBlocking() {
        return isBlocking(thePlayer());
    }

    public static boolean isBlocking(Object player) {
        Object result = invoke(player, "func_70632_aY", new Class<?>[0]);
        if (result == null)
            result = invokeNamed(player, "isBlocking", new Class<?>[0]);
        return result instanceof Boolean && (Boolean) result;
    }

    public static int getHurtTime() {
        return getHurtTime(thePlayer());
    }

    public static int getHurtTime(Object entity) {
        return getInt(entity, "field_70737_aN");
    }

    public static int getMaxHurtTime(Object entity) {
        return getInt(entity, "field_70738_aO");
    }

    public static int getDeathTime(Object entity) {
        return getInt(entity, "field_70725_aQ");
    }

    public static float getHealth() {
        return getHealth(thePlayer());
    }

    public static float getHealth(Object entity) {
        Object hp = invoke(entity, "func_110143_aJ", new Class<?>[0]);
        if (hp == null)
            hp = invokeNamed(entity, "getHealth", new Class<?>[0]);
        return hp instanceof Float ? (Float) hp : 0.0f;
    }

    public static float getMaxHealth() {
        return getMaxHealth(thePlayer());
    }

    public static float getMaxHealth(Object entity) {
        Object hp = invoke(entity, "func_110138_aP", new Class<?>[0]);
        if (hp == null)
            hp = invokeNamed(entity, "getMaxHealth", new Class<?>[0]);
        return hp instanceof Float ? (Float) hp : 0.0f;
    }

    public static float getAbsorption() {
        return getAbsorption(thePlayer());
    }

    public static float getAbsorption(Object entity) {
        Object absorption = invoke(entity, "func_110139_bj", new Class<?>[0]);
        if (absorption == null)
            absorption = invokeNamed(entity, "getAbsorptionAmount", new Class<?>[0]);
        return absorption instanceof Float ? (Float) absorption : 0.0f;
    }

    public static boolean isDead() {
        return isDead(thePlayer());
    }

    public static boolean isDead(Object entity) {
        return getBool(entity, "field_70128_L");
    }

    public static boolean isAlive() {
        return isAlive(thePlayer());
    }

    public static boolean isAlive(Object entity) {
        return entity != null && !isDead(entity) && getDeathTime(entity) <= 0;
    }

    public static Object getHeldItemStack() {
        return getHeldItemStack(thePlayer());
    }

    public static Object getHeldItemStack(Object player) {
        Object stack = invoke(player, "func_70694_bm", new Class<?>[0]);
        if (stack == null)
            stack = invokeNamed(player, "getHeldItem", new Class<?>[0]);
        return stack;
    }

    public static Object getHeldItem() {
        return getHeldItem(thePlayer());
    }

    public static Object getHeldItem(Object player) {
        Object stack = getHeldItemStack(player);
        if (stack == null)
            return null;
        Object item = invoke(stack, "func_77973_b", new Class<?>[0]);
        if (item == null)
            item = invokeNamed(stack, "getItem", new Class<?>[0]);
        return item;
    }

    public static boolean isHoldingSword() {
        Object item = getHeldItem();
        Class<?> sword = gameClass("net.minecraft.item.ItemSword");
        return item != null && sword != null && sword.isInstance(item);
    }

    public static boolean isHoldingBlock() {
        Object item = getHeldItem();
        Class<?> itemBlock = gameClass("net.minecraft.item.ItemBlock");
        return item != null && itemBlock != null && itemBlock.isInstance(item);
    }

    public static boolean isInWater() {
        return isInWater(thePlayer());
    }

    public static boolean isInWater(Object entity) {
        Object result = invoke(entity, "func_70090_H", new Class<?>[0]);
        if (result == null)
            result = invokeNamed(entity, "isInWater", new Class<?>[0]);
        return result instanceof Boolean && (Boolean) result;
    }

    /** Physical RMB (LWJGL button 1). */
    public static boolean isPhysicalRmbDown() {
        try {
            ClassLoader cl = gameLoader();
            if (cl != null) {
                Class<?> mouse = Class.forName("org.lwjgl.input.Mouse", false, cl);
                Object down = mouse.getMethod("isButtonDown", int.class).invoke(null, 1);
                if (down instanceof Boolean)
                    return (Boolean) down;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Use-item key held — physical mouse/keyboard (Wayland-safe). */
    public static boolean isUseItemKeyDown() {
        if (isPhysicalKeyBindDown("field_74313_G"))
            return true;
        return isPhysicalRmbDown();
    }

    /** Minecraft.clickMouse — SRG func_147116_af. */
    public static void clickMouse() {
        Object mc = minecraft();
        if (mc == null)
            return;
        invoke(mc, "func_147116_af", new Class<?>[0]);
    }

    /** OpenMyau NoHitDelay: zero counter immediately before clickMouse runs. */
    public static void clickMouseNoDelay() {
        clearLeftClickCounter();
        clickMouse();
    }

    /** EntityLivingBase.isSwingInProgress — SRG field_82175_bq (1.8.9 stable_22). */
    public static boolean isSwingInProgress(Object player) {
        if (player == null)
            return false;
        if (tryGetBool(player, "field_82175_bq"))
            return true;
        return tryGetBool(player, "isSwingInProgress");
    }

    public static boolean isSwingInProgress() {
        return isSwingInProgress(thePlayer());
    }

    private static boolean tryGetBool(Object owner, String fieldName) {
        if (owner == null)
            return false;
        Field f = findField(owner.getClass(), fieldName);
        if (f == null)
            return false;
        try {
            return f.getBoolean(owner);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean trySetInt(Object owner, String fieldName, int value) {
        if (owner == null)
            return false;
        Field f = findField(owner.getClass(), fieldName);
        if (f == null)
            return false;
        try {
            f.setInt(owner, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setKeyBindState(String keyBindSrg, boolean pressed) {
        try {
            Object keyBind = getObject(gameSettings(), keyBindSrg);
            if (keyBind == null)
                return;
            Object keyCode = invoke(keyBind, "func_151463_i", new Class<?>[0]);
            if (keyCode instanceof Integer) {
                Class<?> kb = gameClass("net.minecraft.client.settings.KeyBinding");
                invokeStatic(kb, "func_74510_a",
                        new Class<?>[] { int.class, boolean.class }, keyCode, pressed);
            }
        } catch (Throwable t) {
            GnuLog.log("JAVA_ McAccess setKeyBindState error: " + t);
        }
    }

    /** Press/release sprint keybind (OpenMyau Sprint). keyBindSprint=field_151444_V. */
    public static void setSprintKeyState(boolean pressed) {
        setKeyBindState("field_151444_V", pressed);
    }

    /** Client sprint flag ({@code EntityLivingBase.isSprinting}). */
    public static boolean isClientSprinting(Object player) {
        if (player == null)
            return false;
        Object sprinting = invoke(player, "func_70051_ag", new Class<?>[0]);
        if (sprinting instanceof Boolean)
            return (Boolean) sprinting;
        return getBool(player, "field_70151_cx");
    }

    public static boolean isClientSprinting() {
        return isClientSprinting(thePlayer());
    }

    /** {@code EntityLivingBase.setSprinting} — SRG {@code func_70031_b}. */
    public static void setClientSprinting(Object player, boolean sprinting) {
        if (player == null)
            return;
        invoke(player, "func_70031_b", new Class<?>[] { boolean.class }, sprinting);
    }

    /** EntityPlayerSP.serverSprintState — what the server last ack'd (1.8.9). */
    public static boolean getServerSprintState(Object player) {
        return getBool(player, "field_175171_bO");
    }

    public static boolean getServerSprintState() {
        return getServerSprintState(thePlayer());
    }

    public static void setServerSprintState(Object player, boolean sprinting) {
        setBool(player, "field_175171_bO", sprinting);
    }

    /**
     * Send {@code C0BPacketEntityAction} sprint start/stop and mirror
     * {@code serverSprintState} (raven / OpenMyau packet sprint sync).
     */
    public static void sendSprintActionPacket(Object player, boolean startSprinting) {
        if (player == null)
            return;
        if (getServerSprintState(player) == startSprinting)
            return;
        try {
            Class<?> actionEnum = gameClass("net.minecraft.network.play.client.C0BPacketEntityAction$Action");
            if (actionEnum == null)
                return;
            String actionName = startSprinting ? "START_SPRINTING" : "STOP_SPRINTING";
            Object action = null;
            for (Object constant : actionEnum.getEnumConstants()) {
                if (constant != null && actionName.equals(constant.toString())) {
                    action = constant;
                    break;
                }
            }
            if (action == null)
                return;

            Class<?> entityCls = gameClass("net.minecraft.entity.Entity");
            Object packet = newInstance("net.minecraft.network.play.client.C0BPacketEntityAction",
                    new Class<?>[] { entityCls, actionEnum }, player, action);
            if (packet != null) {
                gnu.client.runtime.packet.PacketUtil.sendPacket(packet);
                setServerSprintState(player, startSprinting);
            }
        } catch (Throwable t) {
            GnuLog.log("JAVA_ McAccess sendSprintActionPacket error: " + t);
        }
    }

    /**
     * Send C07PacketPlayerDigging with RELEASE_USE_ITEM action — tells the server
     * we stopped using the item (sword block / shield / eating). Used by AutoBlockModule
     * before attack C02 to clear Grim's isSlowedByUsingItem state, bypassing
     * MultiActionsA (attack_while_using).
     *
     * <p>The C07 goes directly to NetworkManager.sendPacket via PacketUtil, bypassing
     * our own PacketEvents listeners (DISPATCHING flag prevents re-entrancy).
     *
     * <p>Safe to call even when not using an item — the server ignores the packet.
     */
    public static void sendReleaseUseItem(Object player) {
        if (player == null) return;
        try {
            Class<?> actionEnum = gameClass("net.minecraft.network.play.client.C07PacketPlayerDigging$Action");
            if (actionEnum == null) return;
            Object action = null;
            for (Object constant : actionEnum.getEnumConstants()) {
                if (constant != null && "RELEASE_USE_ITEM".equals(constant.toString())) {
                    action = constant;
                    break;
                }
            }
            if (action == null) return;

            Class<?> blockPosCls = gameClass("net.minecraft.util.BlockPos");
            Object origin = newInstance("net.minecraft.util.BlockPos",
                    new Class<?>[]{int.class, int.class, int.class}, 0, 0, 0);

            Class<?> facingEnum = gameClass("net.minecraft.util.EnumFacing");
            Object down = null;
            for (Object constant : facingEnum.getEnumConstants()) {
                if (constant != null && "DOWN".equals(constant.toString())) {
                    down = constant;
                    break;
                }
            }
            if (down == null) down = facingEnum.getEnumConstants()[0];

            Object packet = newInstance("net.minecraft.network.play.client.C07PacketPlayerDigging",
                    new Class<?>[]{actionEnum, blockPosCls, facingEnum}, action, origin, down);
            if (packet != null) {
                gnu.client.runtime.packet.PacketUtil.sendPacket(packet);
            }
        } catch (Throwable t) {
            GnuLog.log("JAVA_ McAccess sendReleaseUseItem error: " + t);
        }
    }

    public static void setSneakInput(Object player, boolean sneak) {
        try {
            if (player == null)
                return;

            Object movInput = getObject(player, "field_71158_b");
            if (movInput == null)
                movInput = getObject(player, "field_71159_q"); // fallback (not in stable_22 CSV; CSV says field_71159_c=mc)
            if (movInput != null)
                setBool(movInput, "field_78899_d", sneak); // sneak field on MovementInput

            Object keyBindSneak = getObject(gameSettings(), "field_74311_E");
            if (keyBindSneak != null) {
                Object keyCode = invoke(keyBindSneak, "func_151463_i", new Class<?>[0]); // getKeyCode
                if (keyCode instanceof Integer) {
                    Class<?> kb = gameClass("net.minecraft.client.settings.KeyBinding");
                    invokeStatic(kb, "func_74510_a",
                            new Class<?>[] { int.class, boolean.class }, keyCode, sneak);
                }
            }
        } catch (Throwable t) {
            GnuLog.log("JAVA_ McAccess setSneakInput error: " + t);
        }
    }

    // ===================== script-facing helpers (raven-bS style accessors) =====================

    private static final java.util.Random SHARED_RANDOM = new java.util.Random();

    public static double getMotionX() {
        Object player = thePlayer();
        if (player == null)
            return 0.0;
        return getDouble(player, "field_70159_w");
    }

    public static double getMotionY() {
        Object player = thePlayer();
        if (player == null)
            return 0.0;
        return getDouble(player, "field_70181_x");
    }

    public static double getMotionZ() {
        Object player = thePlayer();
        if (player == null)
            return 0.0;
        return getDouble(player, "field_70179_y");
    }

    public static float getYaw() {
        Object player = thePlayer();
        if (player == null)
            return 0.0f;
        return getFloat(player, "field_70177_z");
    }

    public static float getPitch() {
        Object player = thePlayer();
        if (player == null)
            return 0.0f;
        return getFloat(player, "field_70125_A");
    }

    public static boolean isOnGround() {
        Object player = thePlayer();
        if (player == null)
            return false;
        return getBool(player, "field_70122_E");
    }

    public static boolean isSneaking() {
        return isSneaking(thePlayer());
    }

    public static void setRotation(float yaw, float pitch) {
        Object player = thePlayer();
        if (player == null)
            return;
        float clampedPitch = Math.max(-90.0f, Math.min(90.0f, pitch));
        setFloat(player, "field_70177_z", yaw);
        setFloat(player, "field_70125_A", clampedPitch);
    }

    public static void setMotion(double x, double y, double z) {
        Object player = thePlayer();
        if (player == null)
            return;
        setDouble(player, "field_70159_w", x);
        setDouble(player, "field_70181_x", y);
        setDouble(player, "field_70179_y", z);
    }

    /**
     * Ray-cast from the local player's eyes at the given yaw/pitch, returning the
     * {@code MovingObjectPosition} (or null). Generalized from
     * {@code BridgeAssistModule.rayCast}; mirrors its
     * {@code World.rayTraceBlocks(start, end, false, false, false)} call
     * (SRG {@code func_147447_a}) and {@code Vec3} construction. Uses
     * {@code Math.sin/cos} rather than {@code MathHelper} to keep this file free
     * of compile-time {@code net.minecraft.*} references (see file header).
     */
    public static Object raycastBlocks(double distance, float yaw, float pitch) {
        Object player = thePlayer();
        if (player == null)
            return null;
        Object world = theWorld();
        if (world == null)
            return null;

        double ex = entityPosX(player);
        double ey = entityPosY(player);
        double ez = entityPosZ(player);
        Object eh = invoke(player, "func_70047_e", new Class<?>[0]);
        if (eh instanceof Float)
            ey += (Float) eh;
        else
            ey += 1.62;

        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        float cosPitch = (float) Math.cos(pitchRad);
        float dx = -(float) Math.sin(yawRad) * cosPitch;
        float dy = -(float) Math.sin(pitchRad);
        float dz = (float) Math.cos(yawRad) * cosPitch;

        double endX = ex + dx * distance;
        double endY = ey + dy * distance;
        double endZ = ez + dz * distance;

        Class<?> vec3Cls = gameClass("net.minecraft.util.Vec3");
        if (vec3Cls == null)
            return null;
        Object startVec = newInstance("net.minecraft.util.Vec3",
                new Class<?>[] { double.class, double.class, double.class }, ex, ey, ez);
        Object endVec = newInstance("net.minecraft.util.Vec3",
                new Class<?>[] { double.class, double.class, double.class }, endX, endY, endZ);
        if (startVec == null || endVec == null)
            return null;

        return invoke(world, "func_147447_a",
                new Class<?>[] { vec3Cls, vec3Cls, boolean.class, boolean.class, boolean.class },
                startVec, endVec, false, false, false);
    }

    /**
     * {@code InventoryPlayer.getStackInSlot(int)} — SRG {@code func_70301_a}.
     *
     * @param playerInventory the {@code InventoryPlayer} object (NOT the player —
     *                        resolve it first via {@code getObject(player, "field_71071_by")})
     */
    public static Object getStackInSlot(Object playerInventory, int slot) {
        if (playerInventory == null)
            return null;
        return invoke(playerInventory, "func_70301_a",
                new Class<?>[] { int.class }, slot);
    }

    /**
     * Current hotbar slot index — {@code InventoryPlayer.currentItem}
     * (SRG {@code field_70961_c}). Resolves the player's inventory via
     * {@code EntityPlayer.inventory} (SRG {@code field_71071_by}) first.
     * Returns {@code -1} if the player or inventory is null.
     */
    public static int getHotbarSlot(Object player) {
        if (player == null)
            return -1;
        Object inv = getObject(player, "field_71071_by");
        if (inv == null)
            return -1;
        return getInt(inv, "field_70961_c");
    }

    /**
     * {@code World.getBlockState(BlockPos)} — SRG {@code func_180495_p}.
     * Constructs the {@code BlockPos} the same way
     * {@code BridgeAssistModule.createBlockPos/isAirBlock} do.
     */
    public static Object getBlockState(Object world, int x, int y, int z) {
        if (world == null)
            return null;
        Object pos = newInstance("net.minecraft.util.BlockPos",
                new Class<?>[] { int.class, int.class, int.class }, x, y, z);
        if (pos == null)
            return null;
        Class<?> blockPosCls = gameClass("net.minecraft.util.BlockPos");
        if (blockPosCls == null)
            return null;
        return invoke(world, "func_180495_p", new Class<?>[] { blockPosCls }, pos);
    }

    /** Inclusive random int in {@code [min, max]} using the shared {@link #SHARED_RANDOM}. */
    public static int randomInt(int min, int max) {
        if (max < min)
            return min;
        return min + SHARED_RANDOM.nextInt(max - min + 1);
    }

    /** Random double in {@code [min, max)} using the shared {@link #SHARED_RANDOM}. */
    public static double randomDouble(double min, double max) {
        if (max < min)
            return min;
        return min + SHARED_RANDOM.nextDouble() * (max - min);
    }
}
