package app.morphe.extension.shared;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import app.morphe.extension.shared.settings.Setting;
import kotlin.io.TextStreamsKt;

@SuppressWarnings({"unused", "deprecation", "DiscouragedApi"})
public class ResourceUtils {

    private ResourceUtils() {
    } // utility class

    public static int getIdentifier(@NonNull String str, @NonNull ResourceType resourceType) {
        Activity mActivity = Utils.getActivity();
        Context mContext = mActivity != null
                ? mActivity
                : Utils.getContext();
        if (mContext == null) {
            handleException(str, resourceType);
            return 0;
        }
        return getIdentifier(str, resourceType, mContext);
    }

    public static int getIdentifier(@NonNull String str, @NonNull ResourceType resourceType,
                                    @NonNull Context context) {
        try {
            return context.getResources().getIdentifier(str, resourceType.getType(), context.getPackageName());
        } catch (Exception ex) {
            handleException(str, resourceType);
        }
        return 0;
    }

    public static int getAnimIdentifier(@NonNull String str) {
        return getIdentifier(str, ResourceType.ANIM);
    }

    public static int getArrayIdentifier(@NonNull String str) {
        return getIdentifier(str, ResourceType.ARRAY);
    }

    public static int getAttrIdentifier(@NonNull String str) {
        return getIdentifier(str, ResourceType.ATTR);
    }

    public static int getColorIdentifier(@NonNull String str) {
        return getIdentifier(str, ResourceType.COLOR);
    }

    public static int getDimenIdentifier(@NonNull String str) {
        return getIdentifier(str, ResourceType.DIMEN);
    }

    public static int getDrawableIdentifier(@NonNull String str) {
        return getIdentifier(str, ResourceType.DRAWABLE);
    }

    public static int getFontIdentifier(@NonNull String str) {
        return getIdentifier(str, ResourceType.FONT);
    }

    public static int getIdIdentifier(@NonNull String str) {
        return getIdentifier(str, ResourceType.ID);
    }

    public static int getIntegerIdentifier(@NonNull String str) {
        return getIdentifier(str, ResourceType.INTEGER);
    }

    public static int getLayoutIdentifier(@NonNull String str) {
        return getIdentifier(str, ResourceType.LAYOUT);
    }

    public static int getMenuIdentifier(@NonNull String str) {
        return getIdentifier(str, ResourceType.MENU);
    }

    public static int getMipmapIdentifier(@NonNull String str) {
        return getIdentifier(str, ResourceType.MIPMAP);
    }

    public static int getRawIdentifier(@NonNull String str) {
        return getIdentifier(str, ResourceType.RAW);
    }

    public static int getStringIdentifier(@NonNull String str) {
        return getIdentifier(str, ResourceType.STRING);
    }

    public static int getStyleIdentifier(@NonNull String str) {
        return getIdentifier(str, ResourceType.STYLE);
    }

    public static int getXmlIdentifier(@NonNull String str) {
        return getIdentifier(str, ResourceType.XML);
    }

    @Nullable
    public static Animation getAnimation(@NonNull String str) {
        try {
            int identifier = getAnimIdentifier(str);
            if (identifier == 0) {
                handleException(str, ResourceType.ANIM);
                identifier = android.R.anim.fade_in;
            }
            return AnimationUtils.loadAnimation(Utils.getContext(), identifier);
        } catch (Exception ex) {
            handleException(str, ResourceType.ANIM);
        }
        return null;
    }

    public static int getColor(@NonNull String str) {
        if (str.startsWith("#")) {
            return Color.parseColor(str);
        }
        final int identifier = getColorIdentifier(str);
        if (identifier == 0) {
            handleException(str, ResourceType.COLOR);
            return 0;
        }
        return Utils.getResources().getColor(identifier);
    }

    public static int getDimension(@NonNull String str) {
        final int identifier = getDimenIdentifier(str);
        if (identifier == 0) {
            handleException(str, ResourceType.DIMEN);
            return 0;
        }
        return Utils.getResources().getDimensionPixelSize(identifier);
    }

    public static Drawable getDrawable(@NonNull String str) {
        final int identifier = getDrawableIdentifier(str);
        if (identifier == 0) {
            handleException(str, ResourceType.DRAWABLE);
            return null;
        }
        return Utils.getResources().getDrawable(identifier);
    }

    public static String getString(@NonNull String str) {
        final int identifier = getStringIdentifier(str);
        if (identifier == 0) {
            handleException(str, ResourceType.STRING);
            return str;
        }
        return Utils.getResources().getString(identifier);
    }

    public static String[] getStringArray(@NonNull String str) {
        final int identifier = getArrayIdentifier(str);
        if (identifier == 0) {
            handleException(str, ResourceType.ARRAY);
            return new String[0];
        }
        return Utils.getResources().getStringArray(identifier);
    }

    public static String[] getStringArray(@NonNull Setting<?> setting, @NonNull String suffix) {
        return getStringArray(setting.key + suffix);
    }

    public static int getInteger(@NonNull String str) {
        final int identifier = getIntegerIdentifier(str);
        if (identifier == 0) {
            handleException(str, ResourceType.INTEGER);
            return 0;
        }
        return Utils.getResources().getInteger(identifier);
    }

    public static String[] getEntry(@NonNull Setting<?> setting) {
        return getStringArray(setting, "_entries");
    }

    public static String[] getEntryValue(@NonNull Setting<?> setting) {
        return getStringArray(setting, "_entry_values");
    }

    public static String getRawResource(@NonNull String str) {
        final InputStream is = openRawResource(str);
        if (is != null) {
            try {
                InputStreamReader inputStream = new InputStreamReader(is);
                BufferedReader reader = new BufferedReader(inputStream, 8192);
                String readText = TextStreamsKt.readText(reader);
                reader.close();
                return readText;
            } catch (Exception ignored) {
                handleException(str, ResourceType.RAW);
            }
        }
        return null;
    }

    private static InputStream openRawResource(@NonNull String str) {
        final int identifier = getRawIdentifier(str);
        if (identifier == 0) {
            handleException(str, ResourceType.RAW);
            return null;
        }
        return Utils.getResources().openRawResource(identifier);
    }

    private static void handleException(@NonNull String str, ResourceType resourceType) {
        Logger.printException(() -> "R." + resourceType.getType() + "." + str + " is null");
    }

    public enum ResourceType {
        ANIM("anim"),
        ARRAY("array"),
        ATTR("attr"),
        COLOR("color"),
        DIMEN("dimen"),
        DRAWABLE("drawable"),
        FONT("font"),
        ID("id"),
        INTEGER("integer"),
        LAYOUT("layout"),
        MENU("menu"),
        MIPMAP("mipmap"),
        RAW("raw"),
        STRING("string"),
        STYLE("style"),
        XML("xml");

        private final String type;

        ResourceType(String type) {
            this.type = type;
        }

        public final String getType() {
            return type;
        }
    }
}
