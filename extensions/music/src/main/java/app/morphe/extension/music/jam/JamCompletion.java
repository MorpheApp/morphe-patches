package app.morphe.extension.music.jam;
import java.util.*;
import java.util.concurrent.*;

/** Correlates each native async callback to its originating bridge command. */
public final class JamCompletion {
    public static final class Ticket { final CompletableFuture<Set<String>> result=new CompletableFuture<>(); }
    private static final ThreadLocal<Ticket> dispatch=new ThreadLocal<>();
    private static final Map<Object,Ticket> callbacks=Collections.synchronizedMap(new WeakHashMap<>());
    static Ticket begin(){Ticket ticket=new Ticket();dispatch.set(ticket);return ticket;}
    static void end(){dispatch.remove();}
    public static void attach(Object callback){Ticket ticket=dispatch.get();if(ticket!=null)callbacks.put(callback,ticket);}
    public static void succeeded(Object callback,List<?> items,YtmBridge.QueueAccess access){
        Ticket ticket=callbacks.remove(callback);if(ticket==null)return;
        try{Set<String> ids=new HashSet<>();for(Object item:items)if(item!=null)ids.add(Long.toString(access.patch_jamItemId(item)));ticket.result.complete(ids);}
        catch(Exception e){ticket.result.completeExceptionally(e);}
    }
    public static void failed(Object callback){Ticket ticket=callbacks.remove(callback);if(ticket!=null)ticket.result.completeExceptionally(new IllegalStateException("YouTube Music could not resolve that track"));}
}
