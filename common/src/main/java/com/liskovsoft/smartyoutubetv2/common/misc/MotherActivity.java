package com.liskovsoft.smartyoutubetv2.common.misc;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build.VERSION;
import android.os.Bundle;
import android.view.KeyCharacterMap.UnavailableException;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.KeyHelpers;
import com.liskovsoft.sharedutils.locale.LocaleContextWrapper;
import com.liskovsoft.sharedutils.locale.LocaleUpdater;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;
import com.r0adkll.slidr.Slidr;
import com.r0adkll.slidr.model.SlidrConfig;
import com.r0adkll.slidr.model.SlidrListener;
import com.r0adkll.slidr.model.SlidrPosition;

import java.util.ArrayList;
import java.util.List;

public class MotherActivity extends FragmentActivity {
    private static final String TAG = MotherActivity.class.getSimpleName();
    protected static boolean sIsInPipMode;
    private ScreensaverManager mScreensaverManager;
    // Make static in case Don't keep activities enabled in Developer settings
    private static List<OnPermissions> mOnPermissions;
    private static List<OnResult> mOnResults;
    private long mLastKeyDownTime;
    private boolean mEnableThrottleKeyDown;
    private boolean mIsOculusQuestFixEnabled;
    private boolean mIsFullscreenModeEnabled;

    public interface OnPermissions {
        void onPermissions(int requestCode, String[] permissions, int[] grantResults);
    }

    public interface OnResult {
        void onResult(int requestCode, int resultCode, Intent data);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Fixing: Only fullscreen opaque activities can request orientation (api 26)
        // NOTE: You should remove 'screenOrientation' from the manifest.
        //if (VERSION.SDK_INT != 26) {
        //    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        //}
        super.onCreate(savedInstanceState);

        Log.d(TAG, "Starting %s...", this.getClass().getSimpleName());

        mIsOculusQuestFixEnabled = PlayerTweaksData.instance(this).isOculusQuestFixEnabled();
        mIsFullscreenModeEnabled = GeneralData.instance(this).isFullscreenModeEnabled();

        initTheme();

        // Search Fullscreen routine inside onPause() method
        if (!mIsFullscreenModeEnabled) {
            // There's no way to do this programmatically!
            setTheme(R.style.FitSystemWindows);

            // totally disabling the translucency or any color placed on the status bar and navigation bar
            //if (Build.VERSION.SDK_INT >= 19) {
            //    Window w = getWindow();
            //    w.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            //}
        }

        if (mIsOculusQuestFixEnabled && VERSION.SDK_INT != 26) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }

        mScreensaverManager = createScreensaverManager(); // moved below the theme to fix side effects

        //Helpers.addFullscreenListener(this);

        initEdgeSlide();
    }

    /**
     * NEWTUBE(mobile): creation hook so the touch flavor can opt OUT of the screensaver entirely
     * (return null) - the idle dim is TV burn-in protection; phones rely on the system display
     * timeout instead. TV keeps the default. All usages below are null-guarded (no-op when absent).
     */
    protected ScreensaverManager createScreensaverManager() {
        return new ScreensaverManager(this);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && mScreensaverManager != null) {
            mScreensaverManager.enable();
        }

        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && mScreensaverManager != null) {
            mScreensaverManager.enable();
        }

        try {
            return super.dispatchTouchEvent(event);
        } catch (NullPointerException | SecurityException | IllegalStateException | ArrayIndexOutOfBoundsException e) {
            // Attempt to invoke interface method 'boolean android.app.trust.ITrustManager.isDeviceLocked(int)' on a null object reference
            // Permission Denial: starting Intent
            // IllegalStateException: exitFreeformMode: You can only go fullscreen from freeform.
            e.printStackTrace();
            return false;
        }
    }

    @SuppressLint("RestrictedApi")
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) { // handled
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN && mScreensaverManager != null) {
            boolean isKeepScreenOff = mScreensaverManager.isScreenOff() && Helpers.equalsAny(event.getKeyCode(),
                    new int[]{KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN});
            if (!isKeepScreenOff) {
                mScreensaverManager.enable();
            }
        }

        try {
            return super.dispatchKeyEvent(event);
        } catch (NullPointerException | IllegalArgumentException | IllegalStateException | SecurityException | UnavailableException e) {
            // NullPointerException: 'android.view.Window androidx.core.app.ComponentActivity.getWindow()' on a null object reference
            // IllegalArgumentException: View is not a direct child of HorizontalGridView
            // Fatal Exception: java.lang.IllegalStateException
            // android.permission.RECORD_AUDIO required for search (Android 5 mostly)
            // Fatal Exception: java.lang.SecurityException
            // Not allowed to bind to service Intent { act=android.speech.RecognitionService cmp=com.xgimi.duertts/com.baidu.duer.services.tvser
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP) { // shortcut for closing PIP
            PlaybackPresenter.instance(this).forceFinish();
            return true;
        }

        boolean result = super.onKeyDown(keyCode, event);

        // Fix buggy G20s menu key (focus lost on key press)
        return KeyHelpers.isMenuKey(keyCode) || throttleKeyDown(keyCode) || result;
    }

    public void finishReally() {
        try {
            if (VERSION.SDK_INT >= 21 && getViewManager().getTopView() != null) { // remain root activity in recents
                super.finishAndRemoveTask();
            } else {
                super.finish();
            }
        } catch (Exception e) {
            // TextView not attached to window manager (IllegalArgumentException)
        }
    }

    @Override
    protected void attachBaseContext(Context context) {
        Context contextWrapper = null;

        if (context != null) {
            contextWrapper = LocaleContextWrapper.wrap(context, LocaleUpdater.getSavedLocale(context), null);
        }

        super.attachBaseContext(contextWrapper);

        if (contextWrapper != null) {
            applyUiScale(contextWrapper);
        }
    }

    /**
     * NEWTUBE(system-density): the app used to replace every activity's DisplayMetrics with a TV
     * density (2.0 at 1920 px on the long side: 2.5 on a 2400 px phone, 2.525 on a Pixel 9), with
     * scaledDensity equal to it. That ignored the phone's font size and display size wherever the
     * swap held, while views inflated after the system reset the metrics followed them - so the feed
     * cards grew with the phone's font size and the top bar, tabs and settings didn't (issue #3).
     * Now the system's own density and font scale (non-linear on Android 14+) apply everywhere, and
     * UI scale, when it isn't 1.0x, is an override of the system density on top of them. Being a
     * configuration override, the framework keeps it through its own metrics resets.
     */
    private void applyUiScale(Context base) {
        float uiScale = MainUIData.instance(base).getUIScale();
        boolean scaled = uiScale > 0 && !Helpers.floatEquals(uiScale, 1.0f);
        int nightMode = sNightModeSource != null
                ? sNightModeSource.getNightMode(base) : Configuration.UI_MODE_NIGHT_UNDEFINED;

        if (!scaled && nightMode == Configuration.UI_MODE_NIGHT_UNDEFINED) {
            return;
        }

        // One override for both: applyOverrideConfiguration may only be called once per activity.
        Configuration override = new Configuration();
        // Android 7's constructor sets fontScale to 1, which as an override would pin the text to
        // the default size whatever the system's font size (later versions leave it undefined).
        override.fontScale = 0;
        if (scaled) {
            override.densityDpi = Math.round(base.getResources().getConfiguration().densityDpi * uiScale);
        }
        if (nightMode != Configuration.UI_MODE_NIGHT_UNDEFINED) {
            // Night bits (the type bits stay UNDEFINED, so the system's are kept), except the
            // phone's black theme, which sets a type to pick its palette (ThemeMode.OLED).
            override.uiMode = nightMode;
        }
        // The resources this override builds start from the activity's own context, not from the
        // locale wrapper's (attachBaseContext), so the app language goes in here too: without it a
        // forced theme or UI scale showed the screen in the system language until onResume's
        // applySavedLocale caught up, and the views inflated in onCreate kept it.
        java.util.Locale locale = LocaleUpdater.getSavedLocale(base);
        if (locale != null) {
            if (VERSION.SDK_INT >= 24) {
                override.setLocales(new android.os.LocaleList(locale));
            } else {
                override.setLocale(locale);
            }
        }
        applyOverrideConfiguration(override);
    }

    /**
     * NEWTUBE(theme): phone gate. The phone app's Theme setting (System default / Light / Dark)
     * reaches every screen as a night-mode configuration override, applied with UI scale above
     * when the screen is created. The TV flavors never set a source, so nothing changes there.
     */
    public interface NightModeSource {
        /**
         * {@link Configuration#UI_MODE_NIGHT_YES} or {@link Configuration#UI_MODE_NIGHT_NO} to force
         * a theme, {@link Configuration#UI_MODE_NIGHT_UNDEFINED} to follow the system.
         */
        int getNightMode(Context context);
    }

    private static NightModeSource sNightModeSource;

    public static void setNightModeSource(NightModeSource source) {
        sNightModeSource = source;
    }

    @Override
    protected void onResume() {
        try {
            super.onResume();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        }

        // 4K fix with AFR
        applyCustomConfig();

        applyFullscreenModeIfNeeded();

        // Remove screensaver from the previous activity when closing current one.
        // Called on player's next track. Reason unknown.
        if (mScreensaverManager != null) {
            mScreensaverManager.enable();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Remove screensaver from the previous activity when closing current one.
        // Called on player's next track. Reason unknown.
        if (mScreensaverManager != null) {
            mScreensaverManager.disable();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        applyCustomConfig();
    }

    public ScreensaverManager getScreensaverManager() {
        return mScreensaverManager;
    }

    protected void initTheme() {
        int rootThemeResId = MainUIData.instance(this).getColorScheme().browseThemeResId;
        if (rootThemeResId > 0) {
            setTheme(rootThemeResId);
        }
    }

    private void applyCustomConfig() {
        // Fix sudden language change.
        // Could happen when screen goes off or after PIP mode.
        LocaleUpdater.applySavedLocale(this);
    }

    /**
     * NEWTUBE(mobile): window-chrome hook, called from onResume. TV default = immersive fullscreen
     * (hide system bars). The touch flavor overrides this to apply standard phone system bars
     * instead - the TV immersive/translucent flags left on the window were fighting the mobile
     * screens' inset handling (status bar ended up overlapping content after the player's
     * fullscreen round-trip).
     */
    protected void applyFullscreenModeIfNeeded() {
        if (mIsFullscreenModeEnabled) {
            // Most of the fullscreen tweaks could be performed in styles but not all.
            // E.g. Hide bottom navigation bar (couldn't be done in styles).
            Helpers.makeActivityFullscreen2(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (mOnPermissions != null) {
            for (OnPermissions callback : mOnPermissions) {
                callback.onPermissions(requestCode, permissions, grantResults);
            }
            mOnPermissions.clear();
            mOnPermissions = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (mOnResults != null) {
            for (OnResult callback : mOnResults) {
                callback.onResult(requestCode, resultCode, data);
            }
            mOnResults.clear();
            mOnResults = null;
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        // Oculus Quest fix: back button not closing the activity
        if (mIsOculusQuestFixEnabled) {
            finish();
        }
    }

    public void addOnPermissions(OnPermissions onPermissions) {
        if (mOnPermissions == null) {
            mOnPermissions = new ArrayList<>();
        }

        mOnPermissions.remove(onPermissions);
        mOnPermissions.add(onPermissions);
    }

    public void addOnResult(OnResult onResult) {
        if (mOnResults == null) {
            mOnResults = new ArrayList<>();
        }

        mOnResults.remove(onResult);
        mOnResults.add(onResult);
    }

    /**
     * Use this method only upon exiting from the app.<br/>
     * Big troubles with AFR resolution switch!
     */
    public static void invalidate() {
        sIsInPipMode = false;
    }

    /**
     * Comments focus fix<br/>
     * https://stackoverflow.com/questions/34277425/recyclerview-items-lose-focus
     */
    private boolean throttleKeyDown(int keyCode) {
        if (mEnableThrottleKeyDown && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            long current = System.currentTimeMillis();
            if (current - mLastKeyDownTime < 100) {
                return true;
            }

            mLastKeyDownTime = current;
        }

        return false;
    }

    /**
     * Comments focus fix<br/>
     * https://stackoverflow.com/questions/34277425/recyclerview-items-lose-focus
     */
    public void enableThrottleKeyDown(boolean enable) {
        mEnableThrottleKeyDown = enable;
    }

    //@Override
    //public void setTheme(int resid) {
    //    super.setTheme(resid);
    //
    //    // No way to do this programmatically!
    //    if (!GeneralData.instance(this).isFullscreenModeEnabled()) {
    //        super.setTheme(R.style.FitSystemWindows);
    //    }
    //}

    protected ViewManager getViewManager() {
        return ViewManager.instance(this);
    }

    protected GeneralData getGeneralData() {
        return GeneralData.instance(this);
    }

    protected PlayerTweaksData getPlayerTweaksData() {
        return PlayerTweaksData.instance(this);
    }

    protected PlayerData getPlayerData() {
        return PlayerData.instance(this);
    }

    protected MainUIData getMainUIData() {
        return MainUIData.instance(this);
    }

    protected MediaServiceData getMediaServiceData() {
        return MediaServiceData.instance();
    }

    /**
     * NEWTUBE(mobile): overridable. Slidr's left-edge slide-away is TV/car back-navigation; on the
     * touch flavor the left edge belongs to the navigation drawer / system back gesture, so the
     * mobile base disables this entirely.
     */
    protected void initEdgeSlide() {
        if (VERSION.SDK_INT < 21 || !Helpers.isTouchSupported(this) || Utils.isSystemGestureArrowEnabled(this)) {
            return;
        }

        SlidrConfig config = new SlidrConfig.Builder()
                .position(SlidrPosition.LEFT) // Swipe from the left
                .edge(true)              // Only trigger from the screen edge
                .edgeSize(0.18f)              // Grab 18% of the screen (good for cars)
                .scrimStartAlpha(0f)          // Don't dim the background screen
                .scrimEndAlpha(0f)            // Background clear when finished
                .distanceThreshold(0.1f)      // Set drag distance to minimum
                .partial(true)           // Don't do full slide animation
                .listener(new SlidrListener() {
                    @Override
                    public void onSlideStateChanged(int state) {}

                    @Override
                    public void onSlideChange(float percent) {}

                    @Override
                    public void onSlideOpened() {}

                    @Override
                    public boolean onSlideClosed() {
                        // This replaces the default finish() with your back logic
                        onBackPressed();
                        return true; // Tells the library we handled the close
                    }
                })
                .build();

        // Attach to this activity
        Slidr.attach(this, config);
    }
}
