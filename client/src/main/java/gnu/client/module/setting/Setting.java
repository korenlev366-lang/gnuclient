package gnu.client.module.setting;

import com.google.gson.JsonElement;

public abstract class Setting<T> {

    private final String name;
    private T value;
    private boolean visible = true;

    protected Setting(String name, T value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public T getValue() {
        return value;
    }

    public void setValue(T value) {
        this.value = value;
    }

    /** Whether this setting should be shown in the GUI (Raven's guiUpdate pattern). */
    public boolean isVisible() {
        return visible;
    }

    /** Set visibility — hidden settings are not rendered in the GUI. */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public abstract JsonElement serialize();

    public abstract void deserialize(JsonElement element);
}
