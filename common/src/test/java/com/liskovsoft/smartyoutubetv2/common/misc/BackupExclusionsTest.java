package com.liskovsoft.smartyoutubetv2.common.misc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

public class BackupExclusionsTest {
    private static final File DATA = new File("/data/user/0/io.github.aleixrodriala.arc");
    private static final File FILES = new File(DATA, "files");
    private static final File PREFS = new File(DATA, "shared_prefs");

    @Test
    public void signInFiles_areExcluded() {
        assertTrue(BackupExclusions.isSignInFile(new File(FILES, "global_prefs/media_service_account_data")));
        assertTrue(BackupExclusions.isSignInFile(new File(FILES, "global_prefs/oauth2_account_data")));
        assertTrue(BackupExclusions.isSignInFile(new File(PREFS, "AuthTokenCache.xml")));
    }

    @Test
    public void settings_areKept() {
        assertFalse(BackupExclusions.isSignInFile(new File(FILES, "global_prefs/media_service_data")));
        assertFalse(BackupExclusions.isSignInFile(new File(FILES, "app_prefs/general_data")));
        assertFalse(BackupExclusions.isSignInFile(new File(PREFS, "io.github.aleixrodriala.arc_preferences.xml")));
        assertFalse(BackupExclusions.isSignInFile(new File(PREFS, "yt_service_prefs.xml")));
    }

    @Test
    public void sameNameElsewhere_isKept() {
        assertFalse(BackupExclusions.isSignInFile(new File(FILES, "app_prefs/media_service_account_data")));
        assertFalse(BackupExclusions.isSignInFile(new File("AuthTokenCache.xml")));
        assertFalse(BackupExclusions.isSignInFile(null));
    }
}
