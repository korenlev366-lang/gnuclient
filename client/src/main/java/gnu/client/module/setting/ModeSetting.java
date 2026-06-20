package gnu.client.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Collections;
import java.util.List;

public final class ModeSetting extends Setting<Integer> {

    private final List<String> modes;

    public ModeSetting(String name, int index, List<String> modes) {
        super(name, index);
        this.modes = Collections.unmodifiableList(modes);
    }

    public List<String> getModes() {
        return modes;
    }

    public String getCurrentMode() {
        int idx = getValue();
        if (idx < 0 || idx >= modes.size())
            return modes.isEmpty() ? "" : modes.get(0);
        return modes.get(idx);
    }

    @Override
    public JsonElement serialize() {
        return new JsonPrimitive(getValue());
    }

    @Override
    public void deserialize(JsonElement element) {
        setValue(element.getAsInt());
    }
}
