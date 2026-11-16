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
        boolean included = false;
        for (var instruction : method(classes.get(EXTENSION), "isPatchIncluded").getImplementation().getInstructions()) {
            if (instruction instanceof com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction literal
                    && literal.getNarrowLiteral() == 1) included = true;
        }
        require(included, "The extension's inclusion marker is not enabled");
        require(calls(method(classes.get(EXTENSION), "isEnabled"), EXTENSION, "getDisabler"),
                "The permanent preference read was optimized out");
        boolean modernApi = false;
        for (Method candidate : classes.get(PREFS).getMethods()) {
            if (candidate.getName().equals("getNsfwEnabledPreferenceState")) modernApi = true;
        }
        if (modernApi) {
            Method modern = method(classes.get(PREFS), "getNsfwEnabledPreferenceState");
            boolean disabled = false;
            for (var instruction : modern.getImplementation().getInstructions()) {
                if (instruction instanceof ReferenceInstruction reference
                        && reference.getReference() instanceof FieldReference field
                        && field.getDefiningClass().equals(PREFS.substring(0, PREFS.length() - 1) + "$PreferenceState;")
                        && field.getName().equals("Disabled")) disabled = true;
            }
            require(disabled && calls(modern, EXTENSION, "isEnabled"), "Modern native policy is not forced Disabled");
        }
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
        if (modernApi) {
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
        } else {
            require(calls(method(classes.get(PREFS), "setOver18"), EXTENSION, "allowNsfw"),
                    "Legacy native viewing setter can still enable NSFW");
        }
        Method recentSetup = method(classes.get(
                "Lcom/reddit/screens/pager/v2/SubredditPagerViewModel$setUpSubreddit$3;"), "invokeSuspend");
        MethodReference recentWriter = null;
        for (var instruction : recentSetup.getImplementation().getInstructions()) {
            if (instruction instanceof ReferenceInstruction reference
                    && reference.getReference() instanceof MethodReference call
                    && call.getParameterTypes().toString().equals(
                            "[Lcom/reddit/domain/model/Subreddit;, Lkotlin/coroutines/jvm/internal/ContinuationImpl;]")) {
                recentWriter = call;
            }
        }
        require(recentWriter != null, "Native recent-visit writer not found");
        boolean guardedRecentWriter = false;
        for (Method candidate : classes.get(recentWriter.getDefiningClass()).getMethods()) {
            if (candidate.toString().equals(recentWriter.toString())) {
                guardedRecentWriter = calls(candidate, EXTENSION, "skipRecentSubreddit");
            }
        }
        require(guardedRecentWriter, "Flagged communities can still be recorded as recently visited");
        for (String owner : new String[]{
                "Lcom/reddit/screens/drawer/community/CommunityDrawerPresenter$refreshRecentlyVisited$1;",
                "Lcom/reddit/screens/drawer/community/recentlyvisited/RecentlyVisitedPresenter$loadRecentlyVisited$1;"
        }) {
            require(calls(method(classes.get(owner), "invokeSuspend"), EXTENSION, "visibleRecentSubreddits"),
                    "A recently visited view still shows old flagged communities: " + owner);
        }
        require(calls(method(classes.get(
                        "Lcom/reddit/agegating/impl/nsfw/NsfwBottomSheetViewModel$1;"),
                        "access$invokeSuspend$handleEvent"), EXTENSION, "isEnabled"),
                "Declining the native warning does not use the sheet's back callback");
        checkWarningReceiver(method(classes.get(
                "Lcom/reddit/agegating/impl/nsfw/NsfwBottomSheetViewModel$1;"),
                "access$invokeSuspend$handleEvent"));
        require(calls(method(classes.get(
                        "Lcom/reddit/agegating/impl/nsfw/composables/NsfwBottomSheet;"),
                        modernApi ? "V2" : "R2"), EXTENSION, "isEnabled"),
                "Android back is still disabled on the native warning sheet");
        ClassDef searchBanner = null;
        for (ClassDef candidate : classes.values()) {
            Method toString = null;
            for (Method method : candidate.getMethods()) {
                if (method.getName().equals("toString")) toString = method;
            }
            if (toString != null && containsString(toString, "SearchBannerViewState(id=")) {
                searchBanner = candidate;
                break;
            }
        }
        require(searchBanner != null && calls(method(searchBanner, "<init>"), EXTENSION, "isEnabled"),
                "Search banners can still prompt to adjust NSFW settings");
        require(calls(method(classes.get(
                        "Lcom/reddit/search/combined/ui/CombinedSearchResultsViewModel;"), "O"),
                        EXTENSION, "isEnabled"),
                "The native SafeSearch dropdown is still shown");
        for (String type : classes.keySet()) {
            require(!type.endsWith("/NsfwCommunityGate;") && !type.endsWith("/NsfwJsonFilter;")
                    && !type.endsWith("/RedditNsfwInterceptor;") && !type.endsWith("/BlockedNsfwHosts;"),
                    "Retired custom filtering code is present; use an original APK: " + type);
        }
        require(!calls(method(classes.get("Lcom/reddit/domain/model/Subreddit;"), "getOver18"), EXTENSION, "isEnabled"),
                "Community adult labels must remain intact for native gating");
        System.out.println("PASS: " + (modernApi ? "native Disabled enum" : "native legacy-off policy")
                + ", safe-search on, guarded setter and recent visits, intact labels, no custom content overlay");
    }

    private static Method method(ClassDef cls, String name) {
        if (cls == null) throw new IllegalStateException("Missing class for " + name);
        for (Method method : cls.getMethods()) if (method.getName().equals(name)) return method;
        throw new IllegalStateException("Missing method " + name);
    }

    // The native receiver load reuses p0. Injecting after it caused the .8 VerifyError.
    private static void checkWarningReceiver(Method method) {
        var instructions = new java.util.ArrayList<com.android.tools.smali.dexlib2.iface.instruction.Instruction>();
        method.getImplementation().getInstructions().forEach(instructions::add);
        for (int i = 0; i < instructions.size(); i++) {
            var instruction = instructions.get(i);
            if (!(instruction instanceof ReferenceInstruction reference)
                    || !(reference.getReference() instanceof FieldReference field)
                    || !field.getDefiningClass().equals("Lcom/reddit/agegating/impl/nsfw/NsfwBottomSheetViewModel;")
                    || !field.getType().equals("Lkotlin/jvm/functions/Function0;")) continue;
            if (i == 0 || instructions.get(i - 1).getOpcode() != com.android.tools.smali.dexlib2.Opcode.IF_EQZ) continue;
            var branch = (com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction) instructions.get(i - 1);
            int offset = 0, target = i - 1;
            while (offset < branch.getCodeOffset()) offset += instructions.get(target++).getCodeUnits();
            while (instructions.get(target).getOpcode() == com.android.tools.smali.dexlib2.Opcode.NOP) target++;
            var nativeRead = instructions.get(target);
            require(nativeRead.getOpcode() == com.android.tools.smali.dexlib2.Opcode.IGET_OBJECT
                    && ((com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction) nativeRead).getRegisterB()
                    == ((com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction) instruction).getRegisterB(),
                    "Warning callback reads a receiver already overwritten by the native field load");
            return;
        }
        throw new IllegalStateException("Missing guarded warning callback field read");
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

    private static boolean containsString(Method method, String value) {
        if (method.getImplementation() == null) return false;
        for (var instruction : method.getImplementation().getInstructions()) {
            if (instruction instanceof ReferenceInstruction reference
                    && reference.getReference() instanceof com.android.tools.smali.dexlib2.iface.reference.StringReference text
                    && text.getString().equals(value)) return true;
        }
        return false;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
