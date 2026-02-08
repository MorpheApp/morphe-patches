package app.morphe.extension.reddit.patches;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Environment;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Base64;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

//
// Adapted from:
// https://github.com/L-JINBIN/ApkSignatureKillerEx/blob/3e6a8dc7de1b108dc70647f62bc499d7b68852b2/killer/src/main/java/bin/mt/signature/KillerApplication.java
//
@SuppressWarnings("all")
public class SpoofSignaturePatch extends Application {
    static {
        String packageName = "com.reddit.frontpage";
        String certificateData = "MIIDWzCCAkOgAwIBAgIEVA7dvDANBgkqhkiG9w0BAQsFADBeMQswCQYDVQQGEwJVUzELMAkGA1UECBMCQ0ExFjAUBgNVBAcTDVNhbiBGcmFuY2lzY28xFDASBgNVBAoTC3JlZGRpdCBJbmMuMRQwEgYDVQQDEwtNb2JpbGUgQXBwczAeFw0xNDAzMDEwMDMxMTJaFw0zOTAyMjMwMDMxMTJaMF4xCzAJBgNVBAYTAlVTMQswCQYDVQQIEwJDQTEWMBQGA1UEBxMNU2FuIEZyYW5jaXNjbzEUMBIGA1UEChMLcmVkZGl0IEluYy4xFDASBgNVBAMTC01vYmlsZSBBcHBzMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxIOFo/qVmYq3S4ZKNMSMT9UjU0kZQDDThODZM5o1q3NlIlX2XOP/BNP/kCT4Xww69frbKj6Izd2jn/xOvA+JMqdEFjlRWoF7poJzdKA2zw3L814pzZUhIF1IIoUJ6YiHOkU4hiqvdn/VjRTB5rIgw0bs9NoEIYynp3qcVwCuEU2TyEy4F9aUU1YsfejUWd3GoWH2dZqu+Hg5yJKWBpWAWjOEK1f2ltc+JtGLl9AReDsfTYBxBobqZCK4yKsq65YVL7flp7MkQ/fgcrXWgknUpC6Pb+MKgnK083noS8HNlsUOgW57fJj6mmcebW9fnDpl6FBzxdDerGZ5AxG/yb0B9QIDAQABoyEwHzAdBgNVHQ4EFgQUoq623hlCFRHg6xsCZHTVrgMZ6cswDQYJKoZIhvcNAQELBQADggEBAJR0XZDyZgIqhZPNtz9yn4kdxoUg9d7hRcleSvggJc5OAc3dcIrTXyRf2vkL1KJM+/zTOs0DwNm6rYuQhKDvZ9XtbMllkn2yykjs/xR/4BXqhyQp8sOVr5wRatG6aIbVJ52hldNHY352EgWYLwvz0L7GNcSB+vO3eIkbgdavFgnQWaoVyfoWB0bqky7079hfXHMRPyiZ0ZLwwApqHjDZSaU4gbgjrx5ni8BzchHerwxjsE4yLamkUTZ5/SHpIsJv9ychDwpLbguYRGTyiWNwRZoRurFqdGatHAh0OAr5E9IXNi9EoKksC2RjatfMllQETns7PDbxQ1/tB8DExpyB71U=";
        killPM(packageName, certificateData);
        killOpen(packageName);
    }

    private static void killPM(String packageName, String certificateData) {
        Signature fakeSignature = new Signature(Base64.decode(certificateData, Base64.DEFAULT));
        Parcelable.Creator<PackageInfo> originalCreator = PackageInfo.CREATOR;
        Parcelable.Creator<PackageInfo> creator = new Parcelable.Creator<PackageInfo>() {
            @Override
            public PackageInfo createFromParcel(Parcel source) {
                PackageInfo packageInfo = originalCreator.createFromParcel(source);
                if (packageInfo.packageName.equals(packageName)) {
                    if (packageInfo.signatures != null && packageInfo.signatures.length > 0) {
                        packageInfo.signatures[0] = fakeSignature;
                    }
                    if (packageInfo.signingInfo != null) {
                        Signature[] signaturesArray = packageInfo.signingInfo.getApkContentsSigners();
                        if (signaturesArray != null && signaturesArray.length > 0) {
                            signaturesArray[0] = fakeSignature;
                        }
                    }
                }
                return packageInfo;
            }

            @Override
            public PackageInfo[] newArray(int size) {
                return originalCreator.newArray(size);
            }
        };
        try {
            findField(PackageInfo.class, "CREATOR").set(null, creator);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        HiddenApiBypass.addHiddenApiExemptions("Landroid/os/Parcel;", "Landroid/content/pm", "Landroid/app");
        try {
            Object cache = findField(PackageManager.class, "sPackageInfoCache").get(null);
            //noinspection ConstantConditions
            cache.getClass().getMethod("clear").invoke(cache);
        } catch (Throwable ignored) {
        }
        try {
            Map<?, ?> mCreators = (Map<?, ?>) findField(Parcel.class, "mCreators").get(null);
            //noinspection ConstantConditions
            mCreators.clear();
        } catch (Throwable ignored) {
        }
        try {
            Map<?, ?> sPairedCreators = (Map<?, ?>) findField(Parcel.class, "sPairedCreators").get(null);
            //noinspection ConstantConditions
            sPairedCreators.clear();
        } catch (Throwable ignored) {
        }
    }

    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            while (true) {
                clazz = clazz.getSuperclass();
                if (clazz == null || clazz.equals(Object.class)) {
                    break;
                }
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                }
            }
            throw e;
        }
    }

    private static void killOpen(String packageName) {
        try {
            System.loadLibrary("SignatureKiller");
        } catch (Throwable e) {
            System.err.println("Load SignatureKiller library failed");
            return;
        }
        String apkPath = getApkPath(packageName);
        if (apkPath == null) {
            System.err.println("Get apk path failed");
            return;
        }
        File apkFile = new File(apkPath);
        File repFile = new File(getDataFile(packageName), "origin.apk");
        try (ZipFile zipFile = new ZipFile(apkFile)) {
            String name = "assets/SignatureKiller/origin.apk";
            ZipEntry entry = zipFile.getEntry(name);
            if (entry == null) {
                System.err.println("Entry not found: " + name);
                return;
            }
            if (!repFile.exists() || repFile.length() != entry.getSize()) {
                try (InputStream is = zipFile.getInputStream(entry); OutputStream os = new FileOutputStream(repFile)) {
                    byte[] buf = new byte[102400];
                    int len;
                    while ((len = is.read(buf)) != -1) {
                        os.write(buf, 0, len);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        hookApkPath(apkFile.getAbsolutePath(), repFile.getAbsolutePath());
    }

    private static File getDataFile(String packageName) {
        String username = Environment.getExternalStorageDirectory().getName();
        if (username.matches("\\d+")) {
            File file = new File("/data/user/" + username + "/" + packageName);
            if (file.canWrite()) {
                return file;
            }
        }
        return new File("/data/data/" + packageName);
    }

    private static String getApkPath(String packageName) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] arr = line.split("\\s+");
                String path = arr[arr.length - 1];
                if (isApkPath(packageName, path)) {
                    return path;
                }
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean isApkPath(String packageName, String path) {
        if (!path.startsWith("/") || !path.endsWith(".apk")) {
            return false;
        }
        String[] splitStr = path.substring(1).split("/", 6);
        int splitCount = splitStr.length;
        if (splitCount == 4 || splitCount == 5) {
            if (splitStr[0].equals("data") && splitStr[1].equals("app") && splitStr[splitCount - 1].equals("base.apk")) {
                return splitStr[splitCount - 2].startsWith(packageName);
            }
            if (splitStr[0].equals("mnt") && splitStr[1].equals("asec") && splitStr[splitCount - 1].equals("pkg.apk")) {
                return splitStr[splitCount - 2].startsWith(packageName);
            }
        } else if (splitCount == 3) {
            if (splitStr[0].equals("data") && splitStr[1].equals("app")) {
                return splitStr[2].startsWith(packageName);
            }
        } else if (splitCount == 6) {
            if (splitStr[0].equals("mnt") && splitStr[1].equals("expand") && splitStr[3].equals("app") && splitStr[5].equals("base.apk")) {
                return splitStr[4].endsWith(packageName);
            }
        }
        return false;
    }

    private static native void hookApkPath(String apkPath, String repPath);
}
