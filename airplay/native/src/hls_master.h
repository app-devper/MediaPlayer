#ifndef HLS_MASTER_H
#define HLS_MASTER_H

/*
 * AirPlay video (YouTube, YouTube Music) sends a master playlist with every quality level and
 * subtitles. Each media playlist is a round trip to the sender before playback can start, and
 * the player then opens every variant to probe it, while only one is ever played.
 *
 * Returns a new malloc'd master playlist with one variant (the tallest with height <= max_height
 * and frame rate <= 60, then the highest bandwidth), only the audio rendition it uses and no
 * subtitles; or NULL to keep the original (no variant found).
 */
char *hls_master_single_variant(const char *master, int max_height);

#endif
