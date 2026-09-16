package app.morphe.extension.music.jam;

import android.graphics.Bitmap;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Uses YTM's extractor and palette stream, preserving its native animations. */
public final class JamPalette {
    public interface Source {
        Object patch_jamExtract(Bitmap bitmap);
        void patch_jamPublish(Object palette);
        void patch_jamRestorePalette();
    }
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static WeakReference<Source> source=new WeakReference<>(null);
    private static Bitmap artwork;
    private static Object palette;
    private static long generation;
    public static boolean intercept(Source value) {
        if(source.get()!=value){source=new WeakReference<>(value);if(artwork!=null)update(artwork);}
        if(!JamMirror.active())return false;
        if(palette!=null)value.patch_jamPublish(palette);
        return true;
    }
    static void update(Bitmap bitmap) {
        artwork=bitmap;long token=++generation;Source target=source.get();
        if(target==null||bitmap==null)return;
        worker.execute(()->{try{
            Object result=target.patch_jamExtract(bitmap);
            JamUi.main.post(()->{if(token!=generation||!JamMirror.active()||source.get()!=target)return;palette=result;target.patch_jamPublish(result);});
        }catch(Exception error){android.util.Log.w("MorpheJam","Host palette unavailable",error);}});
    }
    static void clear(){generation++;artwork=null;palette=null;Source target=source.get();if(target!=null)target.patch_jamRestorePalette();}
}
