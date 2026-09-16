package app.morphe.extension.music.jam;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Sanitized native display model: content ID and public display metadata only. */
public final class QueueModel {
    private static byte[] field(int number,byte[] value){ByteArrayOutputStream out=new ByteArrayOutputStream();number(out,((long)number<<3)|2);number(out,value.length);out.write(value,0,value.length);return out.toByteArray();}
    private static byte[] text(int number,String value){return field(number,value.getBytes(StandardCharsets.UTF_8));}
    private static void number(ByteArrayOutputStream out,long value){while((value&~127L)!=0){out.write((int)value&127|128);value>>>=7;}out.write((int)value);}
    private static byte[] concat(byte[]... values){ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] value:values)out.write(value,0,value.length);return out.toByteArray();}
    private static byte[] label(String value){return field(1,text(1,value));}
    public static byte[] encode(String video,String title,String artist){
        return encode(video,title,artist,"");
    }
    public static String thumbnail(String url,String video){
        if(url!=null&&url.length()<2048)try{java.net.URI uri=java.net.URI.create(url);String host=uri.getHost();
            if("https".equals(uri.getScheme())&&uri.getUserInfo()==null&&uri.getPort()==-1&&host!=null&&(host.equals("i.ytimg.com")||host.equals("lh3.googleusercontent.com")||host.equals("lh3.ggpht.com")))return url;
        }catch(Exception ignored){}
        return "https://i.ytimg.com/vi/"+video+"/hqdefault.jpg";
    }
    public static byte[] encode(String video,String title,String artist,String picture){
        if(!video.matches("[A-Za-z0-9_-]{11}"))throw new IllegalArgumentException("Invalid video ID");
        // bxxt.renderer -> bzba.musicResponsiveListItemRenderer (51779701).
        byte[] watch=field(48687757,text(1,video));
        byte[] thumbnail=field(1,text(1,thumbnail(picture,video)));
        byte[] renderer=concat(field(1,label(title)),field(2,label(artist)),field(12,label(artist)),field(3,thumbnail),field(7,watch),text(10,video));
        return field(1,field(51779701,renderer));
    }
}
