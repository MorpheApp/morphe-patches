package app.morphe.extension.shared;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import androidx.annotation.Nullable;

import java.io.InputStream;
import java.util.Scanner;

import app.morphe.extension.shared.settings.Setting;

@SuppressWarnings({"unused", "deprecation", "DiscouragedApi"})
public class ResourceUtils {

    private ResourceUtils() {
    } // utility class

    private static Context getActivityOrContext() {
        Activity mActivity = Utils.getActivity();
        return mActivity != null
                ? mActivity
                : Utils.getContext();
    }

    /**
     * @param type Resource type, or <code>null</code> to search all types for the first declaration.
     * @return zero, if the resource is not found.
     */
    public static int getIdentifier(@Nullable ResourceType type, String name) {
        Context mContext = getActivityOrContext();
        if (mContext == null) {
            handleException(type, name);
            return 0;
        }
        return getIdentifier(mContext, type, name);
    }

    /**
     * @return the resource identifier, or throws an exception if not found.
     * @param type Resource type, or <code>null</code> to search all types for the first declaration.
     * @see #getIdentifier(ResourceType, String)
     */
    public static int getIdentifierOrThrow(@Nullable ResourceType type, String name) {
        return getIdentifierOrThrow(getActivityOrContext(), type, name);
    }

    /**
     * @return zero if the resource is not found.
     * @param type Resource type, or <code>null</code> to search all types for the first declaration.
     */
    public static int getIdentifier(Context context, @Nullable ResourceType type, String name) {
        try {
            return context.getResources().getIdentifier(name,
                    type == null ? null : type.type,
                    context.getPackageName());
        } catch (Exception ex) {
            handleException(type, name);
        }
        return 0;
    }

    public static int getIdentifierOrThrow(Context context, @Nullable ResourceType type, String name) {
        final int resourceId = getIdentifier(context, type, name);
        if (resourceId == 0) {
            throw new Resources.NotFoundException("No resource id exists with name: " + name
                    + " type: " + type);
        }
        return resourceId;
    }

    public static int getAnimIdentifier(String name) {
        return getIdentifier(ResourceType.ANIM, name);
    }

    public static int getArrayIdentifier(String name) {
        return getIdentifier(ResourceType.ARRAY, name);
    }

    public static int getAttrIdentifier(String name) {
        return getIdentifier(ResourceType.ATTR, name);
    }

    public static int getColorIdentifier(String name) {
        return getIdentifier(ResourceType.COLOR, name);
    }

    public static int getColor(String name) throws Resources.NotFoundException {
        if (name.startsWith("#")) {
            return Color.parseColor(name);
        }
        final int identifier = getColorIdentifier(name);
        if (identifier == 0) {
            handleException(ResourceType.COLOR, name);
            return 0;
        }
        return Utils.getResources().getColor(identifier);
    }

    public static int getDimenIdentifier(String name) {
        return getIdentifier(ResourceType.DIMEN, name);
    }

    public static int getDrawableIdentifier(String name) {
        return getIdentifier(ResourceType.DRAWABLE, name);
    }

    public static int getFontIdentifier(String name) {
        return getIdentifier(ResourceType.FONT, name);
    }

    public static int getIdIdentifier(String name) {
        return getIdentifier(ResourceType.ID, name);
    }

    public static int getIntegerIdentifier(String name) {
        return getIdentifier(ResourceType.INTEGER, name);
    }

    public static int getLayoutIdentifier(String name) {
        return getIdentifier(ResourceType.LAYOUT, name);
    }

    public static int getMenuIdentifier(String name) {
        return getIdentifier(ResourceType.MENU, name);
    }

    public static int getMipmapIdentifier(String name) {
        return getIdentifier(ResourceType.MIPMAP, name);
    }

    public static int getRawIdentifier(String name) {
        return getIdentifier(ResourceType.RAW, name);
    }

    public static int getStringIdentifier(String name) {
        return getIdentifier(ResourceType.STRING, name);
    }

    public static int getStyleIdentifier(String name) {
        return getIdentifier(ResourceType.STYLE, name);
    }

    public static int getXmlIdentifier(String name) {
        return getIdentifier(ResourceType.XML, name);
    }

    public static Animation getAnimation(String name) {
        int identifier = getAnimIdentifier(name);
        if (identifier == 0) {
            handleException(ResourceType.ANIM, name);
            identifier = android.R.anim.fade_in;
        }
        return AnimationUtils.loadAnimation(getActivityOrContext(), identifier);
    }

    public static float getDimension(String name) throws Resources.NotFoundException {
        return getActivityOrContext().getResources().getDimension(getIdentifierOrThrow(ResourceType.DIMEN, name));
    }

    public static int getDimensionPixelSize(String name) throws Resources.NotFoundException {
        return getActivityOrContext().getResources().getDimensionPixelSize(getIdentifierOrThrow(ResourceType.DIMEN, name));
    }

    @Nullable
    public static Drawable getDrawable(String name) {
        final int identifier = getDrawableIdentifier(name);
        if (identifier == 0) {
            handleException(ResourceType.DRAWABLE, name);
            return null;
        }
        return getActivityOrContext().getDrawable(identifier);
    }

    public static Drawable getDrawableOrThrow(String name) {
        return checkResourceNotNull(getDrawable(name), ResourceType.DRAWABLE, name);
    }

    @Nullable
    public static String getString(String name) {
        final int identifier = getStringIdentifier(name);
        if (identifier == 0) {
            handleException(ResourceType.STRING, name);
            return name;
        }
        return getActivityOrContext().getString(identifier);
    }

    public static String getStringOrThrow(String name) {
        return checkResourceNotNull(getString(name), ResourceType.STRING, name);
    }

    public static String[] getStringArray(String name) {
        final int identifier = getArrayIdentifier(name);
        if (identifier == 0) {
            handleException(ResourceType.ARRAY, name);
            return new String[0];
        }
        return Utils.getResources().getStringArray(identifier);
    }

    public static String[] getStringArray(Setting<?> setting, String suffix) {
        return getStringArray(setting.key + suffix);
    }

    /**
     * @return zero if the resource is not found.
     */
    public static int getInteger(String name) {
        final int identifier = getIntegerIdentifier(name);
        if (identifier == 0) {
            handleException(ResourceType.INTEGER, name);
            return 0;
        }
        return Utils.getResources().getInteger(identifier);
    }

    @Nullable
    public static String getRawResource(String name) {
        final int identifier = getRawIdentifier(name);
        if (identifier == 0) {
            handleException(ResourceType.RAW, name);
            return null;
        }
        try (InputStream is = Utils.getResources().openRawResource(identifier)) {
            //noinspection CharsetObjectCanBeUsed
            return new Scanner(is, "UTF-8").useDelimiter("\\A").next();
        } catch (Exception ex) {
            Logger.printException(() -> "getRawResource failed", ex);
            return null;
        }
    }

    private static void handleException(ResourceType type, String name) {
        Logger.printException(() -> "R." + type.type + "." + name + " is null");
    }

    private static <T> T checkResourceNotNull(T resource, ResourceType type, String name) {
        if (resource == null) {
            throw new IllegalArgumentException("Could not find: " + type + " name: " + name);
        }
        return resource;
    }
}
