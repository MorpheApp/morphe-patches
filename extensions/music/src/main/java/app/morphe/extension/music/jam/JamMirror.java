package app.morphe.extension.music.jam;
import android.content.Context;
import app.morphe.jam.ipc.QueueEdits;
import org.json.*;
import java.util.*;

/** Native adapters display authoritative state plus unacknowledged local gestures. */
public final class JamMirror {
    private static volatile NativeQueueList mirror,autoplay;
    private static Object original,originalAutoplay;
    private static YtmBridge.QueueAccess owner;
    private static Context context;
    private static volatile JSONObject snapshot;
    private static String rendered="";
    private static volatile int current=-1;
    private static final QueueEdits edits=new QueueEdits();
    private static boolean sending;
    private static long epoch;
    public static void accept(Context c,JSONObject view){
        context=c;JSONObject session=view.optJSONObject("session");if(session==null)return;
        boolean participant="Participant".equals(session.optString("role"));
        YtmBridge.QueueAccess access;try{access=YtmBridge.access();}catch(Exception e){return;}
        access.patch_jamViewThread(()->{try{
            if(!participant){
                if(owner==access&&mirror!=null&&access.patch_jamDisplayedList()==mirror)access.patch_jamDisplayedList(original);
                if(owner==access&&autoplay!=null&&access.patch_jamDisplayedAutoplay()==autoplay)access.patch_jamDisplayedAutoplay(originalAutoplay);
                boolean wasActive=mirror!=null;mirror=null;autoplay=null;original=null;originalAutoplay=null;owner=null;snapshot=null;rendered="";current=-1;edits.clear();sending=false;epoch++;if(wasActive){JamArtwork.clear();JamPlayback.refresh();}return;
            }
            if(!view.has("items"))return;
            if(owner!=access){mirror=null;autoplay=null;rendered="";owner=access;original=access.patch_jamDisplayedList();originalAutoplay=access.patch_jamDisplayedAutoplay();edits.clear();sending=false;epoch++;}
            if(original==null||originalAutoplay==null){original=access.patch_jamDisplayedList();originalAutoplay=access.patch_jamDisplayedAutoplay();if(original==null||originalAutoplay==null)return;}
            if(mirror==null){mirror=new NativeQueueList();autoplay=new NativeQueueList();}
            edits.accept(view);render();
        }catch(Exception e){android.util.Log.e("MorpheJam","Native mirror failed",e);}});
    }
    private static void render()throws Exception{
        JSONObject view=edits.view();if(view==null||owner==null)return;
        JSONArray rows=view.getJSONArray("items"),suggested=view.optJSONArray("autoplay");
        String signature=rows.toString()+String.valueOf(suggested);
        if(signature.equals(rendered))return;
        List<Object> next=new ArrayList<>(),future=new ArrayList<>();int playing=-1;
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);next.add(item(row));if(row.optBoolean("current"))playing=i;}
        if(suggested!=null)for(int i=0;i<suggested.length();i++)future.add(item(suggested.getJSONObject(i)));
        mirror.replace(next);autoplay.replace(future);snapshot=view;current=playing;rendered=signature;
        if(owner.patch_jamDisplayedList()!=mirror)owner.patch_jamDisplayedList(mirror);else owner.patch_jamRefreshDisplay();
        if(owner.patch_jamDisplayedAutoplay()!=autoplay)owner.patch_jamDisplayedAutoplay(autoplay);else owner.patch_jamRefreshAutoplay();
        if(playing>=0){JSONObject row=rows.getJSONObject(playing);JamArtwork.update(QueueModel.thumbnail(row.optString("thumbnail"),row.getString("videoId")));}
        JamPlayback.refresh();
    }
    private static Object item(JSONObject row)throws Exception{return owner.patch_jamCreateItem(QueueModel.encode(row.getString("videoId"),row.optString("title"),row.optString("artist"),row.optString("thumbnail")),Long.parseLong(row.getString("id")));}
    public static Object now(){NativeQueueList list=mirror;int index=current;return list!=null&&index>=0&&index<list.size()?list.get(index):null;}
    public static int current(Object list){return list==mirror?current:list==autoplay?-1:-2;}
    public static int selection(Object item){NativeQueueList main=mirror,future=autoplay;if(main==null)return -1;int index=main.indexOf(item);return index>=0?(index==current?1:0):future!=null&&future.contains(item)?0:-1;}
    public static boolean active(){return mirror!=null;}
    public static boolean move(Object list,int from,int to){return (list==mirror||list==autoplay)&&move(list==autoplay?1:0,from,to);}
    public static boolean remove(Object nativeItem){
        if(mirror==null||owner==null||(!mirror.contains(nativeItem)&&!autoplay.contains(nativeItem)))return false;
        try{queue(JamUi.command("REMOVE").put("item",Long.toString(owner.patch_jamItemId(nativeItem))).put("lane",autoplay.contains(nativeItem)?1:0));return true;}catch(Exception e){return false;}
    }
    public static boolean move(int lane,int from,int to){
        if(mirror==null)return false;
        try{JSONArray rows=snapshot.getJSONArray(lane==0?"items":"autoplay");
            if(from!=to)queue(JamUi.command("MOVE").put("item",rows.getJSONObject(from).getString("id")).put("anchor",rows.getJSONObject(to).getString("id")).put("after",from<to).put("lane",lane));
        }catch(Exception e){JamUi.toast(context,"Queue changed; try the gesture again");}return true;
    }
    private static void queue(JSONObject command)throws Exception{
        edits.add(command);
        // Publish the optimistic order after the native gesture callback has returned.
        owner.patch_jamViewThread(()->{try{render();sendNext();}catch(Exception e){JamUi.toast(context,e.getMessage());}});
    }
    private static void sendNext(){
        if(sending||!edits.busy())return;final long generation=epoch;
        try{JSONObject request=edits.next();sending=true;
            JamUi.call(context,request,response->{if(generation!=epoch)return;sending=false;
                try{edits.complete(response);render();}catch(Exception e){android.util.Log.e("MorpheJam","Edit acknowledgement failed",e);}
                if(!response.optBoolean("ok"))JamUi.toast(context,response.optString("error"));sendNext();
            });
        }catch(Exception e){try{edits.complete(new JSONObject());render();}catch(Exception ignored){}JamUi.toast(context,e.getMessage());sendNext();}
    }
}
