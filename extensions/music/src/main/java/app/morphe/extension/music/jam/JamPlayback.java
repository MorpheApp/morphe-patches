package app.morphe.extension.music.jam;

import android.app.*;
import android.view.View;
import org.json.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** Native command choices and native now-playing adapter refresh. No local audio mirror. */
public final class JamPlayback {
    public interface Router { void patch_jamDispatch(Object command,Object context); void patch_jamWatch(byte[] command); }
    public interface NowUi { void patch_jamRefreshNow(); }
    private static WeakReference<Router> router=new WeakReference<>(null);
    private static final Set<NowUi> views=Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean dialog,refreshing;
    static boolean claimDialog(){if(dialog)return false;dialog=true;return true;}
    static void releaseDialog(){dialog=false;}
    public static void capture(Router value){router=new WeakReference<>(value);}
    public static void observe(NowUi value){views.add(value);}
    public static Object chooseItem(Object local){Object host=JamMirror.now();return host==null?local:host;}
    public static void refresh(){if(refreshing)return;refreshing=true;try{for(NowUi view:new ArrayList<>(views))try{view.patch_jamRefreshNow();}catch(Exception ignored){}}finally{refreshing=false;}}
    private static boolean participant(){JSONObject session=JamUi.latest.optJSONObject("session");return JamMirror.active()||(session!=null&&"Participant".equals(session.optString("role")));}
    public static boolean offer(Router owner,Object endpoint,Object map,byte[] bytes){
        capture(owner);String video=QueueCommand.watchVideo(bytes);if(video==null||!participant())return false;
        JamUi.main.post(()->choices(video,null,()->owner.patch_jamDispatch(endpoint,map),false));return true;
    }
    public static boolean queueTap(Object item){
        if(!participant()||JamMirror.selection(item)<0)return false;
        try{YtmBridge.QueueAccess a=YtmBridge.access();String video=a.patch_jamVideoId(item),id=Long.toString(a.patch_jamItemId(item));
            JamUi.main.post(()->choices(video,id,()->local(video),true));return true;
        }catch(Exception e){return true;}
    }
    public static boolean playButton(View view){
        if(!participant())return false;
        String name="";try{name=view.getResources().getResourceEntryName(view.getId());}catch(Exception ignored){}
        if(!name.contains("play_pause_replay"))return false;
        Object item=JamMirror.now();if(item!=null)queueTap(item);else JamUi.toast(view.getContext(),"Waiting for the host queue");return true;
    }
    private static void local(String video){Router r=router.get();if(r==null)throw new IllegalStateException("Open a song in YouTube Music first");r.patch_jamWatch(QueueCommand.watch(video));}
    private static void choices(String video,String item,Runnable playLocal,boolean queue){
        Activity activity=JamUi.activity(null);if(activity==null||activity.isFinishing()||!claimDialog())return;
        String[] labels=queue?new String[]{"Play on host","Quit Jam and play locally"}:new String[]{"Play next in Jam","Add to Jam queue","Quit Jam and play locally"};
        AlertDialog popup=new AlertDialog.Builder(activity).setTitle(queue?"Play this Jam track":"Choose where to play")
            .setItems(labels,(d,index)->{
                if(index==labels.length-1){JamUi.call(activity,JamUi.command("END"),response->{
                    if(!response.optBoolean("ok")){JamUi.toast(activity,response.optString("error"));return;}
                    try{JSONObject idle=new JSONObject().put("session",new JSONObject().put("role","Idle"));JamUi.latest=idle;JamClock.clear();JamMirror.accept(activity,idle);
                        JamUi.main.post(()->{try{playLocal.run();}catch(Exception e){JamUi.toast(activity,e.getMessage());}});
                    }catch(Exception e){JamUi.toast(activity,e.getMessage());}
                });return;}
                try{JSONObject command=JamUi.command(queue?"PLAY":index==0?"PLAY_NEXT":"ADD").put("videoId",video);if(item!=null)command.put("item",item);
                    JamUi.call(activity,command,response->{if(!response.optBoolean("ok"))JamUi.toast(activity,response.optString("error"));});
                }catch(Exception e){JamUi.toast(activity,e.getMessage());}
            }).setNegativeButton("Cancel",null).setOnDismissListener(d->releaseDialog()).create();
        popup.show();JamUi.styleDialog(popup);
    }
    static void leaveAndPlay(Activity activity,String video,long position){
        JamUi.call(activity,JamUi.command("END"),response->{
            if(!response.optBoolean("ok")){JamUi.toast(activity,response.optString("error"));return;}
            try{JSONObject idle=new JSONObject().put("session",new JSONObject().put("role","Idle"));JamUi.latest=idle;JamClock.clear();JamMirror.accept(activity,idle);
                JamUi.main.post(()->{try{local(video);JamUi.main.postDelayed(()->JamClock.seekLocalWhenReady(video,position,50),300);}catch(Exception e){JamUi.toast(activity,e.getMessage());}});
            }catch(Exception e){JamUi.toast(activity,e.getMessage());}
        });
    }    static void hostWatch(byte[] endpoint)throws Exception{
        FutureTask<Void> task=new FutureTask<>(()->{Router r=router.get();if(r==null)throw new IllegalStateException("Open the host player first");r.patch_jamWatch(endpoint);return null;});
        JamUi.main.post(task);task.get(5,TimeUnit.SECONDS);
    }
}
