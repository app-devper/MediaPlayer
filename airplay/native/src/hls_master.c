#include "hls_master.h"

#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

#define STREAM_INF "#EXT-X-STREAM-INF:"
#define MEDIA "#EXT-X-MEDIA:"

/* Finds attribute `name` (e.g. "AUDIO") in an attribute list, only at an attribute boundary, so
 * "AUDIO" doesn't match inside another name. Returns a pointer to "NAME=", or NULL. */
static const char *find_attr(const char *line, const char *line_end, const char *name) {
    size_t n = strlen(name);
    for (const char *p = line; p + n < line_end; p++) {
        if ((p == line || p[-1] == ':' || p[-1] == ',') && !strncmp(p, name, n) && p[n] == '=') return p;
        if (*p == '"') { /* skip quoted values: they may contain commas */
            const char *q = memchr(p + 1, '"', (size_t) (line_end - p - 1));
            if (!q) return NULL;
            p = q;
        }
    }
    return NULL;
}

/* End of the value that starts at `v` (after a closing quote, or at the next comma). */
static const char *value_end(const char *v, const char *line_end) {
    if (*v == '"') {
        const char *q = memchr(v + 1, '"', (size_t) (line_end - v - 1));
        return q ? q + 1 : line_end;
    }
    const char *c = memchr(v, ',', (size_t) (line_end - v));
    return c ? c : line_end;
}

/* Copies attribute `name` (unquoted) into out; false when missing. */
static bool get_attr(const char *line, const char *line_end, const char *name, char *out, size_t size) {
    const char *a = find_attr(line, line_end, name);
    if (!a) return false;
    const char *v = a + strlen(name) + 1;
    const char *e = value_end(v, line_end);
    if (*v == '"') { v++; if (e > v && e[-1] == '"') e--; }
    size_t len = (size_t) (e - v);
    if (len >= size) len = size - 1;
    memcpy(out, v, len);
    out[len] = '\0';
    return true;
}

static const char *line_end_of(const char *p) {
    const char *e = strchr(p, '\n');
    return e ? e : p + strlen(p);
}

/* Without trailing '\r'. */
static const char *trim_cr(const char *line, const char *end) {
    return (end > line && end[-1] == '\r') ? end - 1 : end;
}

static bool starts_with(const char *p, const char *prefix) { return !strncmp(p, prefix, strlen(prefix)); }

static void append(char **out, const char *s, size_t len) {
    memcpy(*out, s, len);
    *out += len;
}

char *hls_master_single_variant(const char *master, int max_height) {
    /* Pass 1: pick the variant. */
    const char *best = NULL;
    long best_height = -1;
    double best_bandwidth = -1;
    char best_audio[128] = "";
    for (const char *p = master; *p; ) {
        const char *end = line_end_of(p);
        const char *e = trim_cr(p, end);
        if (starts_with(p, STREAM_INF)) {
            char v[64];
            long height = 0;
            double fps = 0, bandwidth = 0;
            if (get_attr(p, e, "RESOLUTION", v, sizeof v)) {
                char *x = strchr(v, 'x');
                if (x) height = strtol(x + 1, NULL, 10);
            }
            if (get_attr(p, e, "FRAME-RATE", v, sizeof v)) fps = strtod(v, NULL);
            if (get_attr(p, e, "BANDWIDTH", v, sizeof v)) bandwidth = strtod(v, NULL);
            bool fits = height <= max_height && fps <= 60.0;
            if (fits && (height > best_height || (height == best_height && bandwidth > best_bandwidth))) {
                best = p;
                best_height = height;
                best_bandwidth = bandwidth;
                if (!get_attr(p, e, "AUDIO", best_audio, sizeof best_audio)) best_audio[0] = '\0';
            }
        }
        p = *end ? end + 1 : end;
    }
    if (!best) return NULL;

    /* Pass 2: copy everything but the other variants, other audio groups and subtitles. */
    char *out = malloc(strlen(master) + 2);
    if (!out) return NULL;
    char *w = out;
    bool skip_uri = false;
    for (const char *p = master; *p; ) {
        const char *end = line_end_of(p);
        const char *e = trim_cr(p, end);
        const char *next = *end ? end + 1 : end;
        if (skip_uri && *p != '#' && e > p) { /* the URI line of a dropped variant */
            skip_uri = false;
            p = next;
            continue;
        }
        if (starts_with(p, STREAM_INF)) {
            if (p != best) {
                skip_uri = true;
            } else {
                /* drop SUBTITLES="..." (that group is gone), keep the rest of the line */
                const char *s = find_attr(p, e, "SUBTITLES");
                if (s) {
                    const char *after = value_end(s + strlen("SUBTITLES="), e);
                    if (after < e && *after == ',') after++;
                    else if (s[-1] == ',') s--;
                    append(&w, p, (size_t) (s - p));
                    append(&w, after, (size_t) (e - after));
                } else {
                    append(&w, p, (size_t) (e - p));
                }
                append(&w, "\n", 1);
            }
            p = next;
            continue;
        }
        if (starts_with(p, MEDIA)) {
            char type[32] = "", group[128] = "";
            get_attr(p, e, "TYPE", type, sizeof type);
            get_attr(p, e, "GROUP-ID", group, sizeof group);
            bool keep = strcmp(type, "SUBTITLES") != 0 &&
                        !(strcmp(type, "AUDIO") == 0 && strcmp(group, best_audio) != 0);
            if (!keep) {
                p = next;
                continue;
            }
        }
        append(&w, p, (size_t) (e - p));
        append(&w, "\n", 1);
        p = next;
    }
    *w = '\0';
    return out;
}
