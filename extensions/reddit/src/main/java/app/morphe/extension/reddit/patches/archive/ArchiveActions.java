package app.morphe.extension.reddit.patches.archive;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Main-thread subscriptions; native requests run off the UI thread and are account scoped. */
final class ArchiveActions {
    private interface Backend {
        Object scope();
        boolean signedIn(Object scope);
        Loaded load(Object scope, String fullname, boolean comment) throws Exception;
        boolean vote(Object scope, String fullname, boolean comment, int direction) throws Exception;
        Integer cachedDirection(String fullname);
        void publish(String fullname, int direction);
    }
    private static final Backend backend = new Backend() {
        public Object scope() { return NativeArchiveActions.scope(); }
        public boolean signedIn(Object scope) { return NativeArchiveActions.signedIn(scope); }
        public Loaded load(Object scope, String fullname, boolean comment) throws Exception {
            Object service = NativeArchiveActions.service(scope, comment);
            ArchiveActionContinuation callback = new ArchiveActionContinuation();
            Object model = NativeArchiveActions.unwrap(callback.await(NativeArchiveActions.load(service, fullname, comment, callback)));
            return new Loaded(NativeArchiveActions.state(model, comment), comment ? null : NativeArchiveActions.postInfo(model));
        }
        public boolean vote(Object scope, String fullname, boolean comment, int direction) throws Exception {
            Object service = NativeArchiveActions.service(scope, comment);
            ArchiveActionContinuation callback = new ArchiveActionContinuation();
            return NativeArchiveActions.success(callback.await(
                    NativeArchiveActions.vote(service, fullname, comment, direction, callback)), comment);
        }
        public Integer cachedDirection(String fullname) { return NativeArchiveActions.cachedDirection(fullname); }
        public void publish(String fullname, int direction) { NativeArchiveActions.publish(fullname, direction); }
    };
    interface Observer { void changed(Entry entry); }
    private static final class Loaded {
        final ArchiveVoteState state;
        final String[] post;
        Loaded(ArchiveVoteState state, String[] post) { this.state = state; this.post = post; }
    }
    static final class Entry {
        final Object scope;
        final String fullname;
        final boolean comment;
        final ArrayList<Observer> observers = new ArrayList<>();
        ArchiveVoteState state;
        boolean loading, pending, signedIn;
        boolean loadedPost, joined, crosspostable, joinPending;
        String subredditId = "", communityIcon;
        Object joinEvent;
        int membershipVersion;
        String error = "";
        long loadedAt;
        volatile int generation;
        Entry(Object scope, ArchiveItem item) {
            this.scope = scope; comment = item.comments; fullname = (comment ? "t1_" : "t3_") + item.id;
        }
        void notifyObservers() { for (Observer observer : new ArrayList<>(observers)) observer.changed(this); }
    }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final LinkedHashMap<String, Entry> ENTRIES = new LinkedHashMap<>(32, .75f, true);
    private static Object activeScope;

    private static Object currentScope() {
        Object scope = backend.scope();
        if (activeScope != scope) {
            for (Entry old : new ArrayList<>(ENTRIES.values())) {
                old.generation++; old.state = null; old.loading = false; old.pending = false; old.signedIn = false;
                old.joinEvent = null; old.joinPending = false; old.joined = false; old.loadedPost = false;
                old.error = "Account changed. Reopen this profile to vote."; old.notifyObservers();
            }
            ENTRIES.clear(); activeScope = scope;
        }
        return scope;
    }
    static Entry observe(ArchiveItem item, Observer observer) {
        Object scope = currentScope();
        String fullname = (item.comments ? "t1_" : "t3_") + item.id;
        Entry entry = ENTRIES.get(fullname);
        if (entry == null) { entry = new Entry(scope, item); ENTRIES.put(fullname, entry); }
        if (!entry.observers.contains(observer)) entry.observers.add(observer);
        entry.signedIn = scope != null && backend.signedIn(scope);
        observer.changed(entry);
        if (scope != null && !entry.pending && !entry.loading &&
                (entry.state == null || System.currentTimeMillis() - entry.loadedAt > 15000)) load(entry);
        trim(); return entry;
    }
    static void unobserve(Entry entry, Observer observer) {
        if (entry != null) {
            entry.observers.remove(observer);
            if (entry.observers.isEmpty() && entry.loading) { entry.generation++; entry.loading = false; }
        }
        trim();
    }
    private static void trim() {
        if (ENTRIES.size() <= 128) return;
        java.util.Iterator<Map.Entry<String, Entry>> iterator = ENTRIES.entrySet().iterator();
        while (ENTRIES.size() > 128 && iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.observers.isEmpty() && !entry.pending && !entry.loading && !entry.joinPending) iterator.remove();
        }
    }
    static void refresh(Entry entry) {
        if (entry == null || entry.scope != currentScope()) return;
        if (entry.scope != null && !entry.loading && !entry.pending) load(entry);
    }
    private static void load(Entry entry) {
        entry.loading = true; entry.error = ""; int generation = ++entry.generation; entry.notifyObservers();
        int membershipVersion = entry.membershipVersion;
        IO.execute(() -> {
            if (generation != entry.generation) return;
            Loaded loaded = null; String error = "";
            try {
                loaded = backend.load(entry.scope, entry.fullname, entry.comment);
                if (loaded == null || loaded.state == null) throw new IllegalStateException("Missing live item");
            } catch (Exception failure) { error = "Could not load voting. Tap an arrow to retry."; }
            Loaded result = loaded; String message = error;
            MAIN.post(() -> {
                if (entry.scope != currentScope() || generation != entry.generation) return;
                entry.loading = false; entry.state = result == null ? null : result.state; entry.error = message; entry.loadedAt = System.currentTimeMillis();
                if (result != null) {
                    Integer cached = backend.cachedDirection(entry.fullname);
                    if (entry.signedIn && cached != null && cached >= -1 && cached <= 1) entry.state = result.state.withDirection(cached);
                    if (result.post != null) {
                        entry.subredditId = result.post[0]; entry.communityIcon = ArchivePostDetails.imageUrl(result.post[1]);
                        if (entry.membershipVersion == membershipVersion) entry.joined = Boolean.parseBoolean(result.post[2]);
                        entry.crosspostable = Boolean.parseBoolean(result.post[3]);
                        entry.loadedPost = true;
                    }
                }
                entry.notifyObservers(); trim();
            });
        });
    }
    static void join(Context context, Entry entry, Object eventContext, ArchiveItem item) {
        if (entry == null || item == null || item.comments || entry.scope != currentScope() || entry.joinPending) return;
        if (!entry.signedIn) { signIn(context); return; }
        if (entry.joined) return;
        if (!entry.loadedPost) { if (!entry.loading) load(entry); return; }
        if (eventContext == null || entry.subredditId == null || entry.subredditId.isEmpty()) {
            toast(context, "This community is unavailable."); return;
        }
        Object event = NativeArchiveActions.joinEvent(entry.fullname, entry.subredditId, item.subreddit);
        entry.joinEvent = event; entry.joinPending = true; entry.notifyObservers();
        NativeArchiveActions.dispatch(eventContext, event);
        // A cancelled native sign-in or confirmation sheet may complete without a result.
        MAIN.postDelayed(() -> {
            if (entry.joinEvent == event) { entry.joinEvent = null; entry.joinPending = false; entry.notifyObservers(); trim(); }
        }, 30000);
    }
    static void joinResult(Object event, boolean success) {
        MAIN.post(() -> {
            Object scope = currentScope();
            for (Entry entry : new ArrayList<>(ENTRIES.values())) if (entry.scope == scope && entry.joinEvent == event) {
                entry.joinEvent = null; entry.joinPending = false;
                if (success) for (Entry other : new ArrayList<>(ENTRIES.values())) {
                    if (other.scope == scope && entry.subredditId.equals(other.subredditId)) {
                        other.membershipVersion++; other.joined = true; other.notifyObservers();
                    }
                }
                entry.notifyObservers();
            }
            trim();
        });
    }
    static void repost(Context context, Entry entry, Object eventContext, ArchiveItem item) {
        if (entry == null || item == null || item.comments || entry.scope != currentScope()) return;
        if (!entry.signedIn) { signIn(context); return; }
        if (entry.loadedPost && !entry.crosspostable) { toast(context, "This post cannot be reposted."); return; }
        if (eventContext == null) { toast(context, "Open the discussion to repost."); return; }
        NativeArchiveActions.dispatch(eventContext, NativeArchiveActions.repostEvent(entry.fullname,
                entry.subredditId == null || entry.subredditId.isEmpty() ? null : entry.subredditId));
    }
    static void vote(Context context, Entry entry, int requested) {
        if (entry == null) return;
        if (entry.scope != currentScope()) { toast(context, "Account changed. Reopen this profile to vote."); return; }
        if (!entry.signedIn) { signIn(context); return; }
        if (entry.pending || entry.loading) return;
        if (entry.state == null) { load(entry); return; }
        if (!entry.state.allowed) { toast(context, entry.state.reason); return; }
        ArchiveVoteState previous = entry.state;
        int next = previous.toggle(requested), generation = ++entry.generation;
        entry.state = previous.withDirection(next); entry.pending = true; entry.error = ""; entry.notifyObservers();
        IO.execute(() -> {
            boolean success = false;
            try {
                // Resolve the service from this exact user scope, never a later active account.
                if (entry.scope != backend.scope()) throw new IllegalStateException("Account changed");
                success = backend.vote(entry.scope, entry.fullname, entry.comment, next);
            } catch (Exception ignored) { }
            boolean accepted = success;
            MAIN.post(() -> {
                if (entry.scope != currentScope() || generation != entry.generation) return;
                entry.pending = false; entry.loadedAt = System.currentTimeMillis();
                if (accepted) backend.publish(entry.fullname, next);
                else { entry.state = previous; entry.error = "Vote was not confirmed. Try again."; }
                entry.notifyObservers();
                if (!accepted && !entry.observers.isEmpty()) toast(context, entry.error);
                trim();
            });
        });
    }
    static Intent shareIntent(ArchiveItem item) {
        return new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, item.discussionUrl());
    }
    static void share(Context context, ArchiveItem item) {
        try { context.startActivity(Intent.createChooser(shareIntent(item), "Share link")); }
        catch (ActivityNotFoundException failure) { toast(context, "No app is available to share this link."); }
    }
    private static void signIn(Context context) {
        try { context.startActivity(new Intent().setClassName(context.getPackageName(), "com.reddit.auth.login.screen.AuthActivityKt")); }
        catch (ActivityNotFoundException failure) { toast(context, "Sign in to Reddit to vote."); }
    }
    private static void toast(Context context, String text) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show(); }
}
