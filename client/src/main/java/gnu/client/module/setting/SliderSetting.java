package gnu.client.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class SliderSetting extends Setting<Float> {

    private final float min;
    private final float max;

    public SliderSetting(String name, float value, float min, float max) {
        super(name, value);
        this.min = min;
        this.max = max;
    }

    public float getMin() {
        return min;
    }

    public float getMax() {
        return max;
    }

    @Override
    public JsonElement serialize() {
        return new JsonPrimitive(getValue());
    }

    @Override
    public void deserialize(JsonElement element) {
        setValue(element.getAsFloat());
    }
}
