package gnu.client.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class BoolSetting extends Setting<Boolean> {

    public BoolSetting(String name, boolean value) {
        super(name, value);
    }

    @Override
    public JsonElement serialize() {
        return new JsonPrimitive(getValue());
    }

    @Override
    public void deserialize(JsonElement element) {
        setValue(element.getAsBoolean());
    }
}
