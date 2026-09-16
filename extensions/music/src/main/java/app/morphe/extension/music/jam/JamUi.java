package app.morphe.extension.music.jam;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.widget.*;
import app.morphe.jam.ipc.*;
import org.json.*;
import java.lang.ref.WeakReference;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Player-integrated UI, with one live shared-state feed per YTM process. */
public final class JamUi {
    static final Handler main=new Handler(Looper.getMainLooper());
    private static final ExecutorService commands=Executors.newSingleThreadExecutor(),updates=Executors.newSingleThreadExecutor();
    private static final Set<Consumer<JSONObject>> observers=new HashSet<>();
    private static volatile IJamCompanion companion;
    private static boolean binding,polling,inFlight;
    private static WeakReference<Activity> current=new WeakReference<>(null);
    static JSONObject latest=new JSONObject();
    static int pending;
    static void notifyState(){for(Consumer<JSONObject> listener:new ArrayList<>(observers))listener.accept(latest);}
    private static Context application;
    static int dp(Context c,int value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
    static String capability(Context c){return c.getSharedPreferences("jam",0).getString("cap","");}
    static Activity activity(Context c){while(c instanceof ContextWrapper){if(c instanceof Activity)return (Activity)c;c=((ContextWrapper)c).getBaseContext();}return current.get();}
    public static void install(Activity a){main.post(()->{current=new WeakReference<>(a);application=a.getApplicationContext();if(!capability(a).isEmpty())bind(a);});}
    static void observe(Context c,Consumer<JSONObject> observer){observers.add(observer);observer.accept(latest);application=c.getApplicationContext();bind(c);if(!polling){polling=true;main.post(poll);}}
    static void unobserve(Consumer<JSONObject> observer){observers.remove(observer);}
    private static final Runnable poll=new Runnable(){public void run(){
        if(!observers.isEmpty()&&!inFlight&&companion!=null){inFlight=true;updates.execute(()->{
            JSONObject value;try{value=new JSONObject(companion.call(capability(application),new JSONObject().put("op","VIEW").toString()));}
            catch(Exception e){value=JamBridgeService.error("Jam disconnected");}
            JSONObject received=value;main.post(()->{inFlight=false;latest=received;JamMirror.accept(application,received);JamClock.accept(received);for(Consumer<JSONObject> listener:new ArrayList<>(observers))listener.accept(received);});
        });}
        if(observers.isEmpty()){polling=false;return;}main.postDelayed(this,600);
    }};
    private static void bind(Context c){
        if(binding||capability(c).isEmpty())return;Context app=c.getApplicationContext();
        try{if(!Trust.equal(Trust.COMPANION_CERT,Trust.certificate(app,Trust.COMPANION)))return;
            binding=app.bindService(new Intent().setComponent(new ComponentName(Trust.COMPANION,"app.morphe.jam.companion.JamService")),new ServiceConnection(){
                public void onServiceConnected(ComponentName n,IBinder b){companion=IJamCompanion.Stub.asInterface(b);}
                public void onServiceDisconnected(ComponentName n){companion=null;}
                public void onBindingDied(ComponentName n){companion=null;binding=false;app.unbindService(this);main.postDelayed(()->bind(app),1500);}
            },Context.BIND_AUTO_CREATE);
        }catch(Exception e){binding=false;}
    }
    static JSONObject command(String operation){try{return new JSONObject().put("op",operation).put("id",UUID.randomUUID().toString());}catch(Exception e){throw new IllegalStateException(e);}}
    static void call(Context c,JSONObject request,Consumer<JSONObject> done){if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->call(c,request,done));return;}bind(c);pending++;notifyState();("END".equals(request.optString("op"))?updates:commands).execute(()->{
        JSONObject value;try{IJamCompanion service=companion;if(service==null)throw new IllegalStateException("Enable Jam Layer first");value=new JSONObject(service.call(capability(c),request.toString()));}
        catch(Exception e){value=JamBridgeService.error(e.getMessage());}
        JSONObject response=value;main.post(()->{pending=Math.max(0,pending-1);if(response.optBoolean("ok")&&"GUEST_EDITS".equals(request.optString("op"))){try{JSONObject state=latest.optJSONObject("session");if(state!=null)state.put("allowGuestEdits",request.optBoolean("allow"));}catch(Exception ignored){}}done.accept(response);notifyState();});
    });}
    static void edit(Context c,JSONObject request){call(c,request,r->{if(!r.optBoolean("ok"))toast(c,r.optString("error"));});}
    static void styleDialog(AlertDialog dialog){
        android.view.Window window=dialog.getWindow();if(window==null)return;Context c=dialog.getContext();
        android.graphics.drawable.GradientDrawable surface=new android.graphics.drawable.GradientDrawable();surface.setColor(0xff212121);surface.setCornerRadius(dp(c,28));window.setBackgroundDrawable(surface);window.getDecorView().setClipToOutline(true);
        window.setLayout(Math.min(c.getResources().getDisplayMetrics().widthPixels-dp(c,32),dp(c,520)),android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    static void toast(Context c,String message){Toast.makeText(c,message,Toast.LENGTH_LONG).show();}
    private static void startLayer(Context c){c.startForegroundService(new Intent().setComponent(new ComponentName(Trust.COMPANION,"app.morphe.jam.companion.JamService")).putExtra("cap",capability(c)));}
    public static void open(Context context){JamPanel.show(context);}
    static void host(Context c){try{startLayer(c);edit(c,command("HOST"));}catch(Exception e){setup(c);}}    static void pair(Context c){
        Activity a=activity(c);if(a==null)return;
        try{if(!Trust.equal(Trust.COMPANION_CERT,Trust.certificate(c,Trust.COMPANION)))throw new SecurityException();
            byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);StringBuilder token=new StringBuilder();for(byte b:bytes)token.append(String.format(Locale.ROOT,"%02x",b&255));
            c.getSharedPreferences("jam",0).edit().putString("cap",token.toString()).commit();
            a.startActivityForResult(new Intent("app.morphe.jam.PAIR").setComponent(new ComponentName(Trust.COMPANION,"app.morphe.jam.companion.MainActivity")).putExtra("cap",token.toString()),18431);
            main.postDelayed(()->bind(c),1500);
        }catch(Exception e){toast(c,"Install the matching Jam Layer APK first");}
    }
    private static void setup(Context c){try{c.startActivity(new Intent().setComponent(new ComponentName(Trust.COMPANION,"app.morphe.jam.companion.MainActivity")));}catch(Exception e){toast(c,"Install Jam Layer first");}}
    static void join(Context c){
        LinearLayout content=new LinearLayout(c);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(c,24),dp(c,8),dp(c,24),0);
        TextView hint=new TextView(c);hint.setText("Enter the code shown on the host. Both devices need to be on the same Wi-Fi.");hint.setTextSize(14);hint.setPadding(0,0,0,dp(c,16));content.addView(hint);
        EditText input=new EditText(c);input.setHint("ABCD-EFGH");input.setSingleLine();input.setTextSize(22);input.setTypeface(Typeface.MONOSPACE);input.setInputType(4097);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(512)});content.addView(input,new LinearLayout.LayoutParams(-1,dp(c,56)));
        AlertDialog dialog=new AlertDialog.Builder(c).setTitle("Join with a code").setView(content).setPositiveButton("Join",null).setNeutralButton("Scan QR",(d,w)->{
            try{Activity a=activity(c);if(a!=null)a.startActivityForResult(new Intent("app.morphe.jam.SCAN").setComponent(new ComponentName(Trust.COMPANION,"app.morphe.jam.companion.MainActivity")).putExtra("cap",capability(c)),18432);}catch(Exception e){setup(c);}
        }).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String value=input.getText().toString().trim();String normalized=value.replace("-","").replace(" ","").toUpperCase(Locale.ROOT);
            if(!value.startsWith("morphejam://")&&!normalized.matches("[A-HJ-NP-Z2-9]{8}")){input.setError("Enter the eight-character code or paste an invitation");return;}
            try{startLayer(c);call(c,command("JOIN").put("invite",value),r->{if(!r.optBoolean("ok"))toast(c,r.optString("error"));});dialog.dismiss();open(c);}catch(Exception e){setup(c);}
        }));dialog.show();styleDialog(dialog);
    }    static void invite(Context c){call(c,command("INVITE"),r->{
        if(!r.optBoolean("ok")){toast(c,r.optString("error"));return;}
        String code=r.optString("code");
        if(!code.isEmpty()){
            TextView text=new TextView(c);text.setText(code);text.setTextSize(32);text.setTypeface(android.graphics.Typeface.MONOSPACE);text.setGravity(android.view.Gravity.CENTER);text.setPadding(24,32,24,32);text.setTextIsSelectable(true);
            long minutes=Math.max(1,(r.optLong("codeExpires")-System.currentTimeMillis()+59999)/60000);
            AlertDialog popup=new AlertDialog.Builder(c).setTitle("Join your Jam").setMessage("Enter this code on a device using the same Wi-Fi. Expires in "+minutes+" minutes.").setView(text)
                .setPositiveButton("Copy code",(d,w)->((ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Jam code",code)))
                .setNeutralButton("Show QR",(d,w)->showQr(c,r)).setNegativeButton("Done",null).create();
            popup.show();styleDialog(popup);
        }else showQr(c,r);
    });}
    private static void showQr(Context c,JSONObject r){
        byte[] bytes=android.util.Base64.decode(r.optString("qr"),android.util.Base64.DEFAULT);ImageView image=new ImageView(c);image.setImageBitmap(BitmapFactory.decodeByteArray(bytes,0,bytes.length));image.setAdjustViewBounds(true);image.setPadding(24,24,24,24);
        AlertDialog popup=new AlertDialog.Builder(c).setTitle("Invite to your Jam").setMessage("Scan to join and edit this queue. Ending the Jam revokes the invitation.").setView(image)
            .setPositiveButton("Done",null).setNeutralButton("Copy link",(d,w)->((ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Jam invitation",r.optString("invite")))).create();
        popup.show();styleDialog(popup);
    }
    public static boolean offer(YtmBridge.QueueAccess access,byte[] bytes){
        String[] decoded=QueueCommand.decode(bytes);Activity a=current.get();
        if(decoded==null||a==null||a.isFinishing())return false;
        if(companion==null){if(JamMirror.active()){main.post(()->toast(a,"Jam is reconnecting; try again shortly"));return true;}return false;}
        Runnable local=()->access.patch_jamExecutor().execute(()->access.patch_jamEnqueue(bytes));
        commands.execute(()->{try{
            JSONObject state=new JSONObject(companion.call(capability(a),new JSONObject().put("op","STATE").toString()));
            if(!"Participant".equals(state.optString("role"))){local.run();return;}
            call(a,command(decoded[1]).put("videoId",decoded[0]),response->toast(a,response.optBoolean("ok")?"Added to Jam":response.optString("error")));
        }catch(Exception e){main.post(()->toast(a,"Jam is unavailable; the track was not added"));}});return true;
    }
}
