package com.newtube.mobile.ui.channel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class ChannelTitleTest {
    @Test
    public void majorityAuthorNamesTheChannel() {
        assertEquals("MrBeast 2", ChannelTitle.fromAuthors(Arrays.asList("MrBeast 2", "Guest", "MrBeast 2", null, " ")));
        assertEquals("Solo", ChannelTitle.fromAuthors(Collections.singletonList("Solo")));
    }

    @Test
    public void noEvidenceNoName() {
        assertNull(ChannelTitle.fromAuthors(null));
        assertNull(ChannelTitle.fromAuthors(Collections.emptyList()));
        assertNull(ChannelTitle.fromAuthors(Arrays.asList("A", "B")));
        assertNull(ChannelTitle.fromAuthors(Arrays.asList(null, "")));
    }
}
