package gnu.client.module;

/**
 * What happens when a module's bound key is pressed.
 */
public enum KeybindAction {
    /** Default — toggle module enabled state. */
    TOGGLE,
    /** Open/close the native ClickGUI menu (no enable state). */
    MENU
}
