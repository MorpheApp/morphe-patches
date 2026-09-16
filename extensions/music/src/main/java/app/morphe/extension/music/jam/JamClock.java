package app.morphe.extension.music.jam;

import android.app.*;
import android.media.MediaMetadata;
import android.media.session.*;
import android.os.SystemClock;
import android.view.View;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import app.morphe.extension.music.shared.VideoInformation;

/** Host clock samples are separate from queue revisions and use local monotonic time. */
public final class JamClock {
    public interface Bar { Object patch_jamModel(long position,long duration);void patch_jamClock(long position,long duration); void patch_jamRestore(Object model); }
    private static volatile MediaController controller;
    private static final Map<Bar,Object> bars=new WeakHashMap<>();
    private static JSONObject sample;
    private static long received,sequence=-1;
    private static String stream="";
    private static boolean ticking;
    private static final String generation=UUID.randomUUID().toString();
    private static long serial;
    public static void capture(MediaSession session){controller=session.getController();}
    static JSONObject snapshot()throws Exception{
        MediaController c=controller;JSONObject out=new JSONObject().put("generation",generation).put("sequence",++serial).put("sampledAt",SystemClock.elapsedRealtime()).put("videoId",VideoInformation.getVideoId());
        if(c==null)return out;
        PlaybackState state=c.getPlaybackState();MediaMetadata metadata=c.getMetadata();if(state==null||metadata==null)return out;
        long duration=metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),position=state.getPosition();boolean playing=state.getState()==PlaybackState.STATE_PLAYING;
        if(playing)position+=(long)(Math.max(0,SystemClock.elapsedRealtime()-state.getLastPositionUpdateTime())*state.getPlaybackSpeed());
        return out.put("position",Math.max(0,Math.min(duration,position))).put("duration",duration).put("speed",state.getPlaybackSpeed()).put("playing",playing);
    }
    static void accept(JSONObject view){
        JSONObject session=view.optJSONObject("session");if(session==null)return;
        if(!"Participant".equals(session.optString("role"))){clear();return;}
        JSONObject next=view.optJSONObject("clock");if(next==null||!next.has("duration"))return;
        long duration=next.optLong("duration"),position=next.optLong("position",-1);double speed=next.optDouble("speed",1);
        if(!JamTime.valid(position,duration,speed))return;
        String video=next.optString("videoId");JSONArray rows=view.optJSONArray("items");boolean matches=false;
        if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&row.optBoolean("current")&&video.equals(row.optString("videoId")))matches=true;}
        if(!matches)return;
        String g=next.optString("generation");long seq=next.optLong("sequence",-1);if(g.equals(stream)&&seq<=sequence)return;
        stream=g;sequence=seq;sample=next;received=next.optLong("receivedAt",SystemClock.elapsedRealtime())-Math.min(4000,Math.max(0,next.optLong("age")));if(!ticking){ticking=true;JamUi.main.post(tick);}
    }
    static long position(){return sample==null?0:JamTime.position(sample.optLong("position"),sample.optLong("duration"),sample.optDouble("speed",1),sample.optBoolean("playing"),SystemClock.elapsedRealtime()-received);}
    public static Object model(Object view,Object local){if(!(view instanceof Bar))return local;Bar bar=(Bar)view;bars.put(bar,local);return sample==null?local:bar.patch_jamModel(position(),sample.optLong("duration"));}
    private static final Runnable tick=new Runnable(){public void run(){if(sample==null){ticking=false;return;}for(Bar bar:new ArrayList<>(bars.keySet()))try{bar.patch_jamClock(position(),sample.optLong("duration"));}catch(Exception ignored){}JamUi.main.postDelayed(this,200);}};
    static void clear(){sample=null;stream="";sequence=-1;for(Map.Entry<Bar,Object> e:new ArrayList<>(bars.entrySet()))try{e.getKey().patch_jamRestore(e.getValue());}catch(Exception ignored){}bars.clear();}
    public static boolean offerSeek(long target){
        if(!JamMirror.active())return false;if(sample==null)return true;
        long duration=sample.optLong("duration");if(duration<=0)return true;long at=Math.max(0,Math.min(duration-1,target));String video=sample.optString("videoId");
        JamUi.main.post(()->{Activity a=JamUi.activity(null);if(a==null||a.isFinishing()||!JamPlayback.claimDialog())return;
            String time=String.format(Locale.ROOT,"%d:%02d",at/60000,(at/1000)%60);
            AlertDialog d=new AlertDialog.Builder(a).setTitle("Seek to "+time+"?").setItems(new String[]{"Move the host here","Quit Jam and play locally"},(w,index)->{
                if(index==0){try{JamUi.edit(a,JamUi.command("SEEK").put("videoId",video).put("position",at));}catch(Exception ignored){}}
                else JamPlayback.leaveAndPlay(a,video,at);
            }).setNegativeButton("Cancel",null).setOnDismissListener(w->JamPlayback.releaseDialog()).create();d.show();JamUi.styleDialog(d);
        });return true;
    }
    static void seekHost(String video,long position)throws Exception{
        FutureTask<Void> task=new FutureTask<>(()->{MediaController c=controller;if(c==null||!video.equals(VideoInformation.getVideoId()))throw new IllegalStateException("The host changed songs; try again");MediaMetadata m=c.getMetadata();long duration=m==null?0:m.getLong(MediaMetadata.METADATA_KEY_DURATION);if(position<0||position>=duration)throw new IllegalArgumentException("Position is outside this song");c.getTransportControls().seekTo(position);return null;});JamUi.main.post(task);task.get(5,TimeUnit.SECONDS);
    }
    static void seekLocalWhenReady(String video,long position,int attempts){
        if(video.equals(VideoInformation.getVideoId())&&controller!=null&&controller.getPlaybackState()!=null&&controller.getPlaybackState().getState()==PlaybackState.STATE_PLAYING){controller.getTransportControls().seekTo(position);return;}
        if(attempts>0)JamUi.main.postDelayed(()->seekLocalWhenReady(video,position,attempts-1),200);
        else {Activity a=JamUi.activity(null);if(a!=null)JamUi.toast(a,"Song did not become ready to seek");}
    }
}
