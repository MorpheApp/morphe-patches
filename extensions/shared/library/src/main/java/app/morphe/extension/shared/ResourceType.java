package app.morphe.extension.shared;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

public enum ResourceType {
    ANIM("anim"),
    ANIMATOR("animator"),
    ARRAY("array"),
    ATTR("attr"),
    BOOL("bool"),
    COLOR("color"),
    DIMEN("dimen"),
    DRAWABLE("drawable"),
    FONT("font"),
    FRACTION("fraction"),
    ID("id"),
    INTEGER("integer"),
    INTERPOLATOR("interpolator"),
    LAYOUT("layout"),
    MENU("menu"),
    MIPMAP("mipmap"),
    NAVIGATION("navigation"),
    PLURALS("plurals"),
    RAW("raw"),
    STRING("string"),
    STYLE("style"),
    STYLEABLE("styleable"),
    TRANSITION("transition"),
    VALUES("values"),
    XML("xml");

    private static final Map<String, ResourceType> VALUE_MAP;

    static {
        ResourceType[] values = values();
        VALUE_MAP = new HashMap<>(2 * values.length);

        for (ResourceType type : values) {
            VALUE_MAP.put(type.type, type);
        }
    }

    public final String type;

    public static ResourceType fromValue(String value) {
        ResourceType type = VALUE_MAP.get(value);
        if (type == null) {
            throw new IllegalArgumentException("Unknown resource type: " + value);
        }
        return type;
    }

    ResourceType(String type) {
        this.type = type;
    }
}
