package app.morphe.jam.ipc;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

public final class Trust {
    public static final String COMPANION = "app.morphe.jam.companion";
    public static final String BRIDGE_SERVICE = "app.morphe.extension.music.jam.JamBridgeService";
    // Release signer certificate SHA-256. Keep in sync with the matching Morphe Jam patch source.
    public static final String COMPANION_CERT = "46f5e42763b17777fb527b1477ae88b3649ec6c14674d232477e70d6e7e91940";
    public static String certificate(Context context, String pkg) {
        try {
            Signature[] signatures = context.getPackageManager().getPackageInfo(pkg,
                    PackageManager.GET_SIGNATURES).signatures;
            if (signatures == null || signatures.length != 1) throw new SecurityException("Signer count");
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
            StringBuilder result = new StringBuilder();
            for (byte b : hash) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (Exception error) { throw new SecurityException("Package signature unavailable", error); }
    }
    public static void caller(Context context, String pkg, String cert) {
        String[] packages = context.getPackageManager().getPackagesForUid(Binder.getCallingUid());
        // Reject shared-UID packages: authority must belong exclusively to the paired app.
        if (packages == null || packages.length != 1 || !pkg.equals(packages[0]) ||
                !equal(cert, certificate(context, pkg))) throw new SecurityException("Unpaired caller");
    }
    public static boolean equal(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
    public static void capability(String expected, String actual) {
        if (expected == null || expected.length() < 32 || !equal(expected, actual))
            throw new SecurityException("Invalid local capability");
    }
}
