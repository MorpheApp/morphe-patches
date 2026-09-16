package app.morphe.extension.music.jam;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Minimal native queue endpoint, verified against the 9.15.51 protobuf schema. */
public final class QueueCommand {
    private QueueCommand() {}
    public static String watchVideo(byte[] data){try{
        if(data==null||data.length>65536)return null;
        byte[] video=field(field(data,48687757),1);
        String id=new String(video,StandardCharsets.US_ASCII);
        return id.matches("[A-Za-z0-9_-]{11}")?id:null;
    }catch(RuntimeException e){return null;}}
    public static byte[] watch(String video){
        if(video==null||!video.matches("[A-Za-z0-9_-]{11}"))throw new IllegalArgumentException("Invalid video ID");
        ByteArrayOutputStream body=new ByteArrayOutputStream(),endpoint=new ByteArrayOutputStream();
        bytes(body,1,video.getBytes(StandardCharsets.US_ASCII));bytes(endpoint,48687757,body.toByteArray());return endpoint.toByteArray();
    }
    /** Native music/get_queue context used to load the selected track's participant-side options. */
    public static byte[] menuRequest(String video){
        if(video==null||!video.matches("[A-Za-z0-9_-]{11}"))throw new IllegalArgumentException("Invalid video ID");
        ByteArrayOutputStream context=new ByteArrayOutputStream(),request=new ByteArrayOutputStream(),endpoint=new ByteArrayOutputStream();
        bytes(context,1,video.getBytes(StandardCharsets.US_ASCII));
        bytes(request,1,context.toByteArray());
        bytes(endpoint,163162354,request.toByteArray());
        return endpoint.toByteArray();
    }

    /** Read only a single-track endpoint; playlists and unknown wire forms stay native. */
    public static String[] decode(byte[] data) {
        try {
            if(data==null || data.length>65536)return null;
            byte[] operation=field(data,163162354);
            byte[] target=field(operation,1);
            byte[] video=field(target,1);
            if(video==null)return null;
            String id=new String(video,StandardCharsets.US_ASCII);
            if(!id.matches("[A-Za-z0-9_-]{11}"))return null;
            int mode=integer(operation,2);
            return mode==1||mode==2?new String[]{id,mode==1?"PLAY_NEXT":"ADD"}:null;
        } catch(RuntimeException malformed){return null;}
    }
    private static long read(byte[] data,int[] at){
        long value=0;
        for(int shift=0;shift<64;shift+=7){if(at[0]>=data.length)throw new IllegalArgumentException();int b=data[at[0]++]&255;value|=(long)(b&127)<<shift;if((b&128)==0)return value;}
        throw new IllegalArgumentException();
    }
    private static Object find(byte[] data,int wanted,int wire){
        if(data==null)throw new IllegalArgumentException();int[] at={0};Object found=null;
        while(at[0]<data.length){long tag=read(data,at);int kind=(int)(tag&7);long number=tag>>>3;if(number==0)throw new IllegalArgumentException();
            Object value=null;
            if(kind==0)value=read(data,at);
            else if(kind==2){long length=read(data,at);if(length<0||length>data.length-at[0])throw new IllegalArgumentException();if(number==wanted)value=java.util.Arrays.copyOfRange(data,at[0],at[0]+(int)length);at[0]+=(int)length;}
            else if(kind==1)at[0]+=8;else if(kind==5)at[0]+=4;else throw new IllegalArgumentException();
            if(at[0]>data.length)throw new IllegalArgumentException();
            if(number==wanted){if(kind!=wire||found!=null)throw new IllegalArgumentException();found=value;}
        }return found;
    }
    private static byte[] field(byte[] data,int field){return (byte[])find(data,field,2);}
    private static int integer(byte[] data,int field){Object value=find(data,field,0);return value==null?0:((Long)value).intValue();}

    public static byte[] encode(String videoId, boolean playNext) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            throw new IllegalArgumentException("Enter an 11-character YouTube video ID");
        }
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        bytes(target, 1, videoId.getBytes(StandardCharsets.US_ASCII));
        // Do not set target field 3: that selects the downloaded/local-media route.
        ByteArrayOutputStream operation = new ByteArrayOutputStream();
        bytes(operation, 1, target.toByteArray());
        varint(operation, 2 << 3);
        varint(operation, playNext ? 1 : 2);
        ByteArrayOutputStream endpoint = new ByteArrayOutputStream();
        bytes(endpoint, 163162354, operation.toByteArray());
        return endpoint.toByteArray();
    }

    private static void bytes(ByteArrayOutputStream out, int field, byte[] value) {
        varint(out, (field << 3) | 2);
        varint(out, value.length);
        out.write(value, 0, value.length);
    }

    private static void varint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7f) != 0) {
            out.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
