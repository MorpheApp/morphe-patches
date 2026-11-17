import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import java.io.File;
import java.util.*;

/** Checks the native gates and navigation independently of permanent activation. */
public final class CheckNsfwFixes {
    private static final String CONTENT = "Lapp/morphe/extension/reddit/patches/NsfwContentPatch;";
    private static final String PERMANENT = "Lapp/morphe/extension/reddit/patches/PermanentNsfwPatch;";

    public static void main(String[] args) throws Exception {
        Map<String, ClassDef> classes = new HashMap<>();
        var apk = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        for (String entry : apk.getDexEntryNames())
            for (var cls : apk.getEntry(entry).getDexFile().getClasses()) classes.put(cls.getType(), cls);
        Method viewing = null, profile = null, post = null, rendering = null, back = null;
        for (var cls : classes.values()) for (var method : cls.getMethods()) {
            if (method.getImplementation() == null) continue;
            for (var instruction : method.getImplementation().getInstructions()) {
                if (instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof StringReference str
                        && str.getString().equals("nsfw_over18_enabled") && method.getReturnType().equals("Z")) viewing = method;
            }
            if (calls(method, CONTENT, "showProfileWarning")) profile = method;
            if (method.getName().equals("patch_shouldHideNsfw")) post = method;
            if (calls(method, cls.getType(), "patch_shouldHideNsfw")) rendering = method;
            if (calls(method, "Lcom/reddit/agegating/impl/nsfw/composables/NsfwBottomSheet$onInitialize$1$1;", "<init>")) back = method;
        }
        require(viewing != null && profile != null && post != null && rendering != null && back != null, "Missing native fix hooks");
        for (var guard : List.of(profile, post)) {
            require(calls(guard, viewing.getDefiningClass(), viewing.getName()), "Guard must read the native session preference");
            require(!calls(guard, PERMANENT, "isEnabled"), "Guard incorrectly requires permanent disabling");
        }
        require(calls(post, "Lcom/reddit/domain/model/Link;", "getOver18"), "Post guard must preserve unflagged posts");
        var profileCode = instructions(profile);
        int warning = callIndex(profileCode, CONTENT, "showProfileWarning");
        require(profileCode.get(warning + 2).getOpcode() == Opcode.RETURN_OBJECT,
                "Flagged profile still falls through to model publication");
        var renderCode = instructions(rendering);
        int gate = callIndex(renderCode, post.getDefiningClass(), post.getName());
        require(renderCode.get(gate + 2).getOpcode() == Opcode.IF_EQZ
                && renderCode.get(gate + 3) instanceof NarrowLiteralInstruction hidden && hidden.getNarrowLiteral() == 1
                && renderCode.get(gate + 4) instanceof NarrowLiteralInstruction blocked && blocked.getNarrowLiteral() == 1,
                "Both native post visibility flags must be set before building the view");
        Method handler = null;
        for (var method : classes.get("Lcom/reddit/agegating/impl/nsfw/NsfwBottomSheetViewModel$1;").getMethods())
            if (method.getName().equals("access$invokeSuspend$handleEvent")) handler = method;
        require(handler != null && !calls(handler, PERMANENT, "isEnabled"), "Dismissal incorrectly requires permanent disabling");
        var code = instructions(handler);
        boolean returnsBeforeHome = false;
        for (int i = 0; i + 3 < code.size(); i++) {
            if (code.get(i) instanceof ReferenceInstruction ref && ref.getReference() instanceof FieldReference field
                    && field.getDefiningClass().equals("Lcom/reddit/agegating/impl/nsfw/NsfwBottomSheetViewModel;")
                    && field.getType().equals("Lkotlin/jvm/functions/Function0;")) {
                returnsBeforeHome = code.get(i + 3).getOpcode() == Opcode.RETURN_OBJECT;
                break;
            }
        }
        require(returnsBeforeHome, "Decline can still reach the native Home redirect");
        if (args.length > 1 && args[1].equals("--normal-off")) {
            Method included = null;
            for (var method : classes.get(PERMANENT).getMethods()) if (method.getName().equals("isPatchIncluded")) included = method;
            for (var instruction : instructions(included)) require(!(instruction instanceof NarrowLiteralInstruction value)
                    || value.getNarrowLiteral() != 1, "Standalone test accidentally includes permanent disabling");
        }
        System.out.println("PASS: native preference controls profile/post gates; hidden and blocked before view creation; Back independent of permanent disabling");
    }

    private static List<Instruction> instructions(Method method) {
        List<Instruction> result = new ArrayList<>();
        method.getImplementation().getInstructions().forEach(result::add);
        return result;
    }
    private static boolean calls(Method method, String owner, String name) {
        return method.getImplementation() != null && callIndex(instructions(method), owner, name) >= 0;
    }
    private static int callIndex(List<Instruction> code, String owner, String name) {
        for (int i = 0; i < code.size(); i++) if (code.get(i) instanceof ReferenceInstruction ref
                && ref.getReference() instanceof MethodReference call
                && call.getDefiningClass().equals(owner) && call.getName().equals(name)) return i;
        return -1;
    }
    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
