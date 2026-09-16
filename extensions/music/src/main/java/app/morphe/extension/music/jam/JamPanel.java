package app.morphe.extension.music.jam;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.function.Consumer;

/** Live session controls using the host application's dialog theme. */
final class JamPanel {
    static String role(JSONObject view){JSONObject s=view.optJSONObject("session");return s==null?"Idle":s.optString("role","Idle");}
    static String title(JSONObject view){String r=role(view);return "Host".equals(r)?"Your Jam":"Participant".equals(r)?"Listening together":"Joining".equals(r)?"Joining Jam":"Listen together";}
    static String status(JSONObject view){
        JSONObject s=view.optJSONObject("session");String r=role(view);
        if(JamUi.pending>0)return "Updating Jam…";
        if("Joining".equals(r))return "Finding your host on Wi-Fi…";
        if("Host".equals(r)){int n=s.optInt("peers");return (n==0?"Waiting for people":n+" "+(n==1?"person":"people")+" connected")+" · "+(s.optBoolean("allowGuestEdits",true)?"Edits open":"Edits locked");}
        if("Participant".equals(r)){String t=s.optString("transport");return "Aware".equals(t)?"Connected via Wi-Fi Aware":"LAN".equals(t)?"Connected via Wi-Fi":"Reconnecting to your host…";}
        return s==null&&view.has("error")?view.optString("error"):"Share a queue. Let everyone add songs.";
    }
    static boolean waiting(JSONObject v){String r=role(v);JSONObject s=v.optJSONObject("session");return JamUi.pending>0||"Joining".equals(r)||("Participant".equals(r)&&!"Aware".equals(s.optString("transport"))&&!"LAN".equals(s.optString("transport")));}
    static void show(Context context){
        Activity activity=JamUi.activity(context);if(activity==null||activity.isFinishing())return;
        Context c=activity;LinearLayout body=new LinearLayout(c);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(JamUi.dp(c,24),JamUi.dp(c,20),JamUi.dp(c,24),JamUi.dp(c,8));
        TextView heading=new TextView(c);heading.setTextSize(24);heading.setTypeface(null,Typeface.BOLD);heading.setTextColor(0xffeeeeee);body.addView(heading);
        TextView status=new TextView(c);status.setTextSize(14);status.setTextColor(0xffbbbbbb);status.setPadding(0,JamUi.dp(c,8),0,JamUi.dp(c,20));status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(status);
        ProgressBar progress=new ProgressBar(c,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);body.addView(progress,new LinearLayout.LayoutParams(-1,JamUi.dp(c,3)));
        LinearLayout actions=new LinearLayout(c);actions.setOrientation(LinearLayout.VERTICAL);body.addView(actions);
        AlertDialog dialog=new AlertDialog.Builder(c).setView(body).setNegativeButton("Done",null).create();
        final String[] previous={""};
        Consumer<JSONObject> observer=v->{
            heading.setText(title(v));status.setText(status(v));progress.setVisibility(waiting(v)?View.VISIBLE:View.GONE);
            String role=role(v);JSONObject state=v.optJSONObject("session");boolean allowed=state==null||state.optBoolean("allowGuestEdits",true),busy=JamUi.pending>0;
            String key=role+allowed+busy+(state!=null&&state.optBoolean("paired"));if(key.equals(previous[0]))return;previous[0]=key;actions.removeAllViews();
            if("Host".equals(role)){
                button(c,actions,"Invite people",false,!busy,()->JamUi.invite(c));
                Switch edits=new Switch(c);edits.setText("Guests can edit the queue");edits.setTextColor(0xffeeeeee);edits.setTextSize(15);edits.setMinHeight(JamUi.dp(c,56));edits.setChecked(allowed);edits.setEnabled(!busy);actions.addView(edits,new LinearLayout.LayoutParams(-1,-2));
                edits.setOnCheckedChangeListener((b,checked)->{edits.setEnabled(false);try{JamUi.edit(c,JamUi.command("GUEST_EDITS").put("allow",checked));}catch(Exception ignored){}});
                button(c,actions,"End Jam",true,!busy,()->JamUi.edit(c,JamUi.command("END")));
            }else if("Participant".equals(role)||"Joining".equals(role)){
                TextView help=new TextView(c);help.setText("Participant".equals(role)?"The host plays the music. Add, move or remove songs directly in the YouTube Music queue.":"Keep both devices nearby. You can cancel while we look for the host.");help.setTextColor(0xffbbbbbb);help.setTextSize(14);help.setPadding(0,JamUi.dp(c,16),0,JamUi.dp(c,12));actions.addView(help);
                button(c,actions,"Joining".equals(role)?"Cancel joining":"Leave Jam",true,true,()->JamUi.edit(c,JamUi.command("END")));
            }else{
                boolean paired=state!=null&&state.optBoolean("paired");
                if(paired){button(c,actions,"Start a Jam",false,!busy,()->JamUi.host(c));button(c,actions,"Join with a code",false,!busy,()->{dialog.dismiss();JamUi.join(c);});}
                else button(c,actions,"Set up Jam Layer",false,!busy,()->{dialog.dismiss();JamUi.pair(c);});
            }
        };
        dialog.setOnDismissListener(d->JamUi.unobserve(observer));dialog.show();JamUi.styleDialog(dialog);JamUi.observe(c,observer);
    }
    private static void button(Context c,LinearLayout parent,String label,boolean destructive,boolean enabled,Runnable action){
        Button b=new Button(c);b.setText(label);b.setAllCaps(false);b.setTextSize(15);b.setTextColor(destructive?0xffffb4ab:0xffeeeeee);b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);b.setPadding(JamUi.dp(c,16),0,JamUi.dp(c,16),0);
        GradientDrawable fill=new GradientDrawable();fill.setColor(destructive?0x10ffb4ab:0x16ffffff);fill.setCornerRadius(JamUi.dp(c,16));b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x30ffffff),fill,null));b.setEnabled(enabled);b.setAlpha(enabled?1f:.45f);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,JamUi.dp(c,52));p.topMargin=JamUi.dp(c,8);parent.addView(b,p);b.setOnClickListener(v->action.run());
    }
}
