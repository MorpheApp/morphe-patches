import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Structural regression checks on the rebuilt APK, not proof of device behavior. */
public final class CheckNativeNsfwPatch {
    private static final String EXTENSION = "Lapp/morphe/extension/reddit/patches/PermanentNsfwPatch;";
    private static final String PREFS = "Lcom/reddit/domain/model/AccountPreferences;";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected rebuilt APK");
        Map<String, ClassDef> classes = new HashMap<>();
        var apk = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        for (String entry : apk.getDexEntryNames()) {
            for (var cls : apk.getEntry(entry).getDexFile().getClasses()) classes.put(cls.getType(), cls);
        }
        Method modern = method(classes.get(PREFS), "getNsfwEnabledPreferenceState");
        boolean disabled = false;
        for (var instruction : modern.getImplementation().getInstructions()) {
            if (instruction instanceof ReferenceInstruction reference
                    && reference.getReference() instanceof FieldReference field
                    && field.getDefiningClass().equals(PREFS.substring(0, PREFS.length() - 1) + "$PreferenceState;")
                    && field.getName().equals("Disabled")) disabled = true;
        }
        require(disabled && calls(modern, EXTENSION, "isEnabled"), "Modern native policy is not forced Disabled");
        for (String name : new String[]{"getOver18", "getSearchIncludeOver18"}) {
            require(calls(method(classes.get(PREFS), name), EXTENSION, "isEnabled"), "Missing legacy preference guard: " + name);
        }
        Method safeSearch = method(classes.get("Lcom/reddit/domain/SafeSearch;"), "getValue");
        boolean safeValue = false;
        for (var instruction : safeSearch.getImplementation().getInstructions()) {
            if (instruction instanceof ReferenceInstruction reference
                    && reference.getReference() instanceof com.android.tools.smali.dexlib2.iface.reference.StringReference value
                    && value.getString().equals("on")) safeValue = true;
        }
        require(safeValue && calls(safeSearch, EXTENSION, "isEnabled"), "Anonymous safe search can still serialize off");
        ClassDef continuation = classes.get("Lcom/reddit/account/repository/RedditPreferenceRepository$setOver18$1;");
        MethodReference setter = null;
        for (var instruction : method(continuation, "invokeSuspend").getImplementation().getInstructions()) {
            if (instruction instanceof ReferenceInstruction reference
                    && reference.getReference() instanceof MethodReference call
                    && call.getParameterTypes().toString().equals("[Z, Z, Lkotlin/coroutines/jvm/internal/ContinuationImpl;]")) setter = call;
        }
        require(setter != null, "Native viewing setter not found");
        boolean guardedSetter = false;
        for (Method candidate : classes.get(setter.getDefiningClass()).getMethods()) {
            if (candidate.toString().equals(setter.toString())) guardedSetter = calls(candidate, EXTENSION, "allowNsfw");
        }
        require(guardedSetter, "Native viewing setter can still enable NSFW");
        for (String type : classes.keySet()) {
            require(!type.endsWith("/NsfwCommunityGate;") && !type.endsWith("/NsfwJsonFilter;")
                    && !type.endsWith("/RedditNsfwInterceptor;") && !type.endsWith("/BlockedNsfwHosts;"),
                    "Retired custom filtering code is present; use an original APK: " + type);
        }
        require(!calls(method(classes.get("Lcom/reddit/domain/model/Subreddit;"), "getOver18"), EXTENSION, "isEnabled"),
                "Community adult labels must remain intact for native gating");
        System.out.println("PASS: modern Disabled policy, legacy guards, native setter guard, intact community labels, no custom filtering/overlay");
    }

    private static Method method(ClassDef cls, String name) {
        if (cls == null) throw new IllegalStateException("Missing class for " + name);
        for (Method method : cls.getMethods()) if (method.getName().equals(name)) return method;
        throw new IllegalStateException("Missing method " + name);
    }

    private static boolean calls(Method method, String type, String name) {
        if (method.getImplementation() == null) return false;
        for (var instruction : method.getImplementation().getInstructions()) {
            if (instruction instanceof ReferenceInstruction reference
                    && reference.getReference() instanceof MethodReference call
                    && call.getDefiningClass().equals(type) && call.getName().equals(name)) return true;
        }
        return false;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
