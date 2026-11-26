/**
 * Word lists and fancy text characters used by {@link TitleCleanup}, from the DeArrow browser extension.
 * <p>
 * Updated by hand from:
 * https://github.com/ajayyy/DeArrow/blob/master/src/titles/titleFormatterData.ts
 * https://gist.github.com/ajayyy/2db5acc76fb2e0cb305b06549445e0aa
 */

package app.morphe.extension.youtube.patches.dearrow;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

final class TitleCleanupData {

    /**
     * Words shown as written, such as acronyms of 4 or more letters. Matched with the same case.
     */
    static final Set<String> KEEP_CASE = Set.of(
            "NASA", "osu!", "PETA", "DEFCONConference", "DEFCON", "HDHR", "HDDT", "HDDTHR", "TUYU", "umu.", "MIMI",
            "S3RL", "NOMA", "DECO*27", "EVO+", "VINXIS", "IOSYS", "fhána", "LGBT", "LGBTQ", "LGBTQIA", "LGBTQ+IA",
            "LGBTQ2S", "BIPOC", "STFU", "TLDR", "TOTK", "BOTW", "SAINTCON", "TASBOT", "FNAF", "IANA", "OSHA",
            "NAFTA", "SCOTUS", "CPAN", "SWAT", "USAF", "ADHD", "IONOS", "NORAD", "UNHRC", "LDAC", "NVENC", "HEVC",
            "NVBFC", "IMAX", "CUDA", "VAAPI", "JPEG", "IETF", "zstd", "LZMA", "ANOVA", "HEIF", "HTML", "HDTV",
            "HDMI", "EULA", "GDPR", "CCPA", "HTTP", "HTTPS", "BIOS", "DMCA", "GUID", "JSON", "MIDI", "MMORPG",
            "OLED", "RHEL", "SFTP", "PCIe", "SSID", "UEFI", "UUID", "VRAM", "XMPP", "YAML", "OWSLA", "DJVI",
            "PSYQUI", "INZO", "MYRNE", "KNOWER", "PYLOT", "USAO", "TESV", "WRLD", "LAPD", "NYPD", "NVMe",
            "WYSIWYG", "TAS", "USSR", "Yu-Gi-Oh!", "II", "III", "IV", "VI", "VII", "VIII", "XIV", "XV", "XVI",
            "XVII", "XVIII", "VIA", "CCCP", "DIY", "SNES", "INTV", "PICO-8", "PICO8", "WSWAN", "PCE", "PCECD",
            "FPGA", "GTA", "bell hooks", "TOOOL", "drawholic", "TF2", "L4D", "L4D2", "P/ECE", "CDROM", "CD-ROM",
            "EFF", "AAA", "IQ", "GEEKOM", "AI", "RVC", "DisplayPort", "BFDI", "BFDIA", "IDFB", "TPOT", "WWII",
            "CS2", "CS:GO", "CSGO", "SCART", "ASMR", "CNBC", "mm", "cm", "km", "ft", "yd", "kg", "mg", "lb", "lbs",
            "oz", "mL", "gal", "m/s", "km/h", "mph", "ft/s", "in/s", "lbf", "cal", "kcal", "kW", "Hz", "kHz",
            "MHz", "GHz", "THz", "Pa", "kPa", "MPa", "GPa", "atm", "mmHg", "torr", "GPA", "ABC", "AFK", "AKA",
            "ASAP", "ATM", "BBC", "BC", "BCE", "BDSM", "BMW", "CCTV", "CD", "CIA", "CSS", "DC", "DLC", "DNA",
            "DPS", "DUI", "DVD", "DWI", "EMS", "ETA", "FAQ", "FBI", "FCC", "FDA", "FEMA", "FLOTUS", "FOMO", "FPS",
            "FTC", "GIF", "GPS", "HDD", "IRL", "IRS", "ISIL", "ISIS", "ISP", "JPG", "LAFD", "LCD", "LSD", "MBA",
            "MDMA", "MMA", "MMO", "MMR", "MMS", "MP3", "MP4", "NATO", "NBA", "NBC", "NFL", "NHL", "NHS", "NRA",
            "NSA", "NSFL", "NSFW", "NYC", "OCD", "OHS", "PDF", "PHP", "PMS", "PNG", "POTUS", "PTSD", "QR", "RLCS",
            "RPG", "RSVP", "SMS", "SQL", "SUV", "TV", "UFO", "UNGA", "URL", "USA", "USB", "USPS", "VCR", "VHS",
            "VIP", "VOD", "VPN", "WHS", "WWE", "WWF", "WWI", "XML", "LGBTQIA2S+", "NVFBC", "DALL-E", "DALL·E",
            "iilluminaughtii", "TOML", "DJ", "SPTV", "VST", "EQ", "JWST", "HP", "STM", "ESP", "TCP", "TCP/IP",
            "FTP", "UDP", "DSKY", "DRO", "MCSR", "ELO", "GT:NH"
    );

    /**
     * Words starting with any of these are shown as written. Matched with the same case.
     */
    static final Set<String> KEEP_CASE_PREFIXES = Set.of(
            "osu!", "de_", "cs_", "ar_", "as_", "es_", "dz_", "fy_", "aim_", "zm_", "jail_", "bb_", "gg_", "awp_",
            "df_", "deathrun_"
    );

    /**
     * Short words that are not acronyms when written in capitals. Matched in lowercase.
     */
    static final Set<String> NOT_ACRONYMS = Set.of(
            "not", "see", "be", "you", "are", "is", "it", "of", "the", "to", "new", "end", "won", "sue", "day",
            "fly", "so", "one", "two", "six", "ten", "can", "pro", "why", "did", "now", "too", "bad", "big", "all",
            "old"
    );

    /**
     * Words written in lowercase inside a title in title case. Matched in lowercase.
     */
    static final Set<String> TITLE_CASE_LOWERCASE = Set.of(
            "a", "an", "the", "and", "but", "or", "nor", "for", "yet", "so", "as", "in", "of", "on", "to", "from",
            "into", "with", "w/", "upon", "at", "by", "via", "vs", "v.s.", "vs.", "ft", "ft.", "feat", "feat.",
            "etc.", "etc"
    );

    /**
     * Abbreviations that do not end a sentence. Matched in lowercase.
     */
    static final Set<String> NOT_SENTENCE_ENDS = Set.of(
            "v.s.", "vs.", "ft.", "feat.", "mr.", "mrs.", "ms.", "dr.", "prof.", "sr.", "jr."
    );

    /**
     * Fancy text character -> plain letter or number.
     */
    static final Map<Integer, String> FANCY_TEXT = fancyText(
            "0", "⓿₀⓪𝟎𝟘𝟶𝟢𝟬０🄀🄁🄋🄌",
            "1", "⥠₁①⓵⑴𝟏𝟙𝟷𝟣𝟭１⒈🄂➀➊❶",
            "2", "₂②⓶⑵𝟐𝟚𝟸𝟤𝟮２⒉🄃➁➋❷",
            "3", "₃③⓷⑶𝟑𝟛𝟹𝟥𝟯３⒊🄄➂➌❸",
            "4", "₄④⓸⑷𝟒𝟜𝟺𝟦𝟰４⒋🄅➃➍❹",
            "5", "Ƽ₅⑤⓹⑸𝟓𝟝𝟻𝟧𝟱５⒌🄆➄➎❺",
            "6", "₆⑥⓺⑹𝟔𝟞𝟼𝟨𝟲６⒍🄇➅➏❻",
            "7", "₇⑦⓻⑺𝟕𝟟𝟽𝟩𝟳７⒎🄈➆➐❼",
            "8", "₈⑧⓼⑻𝟖𝟠𝟾𝟪𝟴８⒏🄉➇➑❽",
            "9", "₉⑨⓽⑼𝟗𝟡𝟿𝟫𝟵９⒐🄊➈➒❾",
            "10", "⓾❿➉➓⑩⑽⒑🔟",
            "11", "⑪⑾⒒⓫",
            "12", "⑫⑿⒓⓬",
            "13", "⑬⒀⒔⓭",
            "14", "⑭⒁⒕⓮",
            "15", "⑮⒂⒖⓯",
            "16", "⑯⒃⒗⓰",
            "17", "⑰⒄⒘⓱",
            "18", "⑱⒅⒙⓲",
            "19", "⑲⒆⒚⓳",
            "20", "⑳⒇⒛⓴",
            "A", "𝐀𝚨𝑨𝜜𝓐𝕬𝖠𝗔𝝖𝘼𝞐𝘈𝙰𝒜𝐴𝛢𝔸𝔄🅐🅰Ⓐ🄰Ａᴬ🄐ᴀ",
            "B", "𝐁𝚩𝑩𝜝𝓑𝕭𝖡𝗕𝝗𝘽𝞑𝘉𝙱ℬ𝐵𝛣𝔹𝔅🅑🅱Ⓑ🄱Ｂᴮ𐞄🄑ʙ",
            "C", "𝐂𝑪𝓒𝕮𝖢𝗖𝘾𝘊𝙲𝒞𝐶ℂℭ🅒🅲Ⓒ🄲Ｃꟲ🄒ᴄ",
            "D", "ⅅƊ𝐃𝑫𝓓𝕯𝖣𝗗𝘿𝘋𝙳𝒟𝐷𝔻𝔇🅓🅳Ⓓ🄳Ｄᴰ🄓ᴅ",
            "E", "𝐄𝚬𝑬𝜠𝓔𝕰𝖤𝗘𝝚𝙀𝞔𝘌𝙴ℰ𝐸𝛦𝔼𝔈🅔🅴Ⓔ🄴Ｅᴱ🄔ᴇ",
            "F", "𝟊𝐅𝑭𝓕𝕱𝖥𝗙𝙁𝘍𝙵ℱ𝐹𝔽𝔉🅕🅵Ⓕ🄵Ｆꟳ🄕ꜰ",
            "G", "𝐆𝑮𝓖𝕲𝖦𝗚𝙂𝘎𝙶𝒢𝐺𝔾𝔊🅖🅶Ⓖ🄶Ｇᴳ𐞒🄖ɢ",
            "H", "𝐇𝚮𝑯𝜢𝓗𝕳𝖧𝗛𝝜𝙃𝞖𝘏𝙷ℋ𝐻𝛨ℍℌ🅗🅷Ⓗ🄷Ｈᴴ𐞖🄗ʜ",
            "I", "Ⅰ𝐈𝚰𝑰𝜤𝓘𝕴𝖨𝗜𝝞𝙄𝞘𝘐𝙸ℐ𝐼𝛪𝕀ℑ🅘🅸Ⓘ🄸Ｉᴵᶦ🄘ɪ",
            "J", "𝐉𝑱𝓙𝕵𝖩𝗝𝙅𝘑𝙹𝒥𝐽𝕁𝔍🅙🅹Ⓙ🄹Ｊᴶ🄙ᴊ",
            "K", "ĸƘK𝐊𝚱𝑲𝜥𝓚𝕶𝖪𝗞𝝟𝙆𝞙𝘒𝙺𝒦𝐾𝛫𝕂𝔎🅚🅺Ⓚ🄺Ｋᴷ🄚ᴋ",
            "L", "𝐋𝑳𝓛𝕷𝖫𝗟𝙇𝘓𝙻ℒ𝐿𝕃𝔏🅛🅻Ⓛ🄻Ｌᴸᶫ🄛ʟ",
            "M", "Ⅿ𝐌𝚳𝑴𝜧𝓜𝕸𝖬𝗠𝝡𝙈𝞛𝘔𝙼ℳ𝑀𝛭𝕄𝔐🅜🅼Ⓜ🄼Ｍᴹ🄜ᴍ",
            "N", "𝐍𝚴𝑵𝜨𝓝𝕹𝖭𝗡𝝢𝙉𝞜𝘕𝙽𝒩𝑁𝛮ℕ𝔑🅝🅽Ⓝ🄽Ｎᴺᶰ🄝ɴ",
            "O", "𝐎𝚶𝑶𝜪𝓞𝕺𝖮𝗢𝝤𝙊𝞞𝘖𝙾𝒪𝑂𝛰𝕆𝔒🅞🅾Ⓞ🄾Ｏᴼ🄞ᴏ",
            "P", "Ƥ𝐏𝚸𝑷𝜬𝓟𝕻𝖯𝗣𝝦𝙋𝞠𝘗𝙿𝒫℘𝑃𝛲ℙ𝔓🅟🅿Ⓟ🄿Ｐᴾ🄟ᴘ",
            "Q", "𝐐𝑸𝓠𝕼𝖰𝗤𝙌𝘘𝚀𝒬𝑄ℚ𝔔🅠🆀Ⓠ🅀Ｑꟴ🄠ꞯ",
            "R", "𝐑𝑹𝓡𝕽𝖱𝗥𝙍𝘙𝚁ℛ𝑅ℝℜ🅡🆁Ⓡ🅁Ｒᴿ𐞪🄡ʀ",
            "S", "🄪𝐒𝑺𝓢𝕾𝖲𝗦𝙎𝘚𝚂𝒮𝑆𝕊𝔖🅢🆂Ⓢ🅂Ｓ🄢ꜱ",
            "T", "🝨𝐓𝚻𝑻𝜯𝓣𝕿𝖳𝗧𝝩𝙏𝞣𝘛𝚃𝒯𝑇𝛵𝕋𝔗🅣🆃Ⓣ🅃Ｔᵀ🄣ᴛ",
            "U", "𝐔𝑼𝓤𝖀𝖴𝗨𝙐𝘜𝚄𝒰𝑈𝕌𝔘🅤🆄Ⓤ🅄Ｕᵁᶸ🄤ᴜ",
            "V", "Ⅴ𝐕𝑽𝓥𝖁𝖵𝗩𝙑𝘝𝚅𝒱𝑉𝕍𝔙🅥🆅Ⓥ🅅Ｖⱽ🄥ᴠ",
            "W", "𝐖𝑾𝓦𝖂𝖶𝗪𝙒𝘞𝚆𝒲𝑊𝕎𝔚🅦🆆Ⓦ🅆Ｗᵂ🄦ᴡ",
            "X", "Ⅹ╳𝐗𝚾𝑿𝜲𝓧𝖃𝖷𝗫𝝬𝙓𝞦𝘟𝚇𝒳𝑋𝛸𝕏𝔛🅧🆇Ⓧ🅇Ｘ🄧",
            "Y", "𝐘𝚼𝒀𝜰𝓨𝖄𝖸𝗬𝝪𝙔𝞤𝘠𝚈𝒴𝑌𝛶𝕐𝔜🅨🆈Ⓨ🅈Ｙ𐞲🄨ʏ",
            "Z", "𝐙𝚭𝒁𝜡𝓩𝖅𝖹𝗭𝝛𝙕𝞕𝘡𝚉𝒵𝑍𝛧ℤℨ🅩🆉Ⓩ🅉Ｚ🄩ᴢ",
            "a", "⍺ɑ⍶ª𝐚𝛂𝒂𝜶𝓪𝖆𝖺𝗮𝝰𝙖𝞪𝘢𝚊𝒶𝑎𝛼𝕒𝔞ⓐａᵃᵅ⒜ₐ",
            "b", "𝐛𝒃𝓫𝖇𝖻𝗯𝙗𝘣𝚋𝒷𝑏𝕓𝔟ⓑｂᵇ⒝",
            "c", "🝌ⅽ𝐜𝒄𝓬𝖈𝖼𝗰𝙘𝘤𝚌𝒸𝑐𝕔𝔠ⓒｃᶜ⒞",
            "d", "ⅆɗ𝐝𝒅𝓭𝖉𝖽𝗱𝙙𝘥𝚍𝒹𝑑𝕕𝔡ⓓ🆥ｄᵈ⒟",
            "e", "ꬲ℮𝐞𝒆𝓮𝖊𝖾𝗲𝙚𝘦𝚎ℯ𝑒𝕖𝔢ⓔｅᵉ⒠ₑ",
            "f", "ꬵꞙẝƒ𝐟𝒇𝓯𝖋𝖿𝗳𝙛𝘧𝚏𝒻𝑓𝕗𝔣ⓕｆᶠ⒡",
            "g", "ᶢɡ𝐠𝒈𝓰𝖌𝗀𝗴𝙜𝘨𝚐ℊ𝑔𝕘𝔤ⓖｇᵍ⒢",
            "h", "ℎ𝐡𝒉𝓱𝖍𝗁𝗵𝙝𝘩𝚑𝒽𝕙𝔥ⓗｈʰ⒣ₕ",
            "i", "ℹⅈⁱ𝐢𝒊𝓲𝖎𝗂𝗶𝙞𝘪𝚒𝒾𝑖𝕚𝔦ⓘｉ⒤ᵢ",
            "j", "ʝ𝚥ⅉ𝐣𝒋𝓳𝖏𝗃𝗷𝙟𝘫𝚓𝒿𝑗𝕛𝔧ⓙｊʲ⒥ⱼ",
            "k", "𝐤𝒌𝓴𝖐𝗄𝗸𝙠𝘬𝚔𝓀𝑘𝕜𝔨ⓚｋᵏ⒦ₖ",
            "l", "𝐥𝒍𝓵𝖑𝗅𝗹𝙡𝘭𝚕𝓁ℓ𝑙𝕝𝔩ⓛｌˡ⒧ₗ",
            "m", "ⅿ𝐦𝒎𝓶𝖒𝗆𝗺𝙢𝘮𝚖𝓂𝑚𝕞𝔪ⓜｍᵐ⒨ₘ",
            "n", "ⁿ𝐧𝒏𝓷𝖓𝗇𝗻𝙣𝘯𝚗𝓃𝑛𝕟𝔫ⓝｎ⒩ₙ",
            "o", "𝐨𝛐𝒐𝝄𝓸𝖔𝗈𝗼𝝾𝙤𝞸𝘰𝚘ℴ𝑜𝜊𝕠𝔬ⓞｏᵒ⒪ₒ",
            "p", "ƿ⍴℗𝐩𝒑𝓹𝖕𝗉𝗽𝙥𝘱𝚙𝓅𝑝𝕡𝔭ⓟｐᵖ⒫ₚ",
            "q", "𝐪𝒒𝓺𝖖𝗊𝗾𝙦𝘲𝚚𝓆𝑞𝕢𝔮ⓠｑ𐞥⒬",
            "r", "ꭇ𝐫𝒓𝓻𝖗𝗋𝗿𝙧𝘳𝚛𝓇𝑟𝕣𝔯ⓡｒʳ⒭ᵣ",
            "s", "𝐬𝒔𝓼𝖘𝗌𝘀𝙨𝘴𝚜𝓈𝑠𝕤𝔰ⓢｓˢ⒮ₛ",
            "t", "𝐭𝒕𝓽𝖙𝗍𝘁𝙩𝘵𝚝𝓉𝑡𝕥𝔱ⓣｔᵗ⒯ₜ",
            "u", "𝐮𝛖𝒖𝝊𝓾𝖚𝗎𝘂𝞄𝙪𝞾𝘶𝚞𝓊𝑢𝜐𝕦𝔲ⓤｕᵘᶷ⒰ᵤ",
            "v", "ⅴ𝐯𝛎𝒗𝝂𝓿𝖛𝗏𝘃𝝼𝙫𝞶𝘷𝚟𝓋𝑣𝜈𝕧𝔳ⓥｖᵛ⒱ᵥ",
            "w", "𝐰𝒘𝔀𝖜𝗐𝘄𝙬𝘸𝚠𝓌𝑤𝕨𝔴ⓦｗʷ⒲",
            "x", "𝛘𝜒𝞆ⅹ𝐱𝒙𝝌𝔁𝖝𝗑𝘅𝙭𝟀𝘹𝚡𝓍𝑥𝕩𝔵ⓧｘˣᵡ⒳ₓ",
            "y", "ỿꭚƴ𝐲𝛄𝒚𝜸𝔂𝖞𝗒𝘆𝝲𝙮𝞬𝘺𝚢𝓎𝑦𝛾𝕪ℽ𝔶ⓨｙʸˠ⒴",
            "z", "𝐳𝒛𝔃𝖟𝗓𝘇𝙯𝘻𝚣𝓏𝑧𝕫𝔷ⓩｚᶻ⒵"
    );

    /**
     * @param plainAndFancy Pairs of a plain letter or number, and its fancy text characters.
     */
    @SuppressWarnings("SameParameterValue")
    private static Map<Integer, String> fancyText(String... plainAndFancy) {
        final int length = plainAndFancy.length;
        Map<Integer, String> map = new HashMap<>(2 * length);
        for (int i = 0; i < length; i += 2) {
            String plain = plainAndFancy[i];
            plainAndFancy[i + 1].codePoints().forEach(codePoint -> map.put(codePoint, plain));
        }
        return map;
    }

    private TitleCleanupData() {
    }
}
