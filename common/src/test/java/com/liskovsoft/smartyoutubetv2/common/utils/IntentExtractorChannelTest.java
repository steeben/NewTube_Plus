package com.liskovsoft.smartyoutubetv2.common.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
public class IntentExtractorChannelTest {
    private static String channel(String url) {
        return IntentExtractor.extractChannelId(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    @Test
    public void handleLinks() {
        assertEquals("@MrBeast2", channel("https://www.youtube.com/@MrBeast2"));
        assertEquals("@mkbhd", channel("https://www.youtube.com/@mkbhd/videos"));
        assertEquals("@kurzgesagt", channel("https://m.youtube.com/@kurzgesagt"));
        assertEquals("@kurzgesagt", channel("https://m.youtube.com/@kurzgesagt?si=abc"));
    }

    @Test
    public void legacyLinks() {
        assertEquals("UCX6OQ3DkcsbYNE6H8uQQuVA", channel("https://www.youtube.com/channel/UCX6OQ3DkcsbYNE6H8uQQuVA"));
        assertEquals("UCX6OQ3DkcsbYNE6H8uQQuVA", channel("https://www.youtube.com/channel/UCX6OQ3DkcsbYNE6H8uQQuVA/videos"));
        assertEquals("@IngaMezerya", channel("https://www.youtube.com/c/IngaMezerya"));
        assertEquals("@RVLucius", channel("https://youtube.com/user/RVLucius"));
    }

    @Test
    public void videoLinksAreNotChannels() {
        assertNull(channel("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertNull(channel("https://youtu.be/dQw4w9WgXcQ"));
    }
}
