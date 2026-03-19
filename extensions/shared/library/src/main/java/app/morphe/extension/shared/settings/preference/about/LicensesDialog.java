package app.morphe.extension.shared.settings.preference.about;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Utils;

public class LicensesDialog extends Dialog {

    // Licenses for all software bundled with Morphe MPP Patches.
    private static final List<License> dependencies = List.of(
            new License("AndroidX Annotation",
                    "https://raw.githubusercontent.com/androidx/androidx/androidx-main/LICENSE.txt"),
            new License("AndroidX Core",
                    "https://raw.githubusercontent.com/androidx/androidx/androidx-main/LICENSE.txt"),
            new License("Android Hidden API Bypass",
                    "https://raw.githubusercontent.com/LSPosed/AndroidHiddenApiBypass/main/LICENSE"),
            new License("AndroidX JavaScriptEngine",
                    "https://raw.githubusercontent.com/androidx/androidx/androidx-main/LICENSE.txt"),
            new License("Gson",
                    "https://raw.githubusercontent.com/google/gson/main/LICENSE"),
            new License("Guava",
                    "https://raw.githubusercontent.com/google/guava/master/LICENSE"),
            new License("Morphe",
                    "https://raw.githubusercontent.com/MorpheApp/morphe-patches/refs/heads/main/LICENSE",
                    "https://raw.githubusercontent.com/MorpheApp/morphe-patches/refs/heads/main/NOTICE"),
            new License("Protocol Buffers",
                    "https://raw.githubusercontent.com/protocolbuffers/protobuf/main/LICENSE")
    );

    private record License(String name, List<String> licenseUrls, List<String> noticeUrls) {
        public License(String name, String licenseUrl) {
            this(name, Collections.singletonList(licenseUrl), Collections.emptyList());
        }

        public License(String name, String licenseUrl, String noticeUrl) {
            this(name, Collections.singletonList(licenseUrl), Collections.singletonList(noticeUrl));
        }
    }

    @Nullable
    private WebView webView;

    public LicensesDialog(Context context) {
        super(context, android.R.style.Theme_DeviceDefault_NoActionBar);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Apply the background color to the navigation bar.
        Window window = getWindow();
        if (window != null) {
            window.setNavigationBarColor(Utils.getAppBackgroundColor());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setNavigationBarContrastEnforced(true);
            }
        }

        showList();
        setOnKeyListener((dialog, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK
                    && event.getAction() == KeyEvent.ACTION_UP
                    && webView != null) {
                webView = null;
                showList();
                return true;
            }
            return false;
        });
    }

    private void showList() {
        List<String> names = new ArrayList<>(dependencies.size());
        for (License dep : dependencies) {
            names.add(dep.name);
        }

        ListView listView = new ListView(getContext());
        listView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        listView.setAdapter(new ArrayAdapter<>(getContext(),
                android.R.layout.simple_list_item_1, names));
        listView.setOnItemClickListener((parent, view, position, id) ->
                showDetail(dependencies.get(position)));
        setContentView(listView);
        applyInsetsToContentView();
    }

    private void showDetail(License dep) {
        webView = new WebView(getContext());
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        webView.getSettings().setJavaScriptEnabled(false);
        webView.setWebViewClient(new AboutLinksWebClient(getContext(), this));
        webView.loadDataWithBaseURL(null, loadingHtml(),
                "text/html", "UTF-8", null);
        setContentView(webView);
        applyInsetsToContentView();

        List<String> allUrls = new ArrayList<>(dep.noticeUrls);
        allUrls.addAll(dep.licenseUrls);
        fetchAllAndRender(allUrls);
    }

    /**
     * Applies window insets to the content root view.
     */
    private void applyInsetsToContentView() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;

        Window window = getWindow();
        if (window == null) return;

        ViewGroup rootView = (ViewGroup) window.getDecorView()
                .findViewById(android.R.id.content)
                .getParent();

        rootView.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets statusInsets = insets.getInsets(WindowInsets.Type.statusBars());
            Insets navInsets    = insets.getInsets(WindowInsets.Type.navigationBars());
            Insets cutoutInsets = insets.getInsets(WindowInsets.Type.displayCutout());

            v.setPadding(
                    cutoutInsets.left,
                    statusInsets.top,
                    cutoutInsets.right,
                    navInsets.bottom
            );
            return insets;
        });
    }

    private void fetchAllAndRender(List<String> urls) {
        Utils.runOnBackgroundThread(() -> {
            List<Pair<String, String>> results = new ArrayList<>(urls.size());
            for (String url : urls) {
                results.add(new Pair<>(url, fetchUrl(url)));
            }

            WebView target = webView;
            if (target != null) {
                target.post(() -> target.loadDataWithBaseURL(
                        null, buildHtml(results), "text/html", "UTF-8", null));
            }
        });
    }

    private String fetchUrl(String urlString) {
        Utils.verifyOffMainThread();

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int bytesRead;
                while ((bytesRead = is.read(chunk)) != -1) {
                    buffer.write(chunk, 0, bytesRead);
                }
                //noinspection CharsetObjectCanBeUsed
                return buffer.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Exception e) {
            return "[Failed to load " + urlString + "]\n" + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String buildHtml(List<Pair<String, String>> urlsAndContent) {
        StringBuilder body = new StringBuilder();
        for (Pair<String, String> pair : urlsAndContent) {
            body.append("<h2>").append(htmlEscape(pair.first)).append("</h2>");
            body.append("<pre>").append(htmlEscape(pair.second)).append("</pre>");
        }

        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<style>" +
                "  body{margin:0;padding:16px;background:#1C1B1F;color:#E6E1E5;" +
                "       font-family:monospace;font-size:13px;line-height:1.6;}" +
                "  h2{color:#D0BCFF;font-family:sans-serif;font-size:13px;word-break:break-all;" +
                "     border-bottom:1px solid #49454F;padding-bottom:6px;margin-top:24px;}" +
                "  h2:first-of-type{margin-top:0;}" +
                "  pre{white-space:pre;overflow-x:auto;word-break:normal;overflow-wrap:normal;margin:0;}" +
                "  a{color:#80BCFF;}" +
                "</style></head><body>" + body + "</body></html>";
    }

    private String loadingHtml() {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
                "<style>body{margin:0;padding:32px;background:#1C1B1F;color:#938F99;" +
                "font-family:sans-serif;font-size:14px;}</style></head>" +
                "<body>" + str("morphe_settings_about_licenses_loading") + "</body></html>";
    }

    private static String htmlEscape(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replaceAll("(https?://[^\\s<>\"]+)", "<a href='$1'>$1</a>");
    }
}
