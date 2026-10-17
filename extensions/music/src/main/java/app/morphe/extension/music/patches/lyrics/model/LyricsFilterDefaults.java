/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static app.morphe.extension.shared.settings.Setting.parent;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.settings.StringSetting;

/**
 * The user-configurable rules lyrics are cleaned and filtered with, together with the values
 * they start out at.
 *
 * <p>Kept out of {@link Settings} so the rules and the code applying them
 * ({@link LyricsFilter}) read as one unit. {@link Settings} calls {@link #register()} from a
 * static block: touching this class is what puts the three settings into the framework's
 * registry, which the preference screen looks keys up in.
 */
public final class LyricsFilterDefaults {

    /**
     * Strips the noise a provider repeats in a title, such as "Official Video", "Remaster",
     * "【插曲】" or "- Topic", before the title is searched for.
     */
    public static final String DEFAULT_LYRICS_REGEX =
            "(?i)\\s*[（(\\[【][^）)\\]】]*?(?:official\\s+)?(?:video|audio|music\\s+video|lyrics?\\s+video|visualizer|mv)[^）)\\]】]*[）)\\]】]"
                    + "|(?i)\\s*[（(\\[【][^）)\\]】]*?remaster(?:ed)?(?:\\s+\\d{4})?[^）)\\]】]*[）)\\]】]"
                    + "|(?i)\\s*[（(\\[【](?:mono|stereo|hq|hd|4k|8k)[）)\\]】]"
                    + "|[（(\\[【][^）)\\]】]*(?:主题曲|片尾曲|插曲|片头曲|广告曲|推广曲)[^）)\\]】]*[）)\\]】]"
                    + "|[（(\\[【][^）)\\]】]*[\\uff1a:][^）)\\]】]*[）)\\]】]"
                    + "|(?i)\\s*-\\s*topic$";

    /** Applied to the original lyrics text only; the whole line is dropped when it matches. */
    public static final String DEFAULT_LYRICS_TEXT_FILTER =
            ".*?(?:"
                    + "未经.*?(?:不得|禁止)"
                    + "|本作品声明.*?著作权权利保留.*?不得"
                    + "|本字幕由TME AI技术生成"
                    + "|部分素材源自网络"
                    + "|酷我音乐.*?特别出品"
                    + "|酷狗.*?星曜计划"
                    + "|酷狗.*?国潮"
                    + "|酷狗音乐.*?就是歌多"
                    + "|听国潮.*?酷狗"
                    + "|未经许可.*?(?:翻唱|盗版)"
                    + "|本作品.*?授权"
                    + "|已获得.*?授权"
                    + "|星曜计划.*?企划|黑胶复刻"
                    + "|此歌曲为没有填词的纯音乐"
                    + "|纯音乐，请欣赏"
                    + "|此歌曲由Vemus未音APP\\.制作 音乐创作如此简单！"
                    + "|酷狗音乐『万物皆可dj』企划"
                    + "|『听dj, 到中国酷狗』"
                    + "|本歌曲来自〖飓风计划〗"
                    + "|10亿现金激励，千亿流量扶持！"
                    + "|版权所有\\s*未经\\s*许可\\s*请勿\\s*使用"
                    + "|想听的歌在评\\s*论区"
                    + ").*";

    /**
     * Comma separated credit labels. A line starting with one of them is treated as a credit
     * and pushed to the bottom instead of being shown in place.
     */
    public static final String DEFAULT_LYRICS_CREDIT_LINE_REGEX =
            "1st Violin,2nd Violin,A&R,A.Guita,AU,Additional Drums Engineering,Administer,Administered,Administered By,"
                    + "Administering,Administers,Agency,Album,All Instruments,Arranged,Arranged By,Arranger,Arrangers,"
                    + "Arranging,Artist,Artists,Atmos混音,Assistant Engineer,Assistant Engineers,"
                    + "Assistant Mix Engineer,Assistant Mix Engineers,Author,Authoring,Authors,Autotune,Backed,"
                    + "Background,Background Vocal,Background Vocals,Backing,Backing Vocals 和音,Bass,Bass 贝斯,"
                    + "Bass Guitar,Bass by,Brass Arrange,Brass Band,COS,CV,Cello,Cello 大提琴,Child,Child Choir,"
                    + "Child Choir Instruction,Child Lead,Children,Chorus,Composed By,Composer,Composers,Composing,"
                    + "Computer Programming by,Conductor,Copyright,Cover,Credit,DJ,Digital Edited,Digital Edited By,"
                    + "Digital Editing,Directed,Directed By,Directing,Director,Directors,Drum,Drum programming,Drums,"
                    + "Duration,E.Guitar,Edited,Edited By,Editing Engineer,Editing Engineers,Editor,Editors,"
                    + "Electric Guitar,Engineer,Engineered,Engineered By,Engineering,Engineers,Executed,Executing,"
                    + "Executive,Executive Producer,First Violin 第一小提琴,Guitar,Guitar by,Guitars,Guitars 吉他,"
                    + "Group,Harmony,Illustration,ISRC,Instrumental Technician,Keyboard,Keyboard by,Keyboard 键盘,"
                    + "Keyboard/Music Arrangement 键盘与编曲,LA,Label A&R,Lang,Language,Lead,Leader,Leaders,Length,"
                    + "Lyric,Lyricist,Lyricist 词,Lyricist 词作,Lyricists,Lyrics,Lyrics By,MV,Main Sample,Manufactory,"
                    + "Manufactured,Manufactured By,Manufacturing,Management,Master,Mastered,Mastered By,Mastering,"
                    + "Mastering 母带后期处理,Mastering Engineer,Mastering Engineers,Mastering Studio,Masters,"
                    + "Mix Engineer,Mix Engineered by,Mixed,Mixed By,Mixed in Dolby Atmos by,Mixer,Mixers,Mixing,"
                    + "Mixing Engineer,Mixing Studio,Music,Musical Director & Mixing Engineer,Movie Effect,OA,OC,OP,OT,"
                    + "Original Lyrics by,Original Title,Original Publisher,Original Writer,PGM,PV,Percussion,"
                    + "Performed,Performed By,Performer,Performers,Performing,Piano,Pro-Tools Editing,Produced,"
                    + "Produced By,Producer,Producer 制作人,Producers,Producing,Program,Programming,Programming by,"
                    + "Published,Published By,Publisher,Publishers,Publishing,Publishing Group,"
                    + "Publishing Group Administered By,QQ,RE,Rap,Record,Recorded,Recorded At,Recorded By,Recorder,"
                    + "Recorders,Recording,Recording & Mixing Engineer,Recording Engineer,Recording Engineers,"
                    + "Recording Engineers by,Recordings,Records,SP,Sample,Sampled,Samples,Sampling,"
                    + "Second Violin 第二小提琴,Singer,Singers,Singing,Song,String Arrangement 弦乐编写,"
                    + "String Recording,String Recording Engineer 弦乐录音师,String Recording Studio 弦乐录音棚,"
                    + "Strings,Strings Arrangement,Strings Arrangement & Piano,Strings Direction,Studio,Sub,"
                    + "Sub Publisher,Subs,Subscribe,Subscribed,Subscriber,Subscribers,Surround,Synthesizer,"
                    + "Synthesizer by,Synthesizers,TA,Title,VE,Ver,Version,Viola,Viola 中提琴,Vocal,Vocal Arrangement,"
                    + "Vocal & Rap Arrangement,Vocal Directed,Vocal Directed By,Vocal Director,Vocal Engineer,"
                    + "Vocal Engineering,Vocal Produced,Vocal Produced By,Vocal Producer,Vocal Producers,Vocals,"
                    + "Vocals Arrangement,Voice,Written,Written By,Writter,专辑,业务联系,业务邮箱,中提,中提琴,中提琴手,"
                    + "中文,中文词SA,主催,主唱,乐器,乐团,乐队,书法,布祖基琴 Bousouki,竖琴吉他,二胡,人声,"
                    + "人声剪辑 Vocal Editing,企业宣传,企划,企宣,伴唱,伴奏,伴舞,低音提琴,低音吉他,作曲,作画,作者,作词,"
                    + "修音,修音师,公司,出品,出品人,创作,创作者,指挥,创意,制谱,制谱 Scoring,制作,制作人,前置混音,剧情,"
                    + "剪纸艺术家,助力推广,助理,协力,厂牌,原唱,原曲,原歌名,原版,原画,原编曲,原翻,原著,原词,原词曲,发型,"
                    + "发布,发布者,发行,口琴,口风琴,古筝,合作,合作伙伴,合作者,合声,合成,合成器,合音,合唱,吉他,后期,"
                    + "吟唱,和声,和音,风笛 Bagpipes,呼麦,唢呐,商务,哨笛 Whistle,团队,图,图片,图画,地址,场景,场景提供,"
                    + "填词,声乐,声音,处理,大提,大提琴,大提琴手,女声,妆造,官方,官方指定音乐合作伙伴,宣传,宣发,宣推,"
                    + "富鲁格,导演,封设,封面,小号,小提,小提琴,小提琴手,第一小提琴,第二小提琴,工作室,工程,工程师,"
                    + "平面设计,平台,弦乐,弦乐团,录音,录音室,录音师,录音棚,录制,微信,微博,快手,念白,总企划,总监,总监制,"
                    + "总策划,总顾问,总营销,手碟,手风琴,打击乐,执行,抖音,短视频平台总统筹,短视频平台宣推,短视频宣推,"
                    + "短视频统筹,拍摄,指定音乐合作伙伴,指导,指导老师,推广,摄影,改编,改编词,文案,时长,曲,曲 Composer,"
                    + "曲Composed by,曲 Music,曲Composer,曲Music,曲名,曲绘,曲编,曲协力,木吉他,木管,杜比全景声,板胡,"
                    + "校准,次中音萨克斯,歌名,歌声,歌手,歌曲,歌词改编,歌唱指导,母带,海外配唱执行,海报,混缩,混缩室,混音,"
                    + "混音室,混音师,混音棚,滤镜,演唱,演奏,漫画,灯光,版本,版权,版权归属,特别鸣谢,"
                    + "特別感謝 Special Thanks to,班卓琴,琵琶,电吉他,电贝斯Electric Bass,电脑工程,电钢琴,男声,画,画师,"
                    + "监制,监唱,私人,童声,笛,笛子,笛萧,策划,管乐,管弦,管弦乐,箫,粤语,经纪,统筹,编,编写,编剧,编唱,编导,"
                    + "编曲,编舞,编舞师,编著,编辑,缩混,网易音乐人商务合作,美工,美声演唱,美术,美术设计,翻唱,翻译,翻译者,"
                    + "联合,联系,联系方式,舞台,舞团,舞曲,舞蹈,艺人制作统筹,艺人经纪,艺人经纪公司,艺人统筹,艺术家,"
                    + "艺术指导,艺术指导老师,艺统,花脸,英文,营销,萧笛,萨克斯,视觉,记,设计,词,词 Lyricist,词 Lyrics,"
                    + "词Lyricist,词Lyrics,词曲作者,词曲提供,词协力,译者,语言,语言代码,说唱,说唱词,调校,调音,谱曲,贝斯,"
                    + "贴唱,造型,邮件,邮箱地址,配唱,采样,钢琴,铜管,键盘,键盘手,长号,长笛,队长,附加,音乐,音乐人,"
                    + "音准调校,音响,音效,音编,音频,项目企划,项目协力,项目总企划,项目总监,项目统筹,项目营销,顾问,领唱,"
                    + "领舞,题字,题记,飓风计划商务合作,马头琴,鸣谢,鼓,鼓手,鼓 Drums,鼓录音,鼓录音室,鼓录制,鼓机,"
                    + "鼓技师Drum Technician,爵士鼓Drum,鼓组编程Drums Arrangement,鼓组 Drums,"
                    + "鼓组录音师 Drum Recording Engineer,鼓组录音室 Drum Recording Studio,鼓组音频编辑,运营";

    public static final StringSetting LYRICS_CUSTOM_REGEX =
            new StringSetting("morphe_music_lyrics_custom_regex", DEFAULT_LYRICS_REGEX, false,
                    parent(Settings.LYRICS_ENABLED));

    public static final StringSetting LYRICS_TEXT_FILTER =
            new StringSetting("morphe_music_lyrics_text_filter", DEFAULT_LYRICS_TEXT_FILTER, false,
                    parent(Settings.LYRICS_ENABLED));

    public static final StringSetting LYRICS_CREDIT_LINE_REGEX =
            new StringSetting("morphe_music_lyrics_credit_line_regex", DEFAULT_LYRICS_CREDIT_LINE_REGEX, false,
                    parent(Settings.LYRICS_ENABLED));

    /**
     * Constructs the three settings above by loading this class.
     *
     * <p>Deliberately reads nothing from this class. {@link Settings} may reach this call from
     * its own static initializer, in which case one of the settings above can still be null;
     * touching one here would hand that null out for good.
     */
    public static void register() {
    }

    private LyricsFilterDefaults() {
    }
}