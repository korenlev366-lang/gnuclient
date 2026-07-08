package gnu.client.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class SliderSetting extends Setting<Float> {

    private final float min;
    private final float max;
    private final float step;

    public SliderSetting(String name, float value, float min, float max) {
        this(name, value, min, max, 0.0f);
    }

    public SliderSetting(String name, float value, float min, float max, float step) {
        super(name, value);
        this.min = min;
        this.max = max;
        this.step = step;
        setValue(value);
    }

    public float getMin() {
        return min;
    }

    public float getMax() {
        return max;
    }

    /** Increment for discrete steps; {@code 0} = continuous slider. */
    public float getStep() {
        return step;
    }

    @Override
    public void setValue(Float value) {
        super.setValue(snap(value));
    }

    private float snap(float value) {
        float v = Math.max(min, Math.min(max, value));
        if (step > 0.0f) {
            v = min + Math.round((v - min) / step) * step;
            v = Math.max(min, Math.min(max, v));
        }
        return v;
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
