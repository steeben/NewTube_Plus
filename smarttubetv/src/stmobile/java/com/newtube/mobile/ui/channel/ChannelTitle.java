package com.newtube.mobile.ui.channel;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NEWTUBE: the name of a channel opened from a bare id (a youtube.com link), taken from the
 * videos it loads: the author most of them share. A channel's own tabs are its uploads, so the
 * majority author is the channel; a lone collab or repost cannot win.
 */
final class ChannelTitle {
    private ChannelTitle() { }

    @Nullable
    static String fromAuthors(@Nullable List<String> authors) {
        if (authors == null || authors.isEmpty()) {
            return null;
        }
        Map<String, Integer> counts = new HashMap<>();
        String best = null;
        int bestCount = 0;
        for (String author : authors) {
            if (author == null) {
                continue;
            }
            String name = author.trim();
            if (name.isEmpty()) {
                continue;
            }
            Integer prev = counts.get(name);
            int count = prev == null ? 1 : prev + 1;
            counts.put(name, count);
            if (count > bestCount) {
                best = name;
                bestCount = count;
            }
        }
        // One video alone is not evidence enough when the page holds several.
        if (best == null || (bestCount < 2 && authors.size() > 1)) {
            return null;
        }
        return best;
    }
}
