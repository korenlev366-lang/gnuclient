package gnu.client.module.modules.combat;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.modules.combat.HitSelectModule;
import gnu.client.module.setting.BoolSetting;
import gnu.client.module.setting.SliderSetting;
import gnu.client.runtime.NativeBootstrap;
import gnu.client.runtime.mc.McAccess;

import java.util.Random;

/**
 * Auto-clicks left mouse at a randomized CPS. Ported from RainClient
 * {@code AutoClicker}, but delegates click injection to native X11
 * {@code XSendEvent} on a dedicated worker thread started in {@link #onEnable()}
 * and stopped in {@link #onDisable()}.
 *
 * Also supports clicking in inventory/container GUIs (merged from the removed
 * InvClick module) via {@code PlayerControllerMP.windowClick} reflection when
 * the "Inventory" setting is enabled.
 */
public final class AutoClickerModule extends Module {

    private static final int CLICK_HISTORY = 32;

    private final BoolSetting randomize = addSetting(new BoolSetting("Randomize", true));
    private final BoolSetting breakBlocks = addSetting(new BoolSetting("Break Blocks", true));
    private final BoolSetting inventoryClick = addSetting(new BoolSetting("Inventory", false));
    private final SliderSetting minCps = addSetting(new SliderSetting("MinCPS", 8.0f, 1.0f, 20.0f));
    private final SliderSetting maxCps = addSetting(new SliderSetting("MaxCPS", 14.0f, 1.0f, 20.0f));

    private final Random random = new Random();

    private volatile boolean running;
    private Thread worker;
    private volatile boolean jniLinkErrorLogged = false;

    // Worker-thread-only humanization state.
    private int clickCount = 0;
    private int fatigueThreshold = 60;
    private boolean fatigueActive = false;
    private int fatigueTicksLeft = 0;
    private double cpsDrift = 1.0;
    private long nextDriftAtMs = 0L;

    // Inventory-specific state (worker thread only).
    private long inventoryNextClickMs = 0L;

    private final long[] clickTimes = new long[CLICK_HISTORY];
    private int clickTimeIdx = 0;

    public AutoClickerModule() {
        super("AutoClicker", "Automatically clicks at a set CPS", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        running = true;
        jniLinkErrorLogged = false;
        clickCount = 0;
        fatigueActive = false;
        cpsDrift = 1.0;
        nextDriftAtMs = 0L;
        inventoryNextClickMs = 0L;
        worker = new Thread(this::loop, "GnuAutoClicker");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void onDisable() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private void loop() {
        while (running) {
            try {
                // --- Inventory click path ---
                if (inventoryClick.getValue() && tryInventoryClick()) {
                    // inventoryClick handled the frame; sleep and continue
                    long interval = rollInterval();
                    if (!sleepMs(interval))
                        break;
                    continue;
                }

                // --- Normal world click path ---
                long interval = rollInterval();
                Object player = McAccess.thePlayer();
                boolean lmbDown = readLeftMouseDown();

                if (gatesPass(player, lmbDown)) {
                    if (randomize.getValue() && random.nextFloat() < rollSkipChance()) {
                        if (!sleepMs(interval + 30L + random.nextInt(120)))
                            break;
                        continue;
                    }

                    if (HitSelectModule.shouldBlockClick()) {
                        if (!sleepMs(interval))
                            break;
                        continue;
                    }
                    long hold = rollHoldMs(interval);
                    NativeBootstrap.nativeClick((int) hold);
                    recordClick(System.currentTimeMillis());
                    fatigueAccount();
                    AimAssistModule.lastClickMs = System.currentTimeMillis();

                    long elapsed = hold;
                    long sleep = Math.max(8L, interval - elapsed);
                    if (!sleepMs(sleep))
                        break;
                } else if (!sleepMs(interval)) {
                    break;
                }
            } catch (Throwable t) {
                if (t instanceof UnsatisfiedLinkError)
                    jniLinkErrorLogged = true;
                break;
            }
        }
    }

    /**
     * Inventory click loop iteration. Checks for a container GUI and performs
     * {@code windowClick} on the hovered slot at the configured CPS rate.
     *
     * @return true if an inventory was handled (caller should skip normal click path)
     */
    private boolean tryInventoryClick() {
        Object player = McAccess.thePlayer();
        if (player == null)
            return false;

        Object screen = McAccess.currentScreen();
        if (screen == null)
            return false;

        if (!readLeftMouseDown())
            return false;

        long now = System.currentTimeMillis();
        if (inventoryNextClickMs == 0L) {
            inventoryNextClickMs = now + 50L; // small initial delay
        }
        if (now < inventoryNextClickMs)
            return true; // still in cooldown — don't fall through to world click

        // Find hovered slot — field_147006_u = theSlot (hovered Slot) on GuiContainer.
        // We detect "is this a container GUI?" by checking if this field exists,
        // which works on both SRG and obfuscated runtimes (field_147006_u / notch d).
        Object slot = McAccess.getObject(screen, "field_147006_u");
        if (slot == null) {
            // Not a container GUI — don't fall through to world click either,
            // but return false so the caller sleeps and retries.
            return false;
        }

        int slotNumber = McAccess.getInt(slot, "field_75222_d"); // slotNumber
        if (slotNumber < 0)
            return false;

        Object container = McAccess.getObject(player, "field_71069_bz"); // openContainer
        if (container == null)
            return false;
        int windowId = McAccess.getInt(container, "field_75152_c"); // windowId

        Object playerController = McAccess.playerController();
        if (playerController == null)
            return false;

        // Perform one windowClick at current CPS timing
        int clicksThisTick = 0;
        while (inventoryNextClickMs <= now) {
            boolean shiftHeld = NativeBootstrap.isShiftDown();
            int clickMode = shiftHeld ? 1 : 0;
            windowClick(playerController, windowId, slotNumber, 0, clickMode, player);
            clicksThisTick++;
            inventoryNextClickMs += rollInventoryInterval();
        }

        if (clicksThisTick > 0) {
            fatigueAccount();
            recordClick(now);
            AimAssistModule.lastClickMs = now;
        }

        return true; // handled inventory frame
    }

    /**
     * Roll a slightly gentler interval for inventory clicks to avoid
     * desync — same CPS range but with less jitter.
     */
    private long rollInventoryInterval() {
        float lo = minCps.getValue();
        float hi = maxCps.getValue();
        if (hi < lo)
            hi = lo;
        double cps = lo + random.nextDouble() * (hi - lo);
        double interval = 1000.0 / Math.max(1.0, cps);
        if (randomize.getValue()) {
            interval += random.nextDouble() * 20.0 - 10.0;
        }
        return Math.max(50L, (long) interval);
    }

    private boolean gatesPass(Object player, boolean lmbDown) {
        if (player == null)
            return false;
        Object screen = McAccess.currentScreen();
        if (screen != null)
            return false;

        if (!lmbDown)
            return false;

        if (breakBlocks.getValue()) {
            Object mop = McAccess.objectMouseOver();
            Object type = McAccess.getObject(mop, "field_72313_a");
            if (type != null && type.toString().contains("BLOCK"))
                return false;
        }
        return true;
    }

    private static boolean readLeftMouseDown() {
        return NativeBootstrap.isLeftMouseDown();
    }

    private float rollSkipChance() {
        return 0.02f + random.nextFloat() * 0.04f;
    }

    private long rollInterval() {
        float lo = minCps.getValue();
        float hi = maxCps.getValue();
        if (hi < lo)
            hi = lo;

        refreshCpsDrift();

        double cps = lo + random.nextDouble() * (hi - lo);
        cps *= cpsDrift;
        double interval = 1000.0 / Math.max(1.0, cps);

        if (randomize.getValue()) {
            double jitter = 1.0 + (random.nextGaussian() * 0.18);
            interval *= Math.max(0.65, Math.min(1.45, jitter));
            jitter = 1.0 + (random.nextGaussian() * 0.11);
            interval *= Math.max(0.78, Math.min(1.28, jitter));
            interval += random.nextDouble() * 24.0 - 12.0;
        }

        if (fatigueActive)
            interval *= 1.22 + random.nextDouble() * 0.62;

        if (clickCount > 0 && clickCount % (14 + random.nextInt(19)) == 0)
            interval += 35.0 + random.nextDouble() * 110.0;

        double minMs = 1000.0 / Math.max(hi, lo);
        double maxMs = 1000.0 / Math.min(lo, hi);
        interval = Math.max(minMs * 0.88, Math.min(maxMs * 1.22, interval));
        return Math.max(42L, (long) interval);
    }

    private void refreshCpsDrift() {
        long now = System.currentTimeMillis();
        if (now < nextDriftAtMs)
            return;
        cpsDrift = 0.88 + random.nextDouble() * 0.24;
        nextDriftAtMs = now + 900L + random.nextInt(1400);
    }

    private long rollHoldMs(long interval) {
        double frac = 0.06 + random.nextDouble() * 0.22;
        return Math.max(4L, (long) (interval * frac));
    }

    private void fatigueAccount() {
        clickCount++;
        if (fatigueActive) {
            if (--fatigueTicksLeft <= 0)
                fatigueActive = false;
        } else if (clickCount >= fatigueThreshold) {
            fatigueActive = true;
            fatigueTicksLeft = 18 + random.nextInt(26);
            fatigueThreshold = 38 + random.nextInt(44);
            clickCount = 0;
        }
    }

    private void recordClick(long now) {
        clickTimes[clickTimeIdx % CLICK_HISTORY] = now;
        clickTimeIdx++;
    }

    private boolean sleepMs(long ms) {
        if (ms <= 0)
            return running;
        try {
            Thread.sleep(ms);
            return running;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /**
     * Invoke {@code PlayerControllerMP.windowClick(windowId, slotId, clickedButton, mode, player)}
     * via reflection.
     *
     * Uses {@link McAccess#gameClass(String)} for the EntityPlayer param type
     * instead of {@code player.getClass()} — the declared method parameter is
     * {@code EntityPlayer}, not {@code EntityPlayerSP}, and Java reflection's
     * {@code getDeclaredMethod} requires an exact type match.
     */
    private static void windowClick(Object playerController, int windowId, int slotId,
                                    int clickedButton, int mode, Object player) {
        // Resolve the EntityPlayer base class — the method declares EntityPlayer,
        // not EntityPlayerSP, and getDeclaredMethod requires exact parameter type match.
        Class<?> entityPlayerClass = McAccess.gameClass("net.minecraft.entity.player.EntityPlayer");
        if (entityPlayerClass == null)
            entityPlayerClass = player.getClass(); // last-resort fallback
        
        Class<?>[] paramTypes = new Class<?>[] {
                int.class, int.class, int.class, int.class,
                entityPlayerClass
        };
        Object result = McAccess.invoke(playerController, "func_78753_a",
                paramTypes, windowId, slotId, clickedButton, mode, player);
        if (result == null) {
            // Fallback: try by deobf name (Lunar/Badlion may expose it)
            McAccess.invokeNamed(playerController, "windowClick",
                    paramTypes, windowId, slotId, clickedButton, mode, player);
        }
    }
}
