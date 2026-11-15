import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Read-only, host-side checks for the required NSFW patch anchors. No device access. */
public final class InspectReddit {
    private static final Map<String, Integer> counts = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected path to base APK");
        File apk = new File(args[0]);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(apk.toPath()));
        System.out.println("Base APK SHA-256: " + HexFormat.of().formatHex(digest));
        for (String name : List.of("OkHttp constructor", "Adult viewing row", "Adult blur row",
                "Account adult viewing getter", "Account adult search getter")) counts.put(name, 0);
        var dexFiles = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        for (String entry : dexFiles.getDexEntryNames()) {
            for (ClassDef cls : dexFiles.getEntry(entry).getDexFile().getClasses()) {
                for (Method method : cls.getMethods()) {
                    if (cls.getType().equals("Lokhttp3/OkHttpClient;")
                            && method.getName().equals("<init>")
                            && method.getParameterTypes().equals(List.of("Lokhttp3/OkHttpClient$Builder;"))) {
                        found("OkHttp constructor", cls, method);
                    }
                    if (cls.getType().equals("Lcom/reddit/domain/model/AccountPreferences;")
                            && method.getParameterTypes().isEmpty() && method.getReturnType().equals("Z")) {
                        if (method.getName().equals("getOver18")) found("Account adult viewing getter", cls, method);
                        if (method.getName().equals("getSearchIncludeOver18")) found("Account adult search getter", cls, method);
                    }
                    if (!method.getName().equals("<init>") || method.getImplementation() == null) continue;
                    for (var instruction : method.getImplementation().getInstructions()) {
                        if (!(instruction instanceof ReferenceInstruction ref)
                                || !(ref.getReference() instanceof StringReference string)) continue;
                        String key = switch (string.getString()) {
                            case "key_pref_over18" -> "Adult viewing row";
                            case "key_pref_blur_nsfw" -> "Adult blur row";
                            default -> null;
                        };
                        if (key == null) continue;
                        List<Method> sets = new ArrayList<>();
                        for (Method candidate : cls.getMethods()) {
                            if (candidate.getParameterTypes().isEmpty()
                                    && candidate.getReturnType().equals("Ljava/util/Set;")) sets.add(candidate);
                        }
                        if (sets.size() != 1) throw new IllegalStateException("Ambiguous session getter for " + key);
                        found(key, cls, sets.get(0));
                    }
                }
            }
        }
        for (var count : counts.entrySet()) {
            if (count.getValue() != 1) throw new IllegalStateException(count.toString());
        }
        System.out.println("PASS: all 5 required anchors match exactly once.");
        System.out.println("Static anchor checks only; not Desktop CLI --validate or runtime verification.");
    }

    private static void found(String key, ClassDef cls, Method method) {
        counts.compute(key, (ignored, count) -> count + 1);
        System.out.println(key + ": " + cls.getType() + "->" + method.getName()
                + method.getParameterTypes() + method.getReturnType());
    }
}
