/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.jam;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.jam.ipc.BridgeProtocol;
import app.morphe.jam.ipc.IJamCompanion;
import app.morphe.jam.ipc.Trust;
import java.lang.ref.WeakReference;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Player-integrated UI, with one live shared-state feed per YTM process. */
public final class JamUi {

  private static final boolean ENABLED = Settings.JAM_ENABLED.get();
  static final Handler main = new Handler(Looper.getMainLooper());
  private static final ExecutorService commands =
      Executors.newSingleThreadExecutor(),
    updates = Executors.newSingleThreadExecutor();
  private static final Set<Consumer<JSONObject>> observers = new HashSet<>();
  private static volatile IJamCompanion companion;
  private static boolean binding, polling, inFlight;
  private static ServiceConnection connection;
  private static WeakReference<Activity> current = new WeakReference<>(null);
  static JSONObject latest = new JSONObject();
  static int pending;

  static void notifyState() {
    for (Consumer<JSONObject> listener : new ArrayList<>(observers))
      listener.accept(latest);
  }

  private static Context application;
  private static final String COMPANION_PACKAGE = "companionPackage",
    COMPANION_SERVICE = ".JamService",
    COMPANION_ACTIVITY = ".MainActivity";

  static int dp(Context c, int value) {
    return Math.round(value * c.getResources().getDisplayMetrics().density);
  }

  static String capability(Context c) {
    return c.getSharedPreferences("jam", 0).getString("cap", "");
  }

  static String companionPackage(Context c) {
    String value = c
      .getSharedPreferences("jam", 0)
      .getString(COMPANION_PACKAGE, Trust.COMPANION);
    return validPackage(value) ? value : Trust.COMPANION;
  }

  private static boolean validPackage(String value) {
    return (
      value != null &&
      value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    );
  }

  private static ComponentName companionComponent(Context c, String name) {
    String pkg = companionPackage(c);
    return new ComponentName(pkg, pkg + name);
  }

  static void configureCompanion(Context c) {
    EditText input = new EditText(c);
    input.setSingleLine();
    input.setText(companionPackage(c));
    input.setSelectAllOnFocus(true);
    input.setHint(str("morphe_music_jam_package_example"));
    input.setPadding(
      dp(c, 24),
      input.getPaddingTop(),
      dp(c, 24),
      input.getPaddingBottom()
    );
    AlertDialog dialog = new AlertDialog.Builder(c)
      .setTitle(str("morphe_music_jam_companion_package_title"))
      .setMessage(str("morphe_music_jam_package_message"))
      .setView(input)
      .setNegativeButton(str("morphe_music_jam_cancel"), null)
      .setPositiveButton(str("morphe_music_jam_use_package"), null)
      .create();
    dialog.setOnShowListener(d ->
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
        String value = input.getText().toString().trim();
        if (!validPackage(value)) {
          input.setError(str("morphe_music_jam_invalid_package"));
          return;
        }
        resetCompanion(c);
        c.getSharedPreferences("jam", 0)
          .edit()
          .putString(COMPANION_PACKAGE, value)
          .remove("cap")
          .apply();
        latest = new JSONObject();
        dialog.dismiss();
        Utils.showToastLong(str("morphe_music_jam_package_saved"));
      })
    );
    dialog.show();
    styleDialog(dialog);
  }

  private static void resetCompanion(Context c) {
    Context app = c.getApplicationContext();
    ServiceConnection old = connection;
    connection = null;
    companion = null;
    binding = false;
    if (old != null) try {
      app.unbindService(old);
    } catch (IllegalArgumentException error) {
      Logger.printInfo(() -> "Jam companion was already unbound", error);
    }
  }

  static Activity activity(Context c) {
    while (c instanceof ContextWrapper) {
      if (c instanceof Activity) return (Activity) c;
      c = ((ContextWrapper) c).getBaseContext();
    }
    return current.get();
  }

  public static boolean enabled() {
    return ENABLED;
  }

  public static void install(Activity a) {
    if (!ENABLED) return;
    Utils.runOnMainThread(() -> {
      try {
        current = new WeakReference<>(a);
        application = a.getApplicationContext();
        if (!capability(a).isEmpty()) {
          bind(a);
          ensurePolling();
        }
      } catch (Exception error) {
        Logger.printInfo(() -> "Could not initialize Jam UI", error);
      }
    });
  }

  static void observe(Context c, Consumer<JSONObject> observer) {
    if (!ENABLED) return;
    observers.add(observer);
    observer.accept(latest);
    application = c.getApplicationContext();
    bind(c);
    ensurePolling();
  }

  static void unobserve(Consumer<JSONObject> observer) {
    observers.remove(observer);
  }

  private static boolean feedEnabled() {
    return application != null && !capability(application).isEmpty();
  }

  private static boolean sessionActive() {
    JSONObject session = latest.optJSONObject("session");
    String role = session == null ? "Idle" : session.optString("role", "Idle");
    return !"Idle".equals(role);
  }

  private static void ensurePolling() {
    if (!polling) {
      polling = true;
      main.post(poll);
    }
  }

  private static void pollNow() {
    polling = true;
    main.removeCallbacks(poll);
    main.post(poll);
  }

  // The utility wraps callbacks on another handler, preventing pollNow() from canceling them.
  private static final Runnable poll = new Runnable() {
    public void run() {
      try {
        if (
          (!observers.isEmpty() || feedEnabled()) &&
          !inFlight &&
          companion != null
        ) {
          inFlight = true;
          updates.execute(() -> {
            JSONObject value;
            try {
              value = companionCall(
                application,
                companion,
                new JSONObject().put("op", "VIEW")
              );
            } catch (Exception e) {
              value = JamBridgeService.error(
                str("morphe_music_jam_disconnected")
              );
            }
            JSONObject received = value;
            Utils.runOnMainThread(() -> {
              inFlight = false;
              latest = received;
              JamMirror.accept(application, received);
              JamClock.accept(received);
              for (Consumer<JSONObject> listener : new ArrayList<>(observers))
                listener.accept(received);
            });
          });
        }
        if (observers.isEmpty() && !feedEnabled()) {
          polling = false;
          return;
        }
        main.postDelayed(this, sessionActive() ? 600 : 3000);
      } catch (Exception error) {
        polling = false;
        Logger.printInfo(() -> "Could not poll Jam state", error);
      }
    }
  };

  private static void bind(Context c) {
    if (binding || capability(c).isEmpty()) return;
    Context app = c.getApplicationContext();
    try {
      connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
          companion = IJamCompanion.Stub.asInterface(b);
          ensurePolling();
        }

        public void onServiceDisconnected(ComponentName n) {
          companion = null;
          binding = false;
          connection = null;
        }

        public void onBindingDied(ComponentName n) {
          companion = null;
          binding = false;
          connection = null;
          try {
            app.unbindService(this);
          } catch (IllegalArgumentException error) {
            Logger.printInfo(
              () -> "Jam companion binding was already gone",
              error
            );
          }
          Utils.runOnMainThreadDelayed(() -> bind(app), 1500);
        }
      };
      binding = app.bindService(
        new Intent().setComponent(companionComponent(app, COMPANION_SERVICE)),
        connection,
        Context.BIND_AUTO_CREATE
      );
      if (!binding) connection = null;
    } catch (Exception e) {
      companion = null;
      binding = false;
      connection = null;
      Logger.printInfo(() -> "Could not bind Jam companion", e);
    }
  }

  private static JSONObject companionCall(
    Context context,
    IJamCompanion service,
    JSONObject request
  ) throws Exception {
    JSONObject envelope = BridgeProtocol.advertise(
      new JSONObject(request.toString())
    );
    return BridgeProtocol.validate(
      new JSONObject(service.call(capability(context), envelope.toString()))
    );
  }

  static JSONObject command(String operation) {
    try {
      return new JSONObject()
        .put("op", operation)
        .put("id", UUID.randomUUID().toString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static void call(Context c, JSONObject request, Consumer<JSONObject> done) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      Utils.runOnMainThread(() -> call(c, request, done));
      return;
    }
    bind(c);
    pending++;
    notifyState();
    ("END".equals(request.optString("op")) ? updates : commands).execute(() -> {
      JSONObject value;
      try {
        IJamCompanion service = companion;
        if (service == null) throw new IllegalStateException(
          str("morphe_music_jam_enable_layer_first")
        );
        value = companionCall(c, service, request);
      } catch (Exception e) {
        value = JamBridgeService.error(e.getMessage());
      }
      JSONObject response = value;
      Utils.runOnMainThread(() -> {
        pending = Math.max(0, pending - 1);
        if (
          response.optBoolean("ok") &&
          "GUEST_EDITS".equals(request.optString("op"))
        ) {
          try {
            JSONObject state = latest.optJSONObject("session");
            if (state != null) state.put(
              "allowGuestEdits",
              request.optBoolean("allow")
            );
          } catch (Exception error) {
            Logger.printInfo(
              () -> "Could not update Jam guest edit state",
              error
            );
          }
        }
        done.accept(response);
        if (response.optBoolean("ok")) pollNow();
        notifyState();
      });
    });
  }

  static void edit(Context c, JSONObject request) {
    call(c, request, r -> {
      if (!r.optBoolean("ok")) Utils.showToastLong(r.optString("error"));
    });
  }

  static void styleDialog(AlertDialog dialog) {
    android.view.Window window = dialog.getWindow();
    if (window == null) return;
    Context c = dialog.getContext();
    android.graphics.drawable.GradientDrawable surface =
      new android.graphics.drawable.GradientDrawable();
    surface.setColor(0xff212121);
    surface.setCornerRadius(dp(c, 28));
    window.setBackgroundDrawable(surface);
    window.getDecorView().setClipToOutline(true);
    window.setLayout(
      Math.min(
        c.getResources().getDisplayMetrics().widthPixels - dp(c, 32),
        dp(c, 520)
      ),
      android.view.ViewGroup.LayoutParams.WRAP_CONTENT
    );
  }

  private static void startLayer(Context c) {
    c.startForegroundService(
      new Intent()
        .setComponent(companionComponent(c, COMPANION_SERVICE))
        .putExtra("cap", capability(c))
    );
  }

  public static void open(Context context) {
    if (!ENABLED) {
      Utils.showToastLong(str("morphe_music_jam_enable_patch_first"));
      return;
    }
    try {
      JamPanel.show(context);
    } catch (Exception error) {
      Logger.printInfo(() -> "Could not open Jam panel", error);
    }
  }

  private static boolean requireWifi(Context c) {
    android.net.wifi.WifiManager wifi = c
      .getApplicationContext()
      .getSystemService(android.net.wifi.WifiManager.class);
    if (wifi == null || wifi.isWifiEnabled()) return true;
    AlertDialog dialog = new AlertDialog.Builder(c)
      .setTitle(str("morphe_music_jam_wifi_title"))
      .setMessage(str("morphe_music_jam_wifi_message"))
      .setNegativeButton(str("morphe_music_jam_cancel"), null)
      .setPositiveButton(str("morphe_music_jam_wifi_settings"), (d, w) -> {
        try {
          c.startActivity(
            new Intent(
              Build.VERSION.SDK_INT >= 29
                ? android.provider.Settings.Panel.ACTION_WIFI
                : android.provider.Settings.ACTION_WIFI_SETTINGS
            )
          );
        } catch (ActivityNotFoundException error) {
          Logger.printInfo(() -> "Could not open Wi-Fi settings", error);
        }
      })
      .create();
    dialog.show();
    styleDialog(dialog);
    return false;
  }

  static void host(Context c) {
    if (!requireWifi(c)) return;
    try {
      startLayer(c);
      edit(c, command("HOST"));
    } catch (Exception e) {
      setup(c);
    }
  }

  static void pair(Context c) {
    Activity a = activity(c);
    if (a == null) return;
    try {
      byte[] bytes = new byte[32];
      new SecureRandom().nextBytes(bytes);
      StringBuilder token = new StringBuilder();
      for (byte b : bytes)
        token.append(String.format(Locale.ROOT, "%02x", b & 255));
      c.getSharedPreferences("jam", 0)
        .edit()
        .putString("cap", token.toString())
        .apply();
      a.startActivityForResult(
        new Intent("app.morphe.jam.PAIR")
          .setComponent(companionComponent(c, COMPANION_ACTIVITY))
          .putExtra("cap", token.toString()),
        18431
      );
      Utils.runOnMainThreadDelayed(() -> bind(c), 1500);
    } catch (Exception e) {
      Utils.showToastLong(str("morphe_music_jam_install_layer_first"));
    }
  }

  private static void setup(Context c) {
    try {
      c.startActivity(
        new Intent().setComponent(companionComponent(c, COMPANION_ACTIVITY))
      );
    } catch (Exception e) {
      Utils.showToastLong(str("morphe_music_jam_install_layer_first"));
    }
  }

  static void join(Context c) {
    if (!requireWifi(c)) return;
    LinearLayout content = new LinearLayout(c);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(c, 24), dp(c, 8), dp(c, 24), 0);
    TextView hint = new TextView(c);
    hint.setText(str("morphe_music_jam_join_hint"));
    hint.setTextSize(14);
    hint.setPadding(0, 0, 0, dp(c, 16));
    content.addView(hint);
    EditText input = new EditText(c);
    input.setHint(str("morphe_music_jam_code_example"));
    input.setSingleLine();
    input.setTextSize(22);
    input.setTypeface(Typeface.MONOSPACE);
    input.setInputType(4097);
    input.setFilters(new android.text.InputFilter[] {
      new android.text.InputFilter.LengthFilter(512),
    });
    content.addView(input, new LinearLayout.LayoutParams(-1, dp(c, 56)));
    AlertDialog dialog = new AlertDialog.Builder(c)
      .setTitle(str("morphe_music_jam_join_with_code"))
      .setView(content)
      .setPositiveButton(str("morphe_music_jam_join"), null)
      .setNeutralButton(str("morphe_music_jam_scan_qr"), (d, w) -> {
        if (!requireWifi(c)) return;
        try {
          Activity a = activity(c);
          if (a != null) a.startActivityForResult(
            new Intent("app.morphe.jam.SCAN")
              .setComponent(companionComponent(c, COMPANION_ACTIVITY))
              .putExtra("cap", capability(c)),
            18432
          );
        } catch (Exception e) {
          setup(c);
        }
      })
      .setNegativeButton(str("morphe_music_jam_cancel"), null)
      .create();
    dialog.setOnShowListener(d ->
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
        String value = input.getText().toString().trim();
        String normalized = value
          .replace("-", "")
          .replace(" ", "")
          .toUpperCase(Locale.ROOT);
        if (
          !value.startsWith("morphejam://") &&
          !normalized.matches("[A-HJ-NP-Z2-9]{8}")
        ) {
          input.setError(str("morphe_music_jam_invalid_invite"));
          return;
        }
        try {
          if (!requireWifi(c)) return;
          startLayer(c);
          call(c, command("JOIN").put("invite", value), r -> {
            if (!r.optBoolean("ok")) Utils.showToastLong(r.optString("error"));
          });
          dialog.dismiss();
          open(c);
        } catch (Exception e) {
          setup(c);
        }
      })
    );
    dialog.show();
    styleDialog(dialog);
  }

  static void invite(Context c) {
    call(c, command("INVITE"), r -> {
      if (!r.optBoolean("ok")) {
        Utils.showToastLong(r.optString("error"));
        return;
      }
      String code = r.optString("code");
      if (!code.isEmpty()) {
        TextView text = new TextView(c);
        text.setText(code);
        text.setTextSize(32);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setGravity(android.view.Gravity.CENTER);
        text.setPadding(24, 32, 24, 32);
        text.setTextIsSelectable(true);
        long minutes = Math.max(
          1,
          (r.optLong("codeExpires") - System.currentTimeMillis() + 59999) /
            60000
        );
        AlertDialog popup = new AlertDialog.Builder(c)
          .setTitle(str("morphe_music_jam_join_your_jam"))
          .setMessage(
            String.format(str("morphe_music_jam_code_expires"), minutes)
          )
          .setView(text)
          .setPositiveButton(str("morphe_music_jam_copy_code"), (d, w) ->
            (
              (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE)
            ).setPrimaryClip(
              ClipData.newPlainText(str("morphe_music_jam_code_clip"), code)
            )
          )
          .setNeutralButton(str("morphe_music_jam_show_qr"), (d, w) ->
            showQr(c, r)
          )
          .setNegativeButton(str("morphe_music_jam_done"), null)
          .create();
        popup.show();
        styleDialog(popup);
      } else showQr(c, r);
    });
  }

  private static void showQr(Context c, JSONObject r) {
    byte[] bytes = android.util.Base64.decode(
      r.optString("qr"),
      android.util.Base64.DEFAULT
    );
    ImageView image = new ImageView(c);
    image.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
    image.setAdjustViewBounds(true);
    image.setPadding(24, 24, 24, 24);
    AlertDialog popup = new AlertDialog.Builder(c)
      .setTitle(str("morphe_music_jam_invite_title"))
      .setMessage(str("morphe_music_jam_invite_message"))
      .setView(image)
      .setPositiveButton(str("morphe_music_jam_done"), null)
      .setNeutralButton(str("morphe_music_jam_copy_link"), (d, w) ->
        (
          (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE)
        ).setPrimaryClip(
          ClipData.newPlainText(
            str("morphe_music_jam_invitation_clip"),
            r.optString("invite")
          )
        )
      )
      .create();
    popup.show();
    styleDialog(popup);
  }

  public static boolean offer(YtmBridge.QueueAccess access, byte[] bytes) {
    try {
      return offerQueueCommand(access, bytes);
    } catch (Exception error) {
      Logger.printInfo(() -> "Could not inspect Jam queue command", error);
      return false;
    }
  }

  private static boolean offerQueueCommand(
    YtmBridge.QueueAccess access,
    byte[] bytes
  ) {
    if (!ENABLED) return false;
    String[] decoded = QueueCommand.decode(bytes);
    Activity a = current.get();
    if (decoded == null || a == null || a.isFinishing()) return false;
    if (companion == null) {
      if (JamMirror.active()) {
        Utils.runOnMainThread(() ->
          Utils.showToastLong(str("morphe_music_jam_reconnecting_toast"))
        );
        return true;
      }
      return false;
    }
    Runnable local = () ->
      access.patch_jamExecutor().execute(() -> access.patch_jamEnqueue(bytes));
    commands.execute(() -> {
      try {
        JSONObject state = companionCall(
          a,
          companion,
          new JSONObject().put("op", "STATE")
        );
        if (!"Participant".equals(state.optString("role"))) {
          local.run();
          return;
        }
        call(a, command(decoded[1]).put("videoId", decoded[0]), response ->
          Utils.showToastLong(
            response.optBoolean("ok")
              ? str("morphe_music_jam_added_to_jam")
              : response.optString("error")
          )
        );
      } catch (Exception e) {
        Utils.runOnMainThread(() ->
          Utils.showToastLong(str("morphe_music_jam_unavailable_toast"))
        );
      }
    });
    return true;
  }
}
