"""Run the production filter runtime on a host JVM with minimal Android/native ABI fixtures.

Usage: python patches/src/test/python/verify_reddit_filters.py <JDK directory> <org.json jar>
Only the two inclusion constants are enabled in temporary copies, as the patcher does.
"""
from pathlib import Path
import subprocess
import sys
import tempfile
import os

repo = Path(__file__).resolve().parents[4]
runtime = repo / "extensions/reddit/src/main/java/app/morphe/extension/reddit/patches"
fixtures = {
    "app/morphe/extension/shared/Logger.java": '''package app.morphe.extension.shared;
public class Logger { public static void printDebug(java.util.function.Supplier<String> message){} }
''',
    "android/content/Context.java": '''package android.content;
public class Context {
 public static final int MODE_PRIVATE=0;
 public Context getApplicationContext(){return this;}
 public SharedPreferences getSharedPreferences(String name,int mode){return STORE;}
 public static final SharedPreferences STORE=new SharedPreferences();
}''',
    "android/content/SharedPreferences.java": '''package android.content;
import java.util.*;
public class SharedPreferences {
 private final Map<String,Object> values=new HashMap<>();
 public boolean contains(String key){return values.containsKey(key);}
 public String getString(String key,String fallback){return (String)values.getOrDefault(key,fallback);}
 public boolean getBoolean(String key,boolean fallback){return (Boolean)values.getOrDefault(key,fallback);}
 public Editor edit(){return new Editor();}
 public class Editor {
  public Editor putString(String k,String v){values.put(k,v);return this;}
  public Editor putBoolean(String k,boolean v){values.put(k,v);return this;}
  public void apply(){}
 }
}''',
    "android/preference/PreferenceManager.java": '''package android.preference;
import android.content.*;
public class PreferenceManager {
 public static SharedPreferences getDefaultSharedPreferences(Context c){return new SharedPreferences();}
}''',
    "android/text/Html.java": '''package android.text;
public class Html {
 public static final int FROM_HTML_MODE_LEGACY=0;
 public static CharSequence fromHtml(String s,int flags){return s.replace("&amp;","&");}
}''',
    "app/morphe/extension/shared/Utils.java": '''package app.morphe.extension.shared;
import android.content.Context;
public class Utils {public static Context getContext(){return new Context();}}
''',
    "com/reddit/domain/model/Link.java": '''package com.reddit.domain.model;
public final class Link {
 public String id,title,body,selftext,community,flair;
 public Link(String i,String t,String b,String c,String f){id=i;title=t;body=b;community=c;flair=f;}
 public String getKindWithId(){return id;}
 public String getTitle(){return title;}
 public String getBody(){return body;}
 public String getSelftext(){return selftext;}
 public String getSubreddit(){return community;}
 public String getLinkFlairText(){return flair;}
}''',
    "app/morphe/extension/reddit/patches/FilterRuntimeTest.java": r'''package app.morphe.extension.reddit.patches;
import java.util.*;
import com.reddit.domain.model.Link;
import android.content.Context;
public class FilterRuntimeTest {
 static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 static void rules(String key,String value){ContentFilterPatch.preferences(new Context()).edit().putString(key,value).apply();}
 static class Node implements ContentFilterPatch.Node {
  Object[] children={},text={}; String community,flair,id;
  public Object[] patch_filterChildren(){return children;}
  public Object[] patch_filterText(){return text;}
  public String patch_filterCommunity(){return community;}
  public String patch_filterFlair(){return flair;}
  public String patch_filterId(){return id;}
 }
 static Node card(String id,String title,String community,String flair){
  Node card=new Node(),titleNode=new Node(),flairNode=new Node();
  card.id=id;titleNode.text=new Object[]{title};
  flairNode.community=community;flairNode.flair=flair;
  card.children=new Object[]{titleNode,List.of(flairNode)};return card;
 }
 public static void main(String[] args){
  boolean keywords=KeywordFilterPatch.isPatchIncluded(),flairs=FlairFilterPatch.isPatchIncluded();
  if(keywords)KeywordFilterPatch.register();
  if(flairs)FlairFilterPatch.register();
  Node one=card("one","Spoiler ALERT","Gaming","Discussion");
  Node otherCommunity=card("two","ordinary","movies","Discussion");
  Object unknown=new Object();
  List<?> original=List.of(one,otherCommunity,unknown);
  check(ContentFilterPatch.filter(original)==original,"empty rules preserve identity");
  rules(ContentFilterPatch.KEYWORDS,"spoiler\\s+alert");
  List<?> filtered=ContentFilterPatch.filter(original);
  check(filtered.size()==(keywords?2:3),"case-insensitive regex and independent keyword selection");
  check(original.size()==3,"original immutable list preserved");
  rules(ContentFilterPatch.KEYWORDS,"");
  rules(ContentFilterPatch.FLAIRS,"r/GAMING::discussion");
  filtered=ContentFilterPatch.filter(original);
  check(filtered.size()==(flairs?2:3),"community-specific case-insensitive flair and independent selection");
  check(filtered.contains(otherCommunity)&&filtered.contains(unknown),"unrelated content preserved");
  Node noFlair=card("plain","ordinary","gaming",null);
  check(ContentFilterPatch.filter(List.of(noFlair)).size()==1,"missing flair fails open");
  Node amp=card("amp","ordinary","gaming","Help &amp; Advice");
  rules(ContentFilterPatch.FLAIRS,"gaming::Help & Advice");
  check(ContentFilterPatch.filter(List.of(amp)).size()==(flairs?0:1),"decoded flair labels");
  rules(ContentFilterPatch.FLAIRS,"gaming::Discussion");
  ContentFilterPatch.rememberLink(new Link("t3_late","later post","body","gaming","Discussion"));
  Node late=card("late","later post","gaming",null);
  check(ContentFilterPatch.filter(List.of(late)).size()==(flairs?0:1),"full-post metadata and both ID forms");
  ContentFilterPatch.rememberLink(new Link("late","later post","body","gaming",null));
  check(ContentFilterPatch.filter(List.of(late)).size()==(flairs?0:1),"partial post does not erase full flair");
  rules(ContentFilterPatch.FLAIRS,"");
  rules(ContentFilterPatch.KEYWORDS,"secret");
  Link body=new Link("body","ordinary","SECRET inside the body","movies",null);
  check(ContentFilterPatch.filter(List.of(body)).size()==(keywords?0:1),"native Link body access");
  Link self=new Link("self","ordinary",null,"movies",null);self.selftext="secret selftext";
  check(ContentFilterPatch.filter(List.of(self)).size()==(keywords?0:1),"native Link selftext access");
  rules(ContentFilterPatch.KEYWORDS,"crap");
  Node titleOnly=card("title-test","crap in title","testing","ordinary flair");
  Node bodyOnly=card("body-test","ordinary title","testing","ordinary flair");
  Node richBody=new Node();
  String document="{\"document\":[{\"e\":\"par\",\"c\":[{\"e\":\"text\",\"t\":\"crap only in description\"}]}]}";
  richBody.text=new Object[]{ContentFilterPatch.visibleRichText(document)};
  Node richSection=new Node();richSection.children=new Object[]{richBody};
  bodyOnly.children=new Object[]{richSection};
  check(ContentFilterPatch.filter(List.of(titleOnly)).size()==(keywords?0:1),"user reproduction: crap only in title");
  check(ContentFilterPatch.filter(List.of(bodyOnly)).size()==(keywords?0:1),"user reproduction: crap only in rich-text description");
  Node flairOnly=card("flair-test","ordinary title","testing","crap");
  check(ContentFilterPatch.filter(List.of(flairOnly)).size()==1,"crap only in flair must never match a keyword");
  Link linkFlair=new Link("link-flair-test","ordinary title","ordinary description","testing","crap");
  ContentFilterPatch.rememberLink(linkFlair);
  check(ContentFilterPatch.filter(List.of(linkFlair)).size()==1,"native Link flair is excluded from keyword input");
  check(ContentFilterPatch.filter(List.of(card("link-flair-test","ordinary title","testing","crap"))).size()==1,"cached native flair is excluded too");
  ContentFilterPatch.rememberLink(new Link("edited-test","crap in the old title","crap in the old body","testing","ordinary flair"));
  ContentFilterPatch.rememberLink(new Link("edited-test","ordinary edited title","","testing","crap"));
  check(ContentFilterPatch.filter(List.of(card("edited-test","ordinary edited title","testing","crap"))).size()==1,"edited post must not retain keywords from an old title or cleared description");
  String metadata="{\"document\":[{\"e\":\"par\",\"c\":[{\"e\":\"link\",\"t\":\"ordinary body\",\"u\":\"https://crap.example/\"}]}],\"flair\":\"crap\",\"media\":{\"t\":\"crap\"}}";
  richBody.text=new Object[]{ContentFilterPatch.visibleRichText(metadata)};
  check(ContentFilterPatch.filter(List.of(bodyOnly)).size()==1,"URLs, flair and media metadata are not description text");
  check(ContentFilterPatch.visibleRichText("{bad json crap").isEmpty(),"malformed rich text does not leak JSON into keyword input");
  check(ContentFilterPatch.visibleRichText("{\"document\":[{\"e\":\"par\",\"c\":[{\"e\":\"text\",\"t\":\"<crap>\"}]}]}").contains("<crap>"),"literal angle brackets survive JSON decoding");
  rules(ContentFilterPatch.FLAIRS,"testing::crap");
  check(ContentFilterPatch.filter(List.of(flairOnly)).size()==(flairs?0:1),"only an enabled flair rule may remove the flair-only post");
  rules(ContentFilterPatch.FLAIRS,"");
  rules(ContentFilterPatch.KEYWORDS,"[invalid\nordinary");
  check(ContentFilterPatch.filter(List.of(self)).size()==(keywords?0:1),"invalid legacy regex does not disable valid rules");
  rules(ContentFilterPatch.KEYWORDS,"");
  check(ContentFilterPatch.filter(original)==original,"removing rules takes effect without restart");
  check(ContentFilterPatch.filter(null)==null,"null input preserved");
  Node cycle=card("cycle","ordinary","gaming",null);cycle.children=new Object[]{cycle};
  check(ContentFilterPatch.filter(List.of(cycle)).size()==1,"cyclic graph bounded");
  rules(ContentFilterPatch.FLAIRS,"gaming::Discussion");
  check(ContentFilterPatch.isSecondaryFetchRemoved(true)==!flairs,"only enabled flair rules retain native fetch");
  rules(ContentFilterPatch.FLAIRS,"");
  rules(ContentFilterPatch.KEYWORDS,"crap");
  check(ContentFilterPatch.isSecondaryFetchRemoved(true)==!keywords,"keyword-only patch retains description fetch");
  rules(ContentFilterPatch.KEYWORDS,"");
  check(ContentFilterPatch.isSecondaryFetchRemoved(true),"empty rules preserve native fetch policy");
  check(!ContentFilterPatch.isSecondaryFetchRemoved(false),"never disable an existing native fetch");
  ContentFilterPatch.preferences(new Context()).edit().putBoolean(FlairFilterPatch.COLLECT,false).apply();
  Node poison=new Node(){public Object[] patch_filterText(){throw new AssertionError("disabled filters read a model");}};
  List<?> disabled=List.of(poison);
  check(ContentFilterPatch.filter(disabled)==disabled,"all filter settings off must skip model work");
  System.out.println("PASS runtime: keywords="+keywords+", flairs="+flairs);
 }
}'''
}

fixtures["app/morphe/extension/shared/Logger.java"] = '''package app.morphe.extension.shared;
public class Logger {
 public static void printDebug(java.util.function.Supplier<String> message){}
 public static void printException(java.util.function.Supplier<String> message,Exception error){}
}'''
fixtures.update({
    "androidx/annotation/Nullable.java": "package androidx.annotation; public @interface Nullable {}",
    "android/preference/PreferenceCategory.java": "package android.preference; public class PreferenceCategory { public void addPreference(Object p){} }",
    "app/morphe/extension/shared/StringRef.java": "package app.morphe.extension.shared; public class StringRef { public static String str(String key){return key;} }",
    "app/morphe/extension/reddit/settings/preference/RuleListPreference.java": '''package app.morphe.extension.reddit.settings.preference;
public class RuleListPreference { public RuleListPreference(android.content.Context c,String key,String title,String summary,String hint,java.util.function.Function<String,String> validator){} }''',
    "app/morphe/extension/reddit/settings/preference/FlairFilterPreferences.java": '''package app.morphe.extension.reddit.settings.preference;
public class FlairFilterPreferences { public static void addPreferences(android.content.Context c,android.preference.PreferenceCategory p){} }'''
})

with tempfile.TemporaryDirectory(prefix="reddit-filter-runtime-") as folder:
    work = Path(folder)
    for name, source in fixtures.items():
        target = work / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding="utf-8")
    for keywords, flairs in [(True, False), (False, True), (True, True), (False, False)]:
        if keywords and not (runtime / "KeywordFilterPatch.java").exists():
            continue
        if flairs and not (runtime / "FlairFilterPatch.java").exists():
            continue
        for name in ["ContentFilterPatch", "KeywordFilterPatch", "FlairFilterPatch"]:
            path = runtime / (name + ".java")
            source = path.read_text(encoding="utf-8") if path.exists() else (
                "package app.morphe.extension.reddit.patches; public class " + name +
                ' { public static final String COLLECT="absent_feature"; public static boolean isPatchIncluded(){return false;} public static void register(){} }'
            )
            if (name == "KeywordFilterPatch" and keywords) or (name == "FlairFilterPatch" and flairs):
                source = source.replace("isPatchIncluded() { return false; }", "isPatchIncluded() { return true; }")
            target = work / "app/morphe/extension/reddit/patches" / (name + ".java")
            target.write_text(source, encoding="utf-8")
        subprocess.run([str(Path(sys.argv[1]) / "bin/javac"), "-cp", sys.argv[2], "-d", str(work / "classes"),
                        *map(str, work.rglob("*.java"))], check=True)
        subprocess.run([str(Path(sys.argv[1]) / "bin/java"), "-cp", os.pathsep.join([str(work / "classes"), sys.argv[2]]),
                        "app.morphe.extension.reddit.patches.FilterRuntimeTest"], check=True)
