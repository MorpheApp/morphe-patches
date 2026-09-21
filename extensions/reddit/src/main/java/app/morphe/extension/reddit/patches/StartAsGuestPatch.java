/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3109
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Utils;

/** Starts Reddit with its native logged-out browsing session. */
@SuppressWarnings("unused")
public final class StartAsGuestPatch {
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean();

    private StartAsGuestPatch() {
    }

    /**
     * Injection point.
     */
    public static void schedule() {
        if (!SCHEDULED.compareAndSet(false, true)) return;

        Utils.runOnMainThreadDelayed(() -> {
            try {
                Object graph = staticField("dvb", "a");
                Object sessionAccessor = staticField("tkp", "i0");
                Method resolve = method(graph.getClass(), "p", 2);
                resolve.setAccessible(true);
                Object component = resolve.invoke(graph, sessionAccessor, false);
                Method getSessionManager = method(component.getClass(), "g5", 0);
                getSessionManager.setAccessible(true);
                Object manager = getSessionManager.invoke(component);

                Class<?> eventType = Class.forName(
                    "com.reddit.session.mode.event.InternalSessionEvent"
                );
                Constructor<?> constructor = eventType.getDeclaredConstructor(
                    String.class,
                    String.class,
                    boolean.class,
                    boolean.class,
                    String.class,
                    boolean.class,
                    int.class
                );
                constructor.setAccessible(true);
                Object event = constructor.newInstance(
                    "enter_incognito",
                    null,
                    false,
                    false,
                    null,
                    false,
                    482
                );
                Method enter = manager.getClass().getDeclaredMethod("x", eventType);
                enter.setAccessible(true);
                enter.invoke(manager, event);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Reddit internals are version-specific. Failing closed leaves the login screen intact.
            } finally {
                SCHEDULED.set(false);
            }
        }, 1000L);
    }

    private static Object staticField(String className, String fieldName)
            throws ReflectiveOperationException {
        Field field = Class.forName(className).getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(null);
    }

    private static Method method(Class<?> type, String name, int parameterCount)
            throws NoSuchMethodException {
        for (Method candidate : type.getMethods()) {
            if (candidate.getName().equals(name)
                    && candidate.getParameterTypes().length == parameterCount) {
                return candidate;
            }
        }
        for (Method candidate : type.getDeclaredMethods()) {
            if (candidate.getName().equals(name)
                    && candidate.getParameterTypes().length == parameterCount) {
                return candidate;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
