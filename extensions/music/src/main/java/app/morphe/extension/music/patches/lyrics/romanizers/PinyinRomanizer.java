/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import java.util.HashMap;
import java.util.Map;

/**
 * Hanyu Pinyin for a line of Chinese, without tones: a character alone does not know which
 * reading the word around it wants, so the readings from {@link PinyinDictStore} get the line
 * mostly right and the two tables here settle the rest. They hold what a lyric actually runs
 * into, the characters that carry several common readings and the particles.
 *
 * <p>Tones are left off, since a romanized lyric is read by someone who cannot hear them and
 * dropping them keeps the line the length a listener expects.
 */
public final class PinyinRomanizer {

    /** Words whose reading is not the one their characters carry on their own. */
    private static final Map<String, String> WORDS = new HashMap<>();

    /**
     * The particles, whose most common reading is only right in some positions and wrong more
     * often than not.
     */
    private static final Map<Character, String> PARTICLES = new HashMap<>();

    /** The longest word in {@link #WORDS}, so the matcher knows where to stop. */
    private static final int MAX_WORD = 4;

    static {
        word("银行", "yin hang");
        word("行走", "xing zou");
        word("行业", "hang ye");
        word("一行", "yi xing");
        word("行李", "xing li");
        word("行为", "xing wei");
        word("行动", "xing dong");
        word("不行", "bu xing");
        word("还是", "hai shi");
        word("还有", "hai you");
        word("还要", "hai yao");
        word("还会", "hui hui");
        word("都是", "dou shi");
        word("都有", "dou you");
        word("长大", "zhang da");
        word("成长", "cheng zhang");
        word("校长", "xiao zhang");
        word("长江", "chang jiang");
        word("长短", "chang duan");
        word("长度", "chang du");
        word("重庆", "chong qing");
        word("重复", "chong fu");
        word("重新", "chong xin");
        word("重要", "zhong yao");
        word("重新开始", "chong xin kai shi");
        word("音乐", "yin yue");
        word("快乐", "kuai le");
        word("难过", "nan guo");
        word("难道", "nan dao");
        word("着火", "zhao huo");
        word("看着", "kan zhe");
        word("着火", "zhao huo");
        word("血色", "xue se");
        word("血液", "xue ye");
        word("喜欢", "xi huan");
        word("东西", "dong xi");
        word("东西", "dong xi");
        word("事情", "shi qing");
        word("认识", "ren shi");
        word("意思", "yi si");
        word("城市", "cheng shi");
        word("方式", "fang shi");
        word("样子", "yang zi");
        word("屋子", "wu zi");
        word("地方", "di fang");
        word("故事", "gu shi");
        word("时候", "shi hou");
        word("于是", "yu shi");
        word("总是", "zong shi");
        word("开始", "kai shi");
        word("结束", "jie shu");
        word("天使", "tian shi");
        word("出去", "chu qu");
        word("出来", "chu lai");
        word("起来", "qi lai");
        word("下来", "xia lai");
        word("回来", "hui lai");
        word("一个人", "yi ge ren");
        word("什么", "shen me");
        word("怎么", "zen me");
        word("这样", "zhe yang");
        word("那么", "na me");
        word("因为", "yin wei");
        word("所以", "suo yi");
        word("但是", "dan shi");
        word("如果", "ru guo");
        word("已经", "yi jing");
        word("应该", "ying gai");
        word("可能", "ke neng");
        word("知道", "zhi dao");
        word("觉得", "jue de");
        word("告诉", "gao su");
        word("遇到", "yu dao");
        word("发现", "fa xian");
        word("以为", "yi wei");
        word("等待", "deng dai");
        word("秘密", "mi mi");
        word("参加", "can jia");
        word("打折", "da zhe");
        word("折磨", "zhe mo");
        word("宁静", "ning jing");
        word("宁可", "ning ke");
        word("教书", "jiao shu");
        word("教学", "jiao xue");
        word("教训", "jiao xun");
        word("处理", "chu li");
        word("到处", "dao chu");
        word("相处", "xiang chu");
        word("差不多", "cha bu duo");
        word("差别", "cha bie");
        word("出差", "chu chai");
        word("曾经", "ceng jing");
        word("运输", "yun shu");
        word("头晕", "tou yun");
        word("中间", "zhong jian");
        word("中午", "zhong wu");
        word("中国", "zhong guo");
        word("中文", "zhong wen");
        word("首都", "shou du");
        word("说服", "shuo fu");
        word("游说", "you shui");
        word("传说", "chuan shuo");
        word("传统", "chuan tong");
        word("假期", "jia qi");
        word("放假", "fang jia");
        word("便宜", "pian yi");
        word("方便", "fang bian");
        word("干杯", "gan bei");
        word("干净", "gan jing");
        word("干燥", "gan zao");
        word("干部", "gan bu");
        word("干活", "gan huo");
        word("树干", "shu gan");
        word("强迫", "qiang po");
        word("勉强", "mian qiang");
        word("强大", "qiang da");
        word("亲戚", "qin qi");
        word("悄悄", "qiao qiao");
        word("巧克力", "qiao ke li");
        word("长城", "chang cheng");
        word("睡觉", "shui jiao");
        word("睡着", "shui zhao");
        word("宿舍", "su she");
        word("合适", "he shi");
        word("舍不得", "she bu de");
        word("舍得", "she de");
        word("葡萄", "pu tao");
        word("垃圾", "la ji");
        word("陌生", "mo sheng");
        word("抹布", "ma bu");
        word("牌子", "pai zi");
        word("招牌", "zhao pai");
        word("沉着", "chen zhuo");
        word("周末", "zhou mo");
        word("似乎", "si hu");
        word("相似", "xiang si");
        word("似的", "shi de");
        word("巷子", "xiang zi");
        word("会计", "kuai ji");
        word("一会儿", "yi hui r");
        word("现金", "xian jin");
        word("露面", "lou mian");
        word("曝光", "pu guang");
        word("冠军", "guan jun");
        word("单车", "dan che");
        word("传记", "zhuan ji");
        word("自传", "zi zhuan");
        word("便条", "bian tiao");
        word("参差", "cen ci");
        word("精神", "jing shen");
        word("精彩", "jing cai");
        word("挑战", "tiao zhan");
        word("弹琴", "tan qin");
        word("子弹", "dan zi");
        word("率领", "shuai ling");
        word("效率", "xiao lv");
        word("屏幕", "ping mu");
        word("熟悉", "shu xi");
        word("少数", "shao shu");
        word("减少", "jian shao");
        word("少年", "shao nian");
        word("重量", "zhong liang");
        word("高兴", "gao xing");
        word("兴趣", "xing qu");
        word("医院", "yi yuan");
        word("朝代", "chao dai");
        word("朝鲜", "chao xian");
        word("朝夕", "zhao xi");
        word("着手", "zhuo shou");
        word("着想", "zhuo xiang");
        word("穿着", "chuan zhuo");
        word("答应", "da ying");
        word("反应", "fan ying");
        word("蒙上", "meng shang");
        word("蒙古", "meng gu");
        word("剥皮", "bo pi");
        word("剥削", "bo xue");
        word("头发", "tou fa");
        word("大夫", "dai fu");
        word("丈夫", "zhang fu");
        word("太太", "tai tai");
        word("难过", "nan guo");
        word("好人", "hao ren");
        word("思念", "si nian");
        word("说服", "shuo fu");
        word("参与", "can yu");
        word("公主", "gong zhu");
        word("公主", "gong zhu");
        word("落后", "luo hou");
        word("落枕", "lao zhen");
        word("空间", "kong jian");
        word("传奇", "chuan qi");
        word("假期", "jia qi");
        word("结巴", "jie ba");
        word("结果", "jie guo");
        word("结实", "jie shi");
        word("强壮", "qiang zhuang");
        word("强大", "qiang da");
        word("刹车", "sha che");
        word("煞白", "sha bai");
        word("参差", "cen ci");
        word("恶心", "e xin");
        word("爱好", "ai hao");
        word("好客", "hao ke");
        word("似乎", "si hu");
        word("和平", "he ping");
        word("几乎", "ji hu");
        word("台风", "tai feng");
        word("舞台", "wu tai");
        word("相似", "xiang si");
        word("模型", "mo xing");
        word("模样", "mu yang");
        word("落后", "luo hou");
        word("处理", "chu li");
        word("到处", "dao chu");
        word("提防", "di fang");
        word("仿佛", "fang fu");
        word("反复", "fan fu");
        word("头发", "tou fa");
        word("露出", "lu chu");
        word("落叶", "luo ye");
        word("自传", "zi zhuan");
        word("喝水", "he shui");
        word("喝彩", "he cai");
        word("好了", "hao liao");
        word("了解", "liao jie");
        word("褪色", "tui shai");
        word("朝阳", "zhao yang");
        word("朝气", "zhao qi");

        particle('地', "di");
        particle('的', "de");
        particle('了', "le");
        particle('着', "zhe");
        particle('都', "dou");
        particle('还', "hai");
        particle('行', "xing");
        particle('得', "de");
        particle('过', "guo");
        particle('一', "yi");
        particle('不', "bu");
        particle('和', "he");
        // 长 reads chang unless a word says otherwise; the zhang words are in WORDS.
        particle('长', "chang");
        particle('少', "shao");
        particle('为', "wei");
        particle('与', "yu");
        particle('给', "gei");
        particle('差', "cha");
        particle('便', "bian");
        particle('干', "gan");
    }

    private static void word(String word, String reading) {
        WORDS.put(word, reading);
    }

    private static void particle(char character, String reading) {
        PARTICLES.put(character, reading);
    }

    private PinyinRomanizer() {
    }

    /** Pinyin for a Chinese line, with a space between readings. Kana are read by rule and
     * the rest is passed through, so a line that mixes scripts keeps its other half. */
    public static String romanize(String line) {
        StringBuilder out = new StringBuilder(line.length() * 3);
        int i = 0;
        while (i < line.length()) {
            String reading = wordAt(line, i);
            if (reading != null) {
                append(out, reading);
                i += wordLengthAt(line, i);
                continue;
            }

            char c = line.charAt(i);
            String single = single(c);
            if (single != null) {
                append(out, single);
                i++;
                continue;
            }

            // A line with too little kana to count as Japanese can still carry some, and kana
            // need no dictionary.
            if (ScriptDetector.isKana(c)) {
                int kanaEnd = i;
                while (kanaEnd < line.length()
                        && ScriptDetector.isKana(line.charAt(kanaEnd))) {
                    kanaEnd++;
                }
                append(out, KanaRomanizer.romanize(line.substring(i, kanaEnd)));
                i = kanaEnd;
                continue;
            }

            int end = i + 1;
            while (end < line.length() && single(line.charAt(end)) == null
                    && !ScriptDetector.isHan(line.charAt(end))
                    && !ScriptDetector.isKana(line.charAt(end))) {
                end++;
            }
            String rest = line.substring(i, end).trim();
            if (!rest.isEmpty()) append(out, rest);
            i = end;
        }
        return out.toString().trim();
    }

    private static void append(StringBuilder out, String reading) {
        if (out.length() > 0) out.append(' ');
        out.append(reading);
    }

    /** The longest word starting at {@code index}, or null when none does. */
    private static String wordAt(String line, int index) {
        int longest = Math.min(MAX_WORD, line.length() - index);
        for (int length = longest; length >= 2; length--) {
            String reading = WORDS.get(line.substring(index, index + length));
            if (reading != null) return reading;
        }
        return null;
    }

    private static int wordLengthAt(String line, int index) {
        int longest = Math.min(MAX_WORD, line.length() - index);
        for (int length = longest; length >= 2; length--) {
            if (WORDS.containsKey(line.substring(index, index + length))) return length;
        }
        return 1;
    }

    private static String single(char c) {
        String reading = PARTICLES.get(c);
        if (reading != null) return reading;
        if (!isHan(c)) return null;
        return PinyinDictStore.reading(c);
    }

    private static boolean isHan(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }
}
