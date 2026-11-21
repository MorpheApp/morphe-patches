/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5107
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.glassmenus;

import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.Logger;

/**
 * Finds the drawables a Litho host view draws itself, such as the background of a component that is not a view.
 * <p>
 * A Litho host ({@code com.facebook.litho.ComponentHost}) keeps its mounted content in sparse arrays of mount items,
 * and draws the drawables among them in {@code dispatchDraw}. These are not the background of a view, so they are
 * found in the fields of the host: a field holds a sparse array, which holds an array of mount items, which hold
 * the content. The names of these classes and fields are obfuscated, so they are found by their types only.
 */
final class LithoDrawables {

    private static final String COMPONENT_HOST = "com.facebook.litho.ComponentHost";

    @Nullable
    private static Class<?> componentHostClass;

    private static final Map<Class<?>, List<Field>> fieldsByClass = new IdentityHashMap<>();

    /**
     * @return If the view is a Litho host. The host class is found by its name among the classes of the view.
     */
    static boolean isHost(View view) {
        Class<?> host = componentHostClass;
        if (host != null) return host.isInstance(view);

        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (COMPONENT_HOST.equals(type.getName())) {
                componentHostClass = type;
                return true;
            }
        }
        return false;
    }

    /**
     * @return The drawables the host draws itself.
     */
    static List<Drawable> drawablesOf(View host) {
        List<Drawable> drawables = new ArrayList<>();
        if (!isHost(host)) return drawables;
        Class<?> hostClass = componentHostClass;

        Set<Object> mountItems = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            for (Field field : fieldsOf(hostClass)) {
                Object value = field.get(host);
                if (value == null) continue;
                if (value instanceof Collection<?> collection) {
                    // A list of mount items.
                    mountItems.addAll(collection);
                } else if (!isPlatformClass(value.getClass())) {
                    // A sparse array of mount items: its values are an object array.
                    for (Field arrayField : fieldsOf(value.getClass())) {
                        if (arrayField.get(value) instanceof Object[] array) {
                            Collections.addAll(mountItems, array);
                        }
                    }
                }
            }
            for (Object item : mountItems) {
                if (item == null || item instanceof Drawable || isPlatformClass(item.getClass())) continue;
                // The content of the mount item.
                for (Field contentField : fieldsOf(item.getClass())) {
                    if (contentField.get(item) instanceof Drawable drawable && drawable.getCallback() == host) {
                        drawables.add(drawable);
                    }
                }
            }
        } catch (Throwable ex) {
            Logger.printDebug(() -> "Litho drawables not found: " + ex);
        }
        return drawables;
    }

    static boolean isPlatformClass(Class<?> type) {
        final String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.")
                || name.startsWith("kotlin.") || name.startsWith("dalvik.") || type.isPrimitive();
    }

    /**
     * @return The object fields declared by the class and its superclasses of the app.
     */
    static List<Field> fieldsOf(Class<?> type) {
        List<Field> fields = fieldsByClass.get(type);
        if (fields != null) return fields;

        fields = new ArrayList<>();
        for (Class<?> current = type; current != null && !isPlatformClass(current);
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    fields.add(field);
                } catch (RuntimeException ex) {
                    Logger.printDebug(() -> "Field is not accessible: " + field, ex);
                }
            }
        }
        fieldsByClass.put(type, fields);
        return fields;
    }

    private LithoDrawables() {
    }
}
