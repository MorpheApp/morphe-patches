package app.morphe.extension.youtube.patches.dearrow;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TitleCleanupTest {
    private static void check(String title, String expected) {
        String cleaned = TitleCleanup.cleanUpTitle(title);
        assertEquals(title, expected, cleaned);
        assertEquals("Cleaning up again changes: " + cleaned, cleaned, TitleCleanup.cleanUpTitle(cleaned));
    }

    @Test
    public void capitalsUseTheCaseOfTheTitle() {
        check("I BUILT A HOUSE IN 24 HOURS!!! 😱", "I built a house in 24 hours");
        check("This is CRAZY", "This is crazy");
        check("This Is CRAZY", "This Is Crazy");
        check("Video of the YEAR", "Video of the year");
        check("Video Of The YEAR", "Video Of The Year");
        check("MY 1ST VIDEO", "My 1st video");
        check("Minecraft: THE MOVIE IS OUT", "Minecraft: The movie is out");
    }

    @Test
    public void acronymsAndListedWordsKeepTheirCase() {
        check("My NEW PC Setup", "My New PC Setup");
        check("NASA FINALLY DID IT", "NASA finally did it");
        check("WORLD WAR II", "World war II");
        check("U.S.A. TRIP", "U.S.A. trip");
        check("HANDS-ON with the new USB-C port", "Hands-on with the new USB-C port");
        check("Plan A is GOOD", "Plan A is good");
        check("Unboxing the PS5 and 4K TV", "Unboxing the PS5 and 4K TV");
        check("I'M BACK", "I'm back");
        check("#SHORTS are FUN", "#SHORTS are fun");
    }

    @Test
    public void otherLanguages() {
        check("İSTANBUL GEZİSİ çok güzel", "İstanbul gezisi çok güzel");
        check("Das ist ein TEST für Menschen", "Das ist ein test für Menschen");
        check("ПРИВЕТ МИР", "Привет мир");
        check("東京タワーに行ってきた", "東京タワーに行ってきた");
    }

    @Test
    public void fancyText() {
        check("𝐇𝐞𝐥𝐥𝐨 𝐖𝐨𝐫𝐥𝐝", "Hello World");
        check("🅷🅴🅻🅻🅾 there", "Hello there");
        check("ʜᴇʟʟᴏ ᴡᴏʀʟᴅ", "Hello world");
        check("ⓗⓔⓛⓛⓞ friends", "hello friends");
        check("Ｆｕｌｌｗｉｄｔｈ text", "Fullwidth text");
        check("x² + y² = z²", "x² + y² = z²");
        check("Brand™ review", "Brand™ review");
        check("🔟 facts about the Moon", "10 facts about the Moon");
    }

    @Test
    public void replaceFancyTextKeepsOtherCharacters() {
        // Used by the keyword filter, which searches the result.
        assertEquals("Biboo's random encounter with these NPCs in GTA 6 is HILARIOUS",
                TitleCleanup.replaceFancyText("𝗕𝗶𝗯𝗼𝗼'𝘀 𝗿𝗮𝗻𝗱𝗼𝗺 𝗲𝗻𝗰𝗼𝘂𝗻𝘁𝗲𝗿 𝘄𝗶𝘁𝗵 𝘁𝗵𝗲𝘀𝗲 𝗡𝗣𝗖𝘀 𝗶𝗻 𝗚𝗧𝗔 𝟲 𝗶𝘀 𝗛𝗜𝗟𝗔𝗥𝗜𝗢𝗨𝗦"));
        assertEquals("HELLO 😂 x²", TitleCleanup.replaceFancyText("ʜᴇʟʟᴏ 😂 x²"));
    }

    @Test
    public void emojis() {
        check("Fun😂Time", "Fun Time");
        check("Watch this (😂)", "Watch this");
        check("Title | 🔥🔥", "Title");
        check("Best of 2024 🇺🇸", "Best of 2024 🇺🇸");
        check("1️⃣ First", "1 First");
        check("Family 👨‍👩‍👧 trip 👍🏽", "Family trip");
        check("WHY❓", "Why?");
        check("▶ Part 2", "▶ Part 2");
        check("😂😂", "😂😂");
    }

    @Test
    public void punctuation() {
        check("Is it real?!!", "Is it real?");
        check("Wow!!! That was fast", "Wow! That was fast");
        check("Playing osu!", "Playing osu!");
        check("Wait for it...", "Wait for it...");
        check("Normal title", "Normal title");
    }
}
