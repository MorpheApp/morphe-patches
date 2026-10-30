package app.morphe.extension.youtube.patches;

import org.junit.Test;
import static org.junit.Assert.*;

public class CaptionSourceIdentityTest {
    private static final String URL="https://www.youtube.com/api/timedtext?v=P6pcTK0wTYA&lang=en&kind=asr&sig=a%2Bb";
    private static AutoTranslateCaptionsPatch.CaptionTrackInterface track(String code, String url, String vss) {
        return new AutoTranslateCaptionsPatch.CaptionTrackInterface() {
            public String patch_getLanguageCode(){return code;}
            public String patch_getUrl(){return url;}
            public String patch_getVssId(){return vss;}
            public AutoTranslateCaptionsPatch.CaptionTrackInterface patch_copyTranslation(String code,String url,String vss,CharSequence label){throw new AssertionError("Capture never creates a second native track");}
        };
    }
    @Test public void selectedSourceIdentitySurvivesNativeTranslationAndOpaqueSignatures() {
        String source=CaptionTracks.sourceIdentity(track("en",URL,"a.en.audio_1"));
        assertNotNull(source);
        assertEquals(source,CaptionTracks.sourceIdentity(track("de",URL+"&tlang=de","ta.en.audio_1.de")));
        assertEquals(source,CaptionTracks.sourceIdentity(track("en",URL.replace("a%2Bb","other"),"a.en.audio_1")));
        assertNotEquals(source,CaptionTracks.sourceIdentity(track("en",URL,"a.en.audio_2")));
    }
    @Test public void mismatchedOrUntrustedSourcesAreRejected() {
        assertNull(CaptionTracks.sourceIdentity(track("de",URL+"&tlang=fr","ta.en.fr")));
        assertNull(CaptionTracks.sourceIdentity(track("en",URL.replace("www.youtube.com","youtube.com.invalid"),"a.en")));
        assertNull(CaptionTracks.sourceIdentity(track("en",URL.replace("kind=asr","kind=manual"),"a.en")));
        assertNull(CaptionTracks.sourceIdentity(track("en",URL.replace("v=P6pcTK0wTYA","v=bad"),"a.en")));
    }
}
