package app.morphe.extension.shared.spoof.js.nsigsolver.common;

import android.content.Context;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

public class ScriptUtils {
    public static class ScriptLoaderError extends Exception {
        public ScriptLoaderError(String message, Exception cause) {
            super(message, cause);
        }
    }

    public static String loadScript(String filename, String errorMsg) throws ScriptLoaderError {
        Context context = Utils.getContext();
        if (context == null) {
            throw new ScriptLoaderError(
                    formatError(errorMsg, "Context isn't available"),
                    null
            );
        }

        String fixedFilename = filename
                .replace("nsigsolver/", "")
                .replace(".js", "");

        return ResourceUtils.getRawResource(fixedFilename);
    }

    public static String loadScript(List<String> filenames, String errorMsg) throws ScriptLoaderError {
        StringBuilder sb = new StringBuilder();
        for (String filename : filenames) {
            sb.append(loadScript(filename, errorMsg));
        }
        return sb.toString();
    }

    public static String formatError(String firstMsg, String secondMsg) {
        return (firstMsg != null) ? firstMsg + ": " + secondMsg : secondMsg;
    }
}
