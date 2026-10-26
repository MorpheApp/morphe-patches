package app.morphe.extension.youtube.niconicocomments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public class NiconicoSettingsMigrationTest {
    private static final String ENABLED = "morphe_niconico_comments_enabled";
    private static final String FONT = "morphe_niconico_comments_font_size";
    private static final String OPACITY = "morphe_niconico_comments_opacity";

    @Test
    public void migratesValuesToTypedSettingStorageAndKeepsLegacyData() {
        Map<String, Object> legacy = new HashMap<>(Map.of(
                "overlay_enabled", false, "font_size_sp", 24, "opacity_percent", 80));
        Map<String, Object> current = new HashMap<>();

        assertTrue(migrate(legacy, current));
        assertEquals(false, current.get(ENABLED));
        assertEquals("24", current.get(FONT));
        assertEquals("80", current.get(OPACITY));
        assertEquals(Map.of("overlay_enabled", false, "font_size_sp", 24, "opacity_percent", 80), legacy);
    }

    @Test
    public void preservesExistingOfficialValuesIncludingExplicitDefaults() {
        Map<String, Object> current = new HashMap<>(Map.of(ENABLED, true, FONT, "20", OPACITY, "100"));
        migrate(Map.of("overlay_enabled", false, "font_size_sp", 32, "opacity_percent", 50), current);
        assertEquals(true, current.get(ENABLED));
        assertEquals("20", current.get(FONT));
        assertEquals("100", current.get(OPACITY));
    }

    @Test
    public void doesNotRestoreOldValuesAfterOfficialSettingIsResetToDefault() {
        Map<String, Object> legacy = Map.of("overlay_enabled", false, "font_size_sp", 32);
        Map<String, Object> current = new HashMap<>();
        migrate(legacy, current);
        current.remove(ENABLED);
        current.remove(FONT);
        assertFalse(migrate(legacy, current));
        assertFalse(current.containsKey(ENABLED));
        assertFalse(current.containsKey(FONT));
    }

    @Test
    public void clampsLegacyValuesBeforeMigration() {
        Map<String, Object> current = new HashMap<>();
        migrate(Map.of("font_size_sp", 1000, "opacity_percent", -10), current);
        assertEquals("40", current.get(FONT));
        assertEquals("10", current.get(OPACITY));
    }

    @Test
    public void ignoresMalformedLegacyValues() {
        Map<String, Object> current = new HashMap<>();
        migrate(Map.of("overlay_enabled", "false", "font_size_sp", "24", "opacity_percent", false), current);
        assertFalse(current.containsKey(ENABLED));
        assertFalse(current.containsKey(FONT));
        assertFalse(current.containsKey(OPACITY));
    }

    private static boolean migrate(Map<String, ?> legacy, Map<String, Object> current) {
        return NiconicoCommentsController.migratePreferences(preferences(legacy), preferences(current));
    }

    @SuppressWarnings("unchecked")
    private static SharedPreferences preferences(Map<String, ?> values) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getAll" -> new HashMap<>(values);
                        case "contains" -> values.containsKey(args[0]);
                        case "getBoolean" -> values.containsKey(args[0]) ? values.get(args[0]) : args[1];
                        case "edit" -> {
                            Map<String, Object> pending = new HashMap<>();
                            yield Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                    new Class<?>[]{SharedPreferences.Editor.class}, (editor, operation, parameters) -> {
                                        switch (operation.getName()) {
                                            case "putBoolean", "putString" -> {
                                                pending.put((String) parameters[0], parameters[1]);
                                                return editor;
                                            }
                                            case "apply" -> {
                                                ((Map<String, Object>) values).putAll(pending);
                                                return null;
                                            }
                                            default -> throw new UnsupportedOperationException(operation.getName());
                                        }
                                    });
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }
}
