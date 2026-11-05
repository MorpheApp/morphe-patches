package app.morphe.extension.youtube.patches;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OfflineCaptionUrlTest {
    private static final String URL = "https://www.youtube.com/api/timedtext?v=dQw4w9WgXcQ&caps=asr"
            + "&sparams=ip,ipbits,expire,v,ei,caps&signature=D1C6.9080&key=yt8&lang=en";

    @Test
    public void addsSrv3FormatToUrlWithoutFormat() {
        assertEquals(URL + "&fmt=srv3", OfflineCaptionUrl.withSrv3Format(URL));
    }

    @Test
    public void keepsUrlThatAlreadyAsksForSrv3() {
        assertEquals(URL + "&fmt=srv3", OfflineCaptionUrl.withSrv3Format(URL + "&fmt=srv3"));
    }

    @Test
    public void replacesOtherFormats() {
        assertEquals(URL + "&fmt=srv3", OfflineCaptionUrl.withSrv3Format(URL + "&fmt=vtt"));
        assertEquals(URL + "&fmt=srv3", OfflineCaptionUrl.withSrv3Format(URL + "&fmt"));
        assertEquals(URL + "&fmt=srv3", OfflineCaptionUrl.withSrv3Format(
                URL.replace("&lang=en", "&fmt=srv1&lang=en")));
    }

    @Test
    public void keepsParametersThatOnlyStartWithFormatName() {
        assertEquals(URL + "&fmts=1&fmt=srv3", OfflineCaptionUrl.withSrv3Format(URL + "&fmts=1"));
    }

    @Test
    public void handlesUrlWithoutQueryAndEmptyUrl() {
        String base = "https://www.youtube.com/api/timedtext";
        assertEquals(base + "?fmt=srv3", OfflineCaptionUrl.withSrv3Format(base));
        assertEquals(base + "?fmt=srv3", OfflineCaptionUrl.withSrv3Format(base + "?"));
        assertEquals("", OfflineCaptionUrl.withSrv3Format(""));
    }
}
