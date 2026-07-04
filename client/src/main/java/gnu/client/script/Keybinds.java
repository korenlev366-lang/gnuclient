package gnu.client.script;

import gnu.client.runtime.NativeBootstrap;
import gnu.client.runtime.mc.McAccess;

/**
 * Script-facing {@code keybinds} accessor — stateless singleton facade over
 * {@link NativeBootstrap} (native evdev for LMB) and {@link McAccess}
 * (LWJGL RMB, vanilla {@code sendUseItem}).
 *
 * <p>Mouse-button dispatch:
 * <ul>
 *   <li>button {@code 0} (LMB) → {@link NativeBootstrap#isLeftMouseDown()} (native evdev)</li>
 *   <li>button {@code 1} (RMB) → {@link McAccess#isPhysicalRmbDown()} (LWJGL button 1)</li>
 *   <li>any other index → {@code false}</li>
 * </ul>
 */
public final class Keybinds {

    public static final Keybinds INSTANCE = new Keybinds();

    private Keybinds() {}

    /**
     * Physical mouse-button state. {@code button} follows LWJGL conventions
     * (0 = left, 1 = right). Returns {@code false} for unknown indices.
     */
    public boolean isMouseDown(int button) {
        if (button == 0)
            return NativeBootstrap.isLeftMouseDown();
        if (button == 1)
            return McAccess.isPhysicalRmbDown();
        return false;
    }

    /**
     * Synthesize a vanilla right-click (item-use / block-place) via
     * {@code PlayerControllerMP.sendUseItem}. Returns {@code true} if the
     * server accepted the use, {@code false} on any failure or no held item.
     */
    public boolean rightClick() {
        return McAccess.sendUseItem();
    }
}
