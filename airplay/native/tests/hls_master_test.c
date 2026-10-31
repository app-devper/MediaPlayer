/* hls_master_single_variant on a YouTube master playlist (as sent over AirPlay, URLs shortened). */
#include "hls_master.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int failures = 0;

#define CHECK(cond) do { if (!(cond)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); failures++; } } while (0)

static const char YOUTUBE[] =
    "#EXTM3U\n"
    "#EXT-X-INDEPENDENT-SEGMENTS\n"
    "#EXT-X-MEDIA:URI=\"mlhls://localhost/itag/233/mediadata.m3u8\",TYPE=AUDIO,GROUP-ID=\"233\",DEFAULT=YES,AUTOSELECT=YES,NAME=\"Default\"\n"
    "#EXT-X-MEDIA:URI=\"mlhls://localhost/itag/234/mediadata.m3u8\",TYPE=AUDIO,GROUP-ID=\"234\",DEFAULT=YES,AUTOSELECT=YES,NAME=\"Default\"\n"
    "#EXT-X-MEDIA:URI=\"https://manifest.googlevideo.com/api/manifest/hls_timedtext_playlist/a,b\",TYPE=SUBTITLES,GROUP-ID=\"vtt\",NAME=\"Thai\",DEFAULT=NO,AUTOSELECT=YES,LANGUAGE=\"th\"\n"
    "#EXT-X-STREAM-INF:BANDWIDTH=1232061,CODECS=\"avc1.4D401F,mp4a.40.2\",RESOLUTION=1280x720,AUDIO=\"234\",SUBTITLES=\"vtt\",FRAME-RATE=25,VIDEO-RANGE=SDR,CLOSED-CAPTIONS=NONE\n"
    "mlhls://localhost/itag/232/mediadata.m3u8\n"
    "#EXT-X-STREAM-INF:BANDWIDTH=235794,CODECS=\"avc1.4D4015,mp4a.40.5\",RESOLUTION=426x240,AUDIO=\"233\",SUBTITLES=\"vtt\",FRAME-RATE=25,VIDEO-RANGE=SDR,CLOSED-CAPTIONS=NONE\n"
    "mlhls://localhost/itag/229/mediadata.m3u8\n"
    "#EXT-X-STREAM-INF:BANDWIDTH=4800000,CODECS=\"avc1.640033,mp4a.40.2\",RESOLUTION=2560x1440,AUDIO=\"234\",SUBTITLES=\"vtt\",FRAME-RATE=25,VIDEO-RANGE=SDR,CLOSED-CAPTIONS=NONE\n"
    "mlhls://localhost/itag/271/mediadata.m3u8\n"
    "#EXT-X-STREAM-INF:BANDWIDTH=2419499,CODECS=\"avc1.640028,mp4a.40.2\",RESOLUTION=1920x1080,AUDIO=\"234\",SUBTITLES=\"vtt\",FRAME-RATE=25,VIDEO-RANGE=SDR,CLOSED-CAPTIONS=NONE\n"
    "mlhls://localhost/itag/270/mediadata.m3u8\n"
    "#EXT-X-STREAM-INF:BANDWIDTH=3500000,CODECS=\"avc1.64002a,mp4a.40.2\",RESOLUTION=1920x1080,AUDIO=\"234\",SUBTITLES=\"vtt\",FRAME-RATE=120,VIDEO-RANGE=SDR,CLOSED-CAPTIONS=NONE\n"
    "mlhls://localhost/itag/999/mediadata.m3u8\n";

static int count(const char *s, const char *needle) {
    int n = 0;
    for (const char *p = strstr(s, needle); p; p = strstr(p + 1, needle)) n++;
    return n;
}

int main(void) {
    char *out = hls_master_single_variant(YOUTUBE, 1080);
    CHECK(out != NULL);
    if (out) {
        /* 1080p at 25 fps: not 1440p (too tall), not 1080p120 (too fast), not lower ones */
        CHECK(count(out, "#EXT-X-STREAM-INF:") == 1);
        CHECK(strstr(out, "RESOLUTION=1920x1080,AUDIO=\"234\",FRAME-RATE=25,") != NULL);
        CHECK(strstr(out, "itag/270/") != NULL);
        CHECK(strstr(out, "itag/232/") == NULL && strstr(out, "itag/229/") == NULL);
        CHECK(strstr(out, "itag/271/") == NULL && strstr(out, "itag/999/") == NULL);
        /* only its audio group, no subtitles */
        CHECK(strstr(out, "GROUP-ID=\"234\"") != NULL);
        CHECK(strstr(out, "GROUP-ID=\"233\"") == NULL);
        CHECK(strstr(out, "SUBTITLES") == NULL && strstr(out, "timedtext") == NULL);
        CHECK(strncmp(out, "#EXTM3U\n#EXT-X-INDEPENDENT-SEGMENTS\n", 36) == 0);
        free(out);
    }

    /* Nothing small enough: keep the original. */
    CHECK(hls_master_single_variant(YOUTUBE, 100) == NULL);

    /* Audio-only (YouTube Music song): no RESOLUTION counts as height 0; best bandwidth wins. */
    const char *audio =
        "#EXTM3U\r\n"
        "#EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS=\"mp4a.40.5\"\r\n"
        "low.m3u8\r\n"
        "#EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS=\"mp4a.40.2\"\r\n"
        "high.m3u8\r\n";
    out = hls_master_single_variant(audio, 1080);
    CHECK(out != NULL);
    if (out) {
        CHECK(strcmp(out, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS=\"mp4a.40.2\"\nhigh.m3u8\n") == 0);
        free(out);
    }

    /* Not a master playlist. */
    CHECK(hls_master_single_variant("#EXTM3U\n#EXTINF:5,\nseg.ts\n", 1080) == NULL);

    if (failures) return 1;
    printf("hls_master_test: all passed\n");
    return 0;
}
