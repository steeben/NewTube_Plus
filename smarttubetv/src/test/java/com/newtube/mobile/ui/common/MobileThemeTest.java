package com.newtube.mobile.ui.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.view.ContextThemeWrapper;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * NEWTUBE(theme): Theme.NewTube is DayNight with a palette per side (issue #8). The dark side is the
 * palette the app always had; the light side must draw dark controls and text on its white pages
 * (a DayNight parent under the old dark palette drew unchecked radios #060606 on #0F0F0F). The
 * player chrome is dark on both sides. Also pins sentence-case button text (NEWTUBE(buttons)).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MobileThemeTest {

    @Test
    @Config(qualifiers = "night")
    public void darkSideKeepsTheDarkPaletteAndLightControls() {
        Context context = themed(R.style.Theme_NewTube);
        assertEquals(0xFF0F0F0F, color(context, R.color.mobile_color_background));
        assertEquals(0xFFFFFFFF, color(context, R.color.mobile_color_on_surface));
        assertEquals(0xFF1E1E1E, color(context, R.color.mobile_color_surface));
        assertTrue(lightControls(context));
    }

    @Test
    @Config(qualifiers = "notnight")
    public void lightSideDrawsDarkControlsOnWhitePages() {
        Context context = themed(R.style.Theme_NewTube);
        assertEquals(0xFFFFFFFF, color(context, R.color.mobile_color_background));
        assertEquals(0xFF0F0F0F, color(context, R.color.mobile_color_on_surface));
        assertFalse(lightControls(context));
    }

    @Test
    @Config(qualifiers = "notnight")
    public void playerChromeStaysDarkOnTheLightSide() {
        // The video area's theme overlay: dark ripples and control colours over the video.
        Context chrome = new ContextThemeWrapper(themed(R.style.Theme_NewTube_Player),
                R.style.ThemeOverlay_NewTube_PlayerChrome);
        assertTrue(lightControls(chrome));
        assertEquals(0xFFFFFFFF, color(chrome, R.color.mobile_player_on_video));
    }

    @Test
    @Config(qualifiers = "night")
    public void playerColoursAreTheSameOnTheDarkSide() {
        Context context = themed(R.style.Theme_NewTube_Player);
        assertEquals(0xFFFFFFFF, color(context, R.color.mobile_player_on_video));
        assertEquals(0xFF4FC3F7, color(context, R.color.mobile_player_cast_active));
        assertEquals(0x26FFFFFF, color(context, R.color.mobile_player_pill));
    }

    @Test
    @Config(qualifiers = "notnight")
    public void buttonTextIsSentenceCase() {
        Context themed = themed(R.style.Theme_NewTube);
        TypedArray theme = themed.obtainStyledAttributes(new int[] {R.attr.textAppearanceButton});
        int appearance = theme.getResourceId(0, 0);
        theme.recycle();

        TypedArray style = themed.obtainStyledAttributes(appearance,
                new int[] {android.R.attr.textAllCaps, android.R.attr.letterSpacing});
        boolean allCaps = style.getBoolean(0, true);
        float letterSpacing = style.getFloat(1, -1f);
        style.recycle();

        assertFalse("button text must not be ALL CAPS", allCaps);
        assertEquals(0f, letterSpacing, 0.0001f);
    }

    @Test
    public void themeModesMapToOneNightModeEverywhere() {
        assertEquals(Configuration.UI_MODE_NIGHT_UNDEFINED, ThemeMode.forcedNight(ThemeMode.SYSTEM));
        assertEquals(Configuration.UI_MODE_NIGHT_NO, ThemeMode.forcedNight(ThemeMode.LIGHT));
        assertEquals(Configuration.UI_MODE_NIGHT_YES, ThemeMode.forcedNight(ThemeMode.DARK));
        // AppCompat's dialogs apply this mode to the screen they open on: it must agree.
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemeMode.appCompatMode(ThemeMode.SYSTEM));
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeMode.appCompatMode(ThemeMode.LIGHT));
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemeMode.appCompatMode(ThemeMode.DARK));
    }

    @Test
    public void anInstallThatRanBeforeIsAnUpgrade() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences migrations = context.getSharedPreferences("theme_test_migrations", Context.MODE_PRIVATE);
        migrations.edit().clear().commit();
        assertFalse("a fresh install has no migration entries", ThemeMode.isExistingInstall(context, migrations));

        migrations.edit().putBoolean("caption_style_white_default", true).commit();
        assertTrue(ThemeMode.isExistingInstall(context, migrations));
    }

    private static boolean lightControls(Context context) {
        TypedArray a = context.obtainStyledAttributes(new int[] {
                R.attr.colorControlNormal, android.R.attr.textColorPrimary});
        ColorStateList controlNormal = a.getColorStateList(0);
        ColorStateList textPrimary = a.getColorStateList(1);
        a.recycle();
        boolean light = luminance(controlNormal.getDefaultColor()) > 0.4;
        assertEquals("text and controls on the same side: " + Integer.toHexString(textPrimary.getDefaultColor()),
                light, luminance(textPrimary.getDefaultColor()) > 0.5);
        return light;
    }

    private static int color(Context context, int res) {
        return ContextCompat.getColor(context, res);
    }

    private static Context themed(int style) {
        return new ContextThemeWrapper(RuntimeEnvironment.getApplication(), style);
    }

    /** Perceived brightness 0..1 (alpha ignored: both colours are drawn on the opaque app background). */
    private static double luminance(int color) {
        return (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
    }
}
