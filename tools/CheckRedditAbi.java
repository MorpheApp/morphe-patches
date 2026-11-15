import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import java.io.File;
import java.util.HashSet;
import java.util.Set;

/** Checks exact network API descriptors against the host, including obfuscation. */
public final class CheckRedditAbi {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected base APK and extension DEX");
        Set<String> available = new HashSet<>();
        var apk = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        for (String entry : apk.getDexEntryNames()) {
            for (var cls : apk.getEntry(entry).getDexFile().getClasses()) {
                if (!networkType(cls.getType())) continue;
                for (var method : cls.getMethods()) available.add(method.toString());
            }
        }
        Set<String> checked = new HashSet<>();
        var extension = DexFileFactory.loadDexFile(new File(args[1]), Opcodes.getDefault());
        for (var cls : extension.getClasses()) {
            if (!cls.getType().contains("/nsfw/") && !cls.getType().contains("/PermanentNsfw")) continue;
            for (var method : cls.getMethods()) {
                if (method.getImplementation() == null) continue;
                for (var instruction : method.getImplementation().getInstructions()) {
                    if (!(instruction instanceof ReferenceInstruction reference)
                            || !(reference.getReference() instanceof MethodReference target)
                            || !networkType(target.getDefiningClass())) continue;
                    if (!available.contains(target.toString())) {
                        throw new IllegalStateException("Missing network API: " + target);
                    }
                    checked.add(target.toString());
                }
            }
        }
        if (checked.isEmpty()) throw new IllegalStateException("No network API calls checked");
        System.out.println("PASS: " + checked.size() + " exact network API descriptors exist in the host APK");
    }

    private static boolean networkType(String type) {
        return type.startsWith("Lokhttp3/") || type.startsWith("Lokio/");
    }
}
