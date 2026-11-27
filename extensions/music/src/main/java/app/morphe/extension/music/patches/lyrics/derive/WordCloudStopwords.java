/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Editable stopword list; comma/space/newline separated. */
public final class WordCloudStopwords {

    public static final String DEFAULT =
            "a,an,the,and,or,but,if,in,on,at,to,for,of,with,by,from,as,is,are,was,were,be,been,being,"
                    + "i,you,he,she,it,we,they,me,him,her,us,them,my,your,his,its,our,their,this,that,"
                    + "these,those,not,no,so,do,did,does,have,has,had,will,would,can,could,should,just,"
                    + "like,don't,yeah,oh,ooh,ah,na,la,hey,now,then,when,what,how,all,out,up,down,into,"
                    + "about,over,only,more,some,any,ever,never,always,still,again,here,there,go,got,"
                    + "let,get,的,了,在,是,我,你,他,她,它,们,这,那,和,与,或,但,就,也,都,还,又,再,不,没,"
                    + "很,更,最,太,啊,吧,呢,吗,呀,哦,哈,嗯,着,过,地,得,要,会,能,可,把,被,让,给,向,从,到,"
                    + "为,对,及,等,一,个,些"
                    + ",you're,we're,they're,i'm,he's,she's,it's,we've,you've,they've,i've"
                    + ",we'll,you'll,they'll,i'll,he'll,she'll,it'll,we'd,you'd,they'd,i'd"
                    + ",he'd,she'd,it'd,let's,that's,there's,here's,y'all,would've,could've"
                    + ",should've,must've,can't,won't,isn't,aren't,wasn't,weren't,doesn't"
                    + ",didn't,hasn't,haven't,hadn't,couldn't,wouldn't,shouldn't,mustn't"
                    + ",needn't,shan't,ain't,cannot,what's,who's,where's,when's,how's,why's"
                    + ",gonna,gotta,wanna,cause,um,umm,uh,uhh,hmm,mmm,nah,nope,yep,whoa,wow,tsk"
                    + ",re,ve,ll"
                    + ",我们,你们,他们,她们,它们,什么,怎么,这么,那么,为什么,因为,所以,但是,如果"
                    + ",可以,没有,就是,还是,已经,这样,那样,多少,真的,现在,时候,知道,觉得,可能"
                    + ",应该,自己,大家,这里,那里,哪里,虽然,然后,而且,不过,只是,还有"
                    + ",です,ます,ました,ません,こと,もの,から,まで,より,ので,のに,けど,けれど"
                    + ",など,そして,しかし,また,まだ,もう,という,本当に"
                    + ",그래서,하지만,그리고,그런데,왜냐하면,때문에,에서,으로,까지,부터,이나,또는";

    private WordCloudStopwords() {
    }

    public static Set<String> parse(String value) {
        String raw = (value == null || value.trim().isEmpty()) ? DEFAULT : value;
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split("[\\s,，、;；]+")) {
            String t = part.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    public static boolean isStopword(String token, Collection<String> stopwords) {
        if (token == null) {
            return true;
        }
        String t = token.toLowerCase(Locale.ROOT).trim();
        return t.isEmpty() || stopwords.contains(t);
    }
}
