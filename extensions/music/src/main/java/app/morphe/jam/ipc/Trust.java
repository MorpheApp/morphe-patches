package app.morphe.jam.ipc;

import android.content.Context;
import android.os.Binder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Trust {

  public static final String COMPANION = "app.morphe.jam.companion";
  public static final String BRIDGE_SERVICE =
    "app.morphe.extension.music.jam.JamBridgeService";

  public static void caller(Context context, String pkg) {
    String[] packages = context
      .getPackageManager()
      .getPackagesForUid(Binder.getCallingUid());
    // Reject shared-UID packages: authority must belong exclusively to the paired app.
    if (
      packages == null || packages.length != 1 || !pkg.equals(packages[0])
    ) throw new SecurityException("Unpaired caller");
  }

  public static boolean equal(String a, String b) {
    return (
      a != null &&
      b != null &&
      MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8),
        b.getBytes(StandardCharsets.UTF_8)
      )
    );
  }

  public static void capability(String expected, String actual) {
    if (
      expected == null || expected.length() < 32 || !equal(expected, actual)
    ) throw new SecurityException("Invalid local capability");
  }
}
