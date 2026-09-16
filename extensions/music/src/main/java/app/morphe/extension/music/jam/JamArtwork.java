package app.morphe.extension.music.jam;
import android.graphics.*;
import android.graphics.drawable.*;
import android.widget.ImageView;
import java.util.*;
import java.util.concurrent.*;
import java.net.*;
import java.io.*;

/** Updates the existing native artwork view; caches one bounded public thumbnail. */
public final class JamArtwork {
    private static final Map<ImageView,Drawable> originals=new WeakHashMap<>();
    private static final ExecutorService loader=Executors.newSingleThreadExecutor();
    private static String url="";
    private static Bitmap picture;
    public static void bind(ImageView view){if(!JamMirror.active())return;if(!originals.containsKey(view))originals.put(view,view.getDrawable());if(picture!=null)view.setImageBitmap(picture);}
    public static Bitmap choose(ImageView view,Bitmap local){
        if(!JamMirror.active())return local;
        originals.put(view,new BitmapDrawable(view.getResources(),local));return picture==null?local:picture;
    }
    static void clear(){url="";picture=null;JamPalette.clear();for(Map.Entry<ImageView,Drawable> entry:new ArrayList<>(originals.entrySet()))entry.getKey().setImageDrawable(entry.getValue());originals.clear();}
    static void update(String value){
        if(value.equals(url))return;url=value;picture=null;
        loader.execute(()->{try{
            HttpURLConnection connection=(HttpURLConnection)new URL(value).openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(5000);connection.setInstanceFollowRedirects(false);
            byte[] bytes;try(InputStream input=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){if(out.size()+count>2097152)throw new IOException("Artwork too large");out.write(buffer,0,count);}bytes=out.toByteArray();
            }finally{connection.disconnect();}
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            if(options.outWidth<=0||options.outHeight<=0||options.outWidth>8192||options.outHeight>8192)return;
            options.inJustDecodeBounds=false;options.inSampleSize=Math.max(1,Math.max(options.outWidth,options.outHeight)/1024);
            Bitmap result=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            JamUi.main.post(()->{if(!value.equals(url)||!JamMirror.active())return;picture=result;JamPalette.update(result);for(ImageView view:new ArrayList<>(originals.keySet()))if(result!=null)view.setImageBitmap(result);});
        }catch(Exception e){android.util.Log.i("MorpheJam","Host artwork unavailable");}});
    }
}
