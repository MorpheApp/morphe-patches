package app.morphe.extension.shared.settings.preference;

import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.util.AttributeSet;

import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;

/**
 * Translates the texts of inflated preferences.
 * <p>
 * The patches add translated strings as JSON instead of string resources, so inflating the
 * preferences resolves their texts to the string resources of the default language.
 * The texts are looked up again by the name of the string resource the XML references.
 */
final class PreferenceTranslations {

    private static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";

    // Children of a preference element that are not preferences.
    private static final String TAG_INTENT = "intent";
    private static final String TAG_EXTRA = "extra";

    private PreferenceTranslations() {
    }

    /**
     * Translates the titles and summaries of the preferences inflated from the XML. The preferences
     * of a group are in the order of the XML until they are sorted, so each element is matched to
     * the preference at its position, and its key is checked if it has one.
     */
    static void translate(Resources resources, int xmlIdentifier, PreferenceGroup screen) {
        if (!ResourceUtils.hasTranslations()) {
            return;
        }

        final long start = System.nanoTime();
        try (XmlResourceParser parser = resources.getXml(xmlIdentifier)) {
            int event;
            do {
                event = parser.next();
            } while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT);

            if (event == XmlPullParser.START_TAG) {
                translatePreference(parser, screen);
                translateChildren(parser, screen);
            }
        } catch (XmlPullParserException | IOException | RuntimeException ex) {
            Logger.printException(() -> "translate failure", ex);
        }

        final long millis = (System.nanoTime() - start) / 1_000_000;
        Logger.printDebug(() -> "Translated preferences in " + millis + "ms");
    }

    /**
     * Translates the entries of a list preference inflated with the attributes.
     * Called as it is created, before a subclass sorts or filters the entries.
     */
    static void translateEntries(ListPreference preference, @Nullable AttributeSet attributes) {
        if (attributes == null) {
            return;
        }

        final int entriesIdentifier = attributes.getAttributeResourceValue(ANDROID_NAMESPACE, "entries", 0);
        if (entriesIdentifier != 0 && ResourceUtils.hasTranslations()) {
            preference.setEntries(ResourceUtils.getStringArray(entriesIdentifier));
        }
    }

    /**
     * @param parser At the start tag of the group.
     */
    private static void translateChildren(XmlResourceParser parser, PreferenceGroup group)
            throws XmlPullParserException, IOException {
        final int depth = parser.getDepth();
        int index = 0;
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.getDepth() == depth) {
                return;
            }
            if (event != XmlPullParser.START_TAG) {
                continue;
            }

            String tag = parser.getName();
            if (tag.equals(TAG_INTENT) || tag.equals(TAG_EXTRA)) {
                skipElement(parser);
                continue;
            }

            Preference preference = group.getPreference(index++);
            translatePreference(parser, preference);
            if (preference instanceof PreferenceGroup childGroup) {
                translateChildren(parser, childGroup);
            } else {
                skipElement(parser);
            }
        }
    }

    private static void translatePreference(XmlResourceParser parser, Preference preference) {
        String key = parser.getAttributeValue(ANDROID_NAMESPACE, "key");
        if (key != null && !key.equals(preference.getKey())) {
            throw new IllegalStateException("Expected preference: " + key + " but found: " + preference.getKey());
        }

        String title = getTranslation(parser, "title");
        if (title != null) {
            preference.setTitle(title);
        }

        String summary = getTranslation(parser, "summary");
        if (summary != null) {
            preference.setSummary(summary);
        }

        // Morphe list preferences translate their entries as they are created.
        if (preference instanceof ListPreference list && !(preference instanceof CustomDialogListPreference)) {
            final int entriesIdentifier = parser.getAttributeResourceValue(ANDROID_NAMESPACE, "entries", 0);
            if (entriesIdentifier != 0) {
                list.setEntries(ResourceUtils.getStringArray(entriesIdentifier));
            }
        }
    }

    @Nullable
    private static String getTranslation(XmlResourceParser parser, String attribute) {
        final int stringIdentifier = parser.getAttributeResourceValue(ANDROID_NAMESPACE, attribute, 0);
        return stringIdentifier == 0
                ? null
                : ResourceUtils.getTranslation(stringIdentifier);
    }

    /**
     * @param parser At the start tag of the element, and left at its end tag.
     */
    private static void skipElement(XmlResourceParser parser) throws XmlPullParserException, IOException {
        final int depth = parser.getDepth();
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.getDepth() == depth) {
                return;
            }
        }
    }
}
