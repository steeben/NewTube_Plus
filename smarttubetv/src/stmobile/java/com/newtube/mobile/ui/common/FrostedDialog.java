package com.newtube.mobile.ui.common;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;

import com.google.android.material.shape.MaterialShapeDrawable;

/**
 * NEWTUBE(frosted): a pop-up as frosted glass (Settings > Appearance > Frosted glass) - its panel
 * see-through over a blur of the screen behind it. The panel keeps Material's shape and margins
 * (only its fill is made translucent); the blur is the system's cross-window blur (Android 12+),
 * and where that is off (older Android, battery saver, some devices) the panel stays nearly
 * opaque so the text never sits on a sharp, busy page.
 */
public final class FrostedDialog {
    private static final int BLUR_BEHIND_DP = 12;
    private static final int PANEL_BLUR_DP = 40;

    private FrostedDialog() {
    }

    /** {@link #apply} and hand the dialog back, to wrap a builder's show(). */
    public static <T extends Dialog> T frost(T dialog) {
        apply(dialog);
        return dialog;
    }

    /** Call after show(). Does nothing while Frosted glass is off. */
    public static void apply(Dialog dialog) {
        if (dialog == null || !UiPrefs.isFrostedGlass(dialog.getContext())) {
            return;
        }
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        float density = dialog.getContext().getResources().getDisplayMetrics().density;
        boolean light = ThemeMode.isLight(dialog.getContext());
        boolean blur = Build.VERSION.SDK_INT >= 31 && window.getWindowManager().isCrossWindowBlurEnabled();
        int base = light ? 0xFFFFFF : 0x1C1C1C;
        int alpha = blur ? (light ? 0xDE : 0xD4) : 0xF5;
        int fill = (alpha << 24) | base;

        Drawable background = window.getDecorView().getBackground();
        if (background instanceof InsetDrawable) {
            background = ((InsetDrawable) background).getDrawable();
        }
        if (background instanceof MaterialShapeDrawable) {
            MaterialShapeDrawable shape = (MaterialShapeDrawable) background;
            shape.setFillColor(ColorStateList.valueOf(fill));
            shape.setStroke(Math.max(1f, density), light ? 0x1F000000 : 0x2EFFFFFF);
        }
        if (blur) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.setBlurBehindRadius(Math.round(BLUR_BEHIND_DP * density));
            params.dimAmount = light ? 0.12f : 0.3f;
            window.setAttributes(params);
            window.setBackgroundBlurRadius(Math.round(PANEL_BLUR_DP * density));
        }
    }

    /**
     * A bottom sheet as frosted glass: call from its show listener. The sheet's surface is the
     * MaterialShapeDrawable its behavior re-sets on every layout, so its fill is changed on that
     * same drawable (a new background would be thrown away); the blur is of the whole screen
     * behind, seen through the sheet.
     */
    public static void applySheet(Dialog dialog, android.view.View sheet) {
        if (dialog == null || sheet == null || !UiPrefs.isFrostedGlass(dialog.getContext())) {
            return;
        }
        Window window = dialog.getWindow();
        float density = dialog.getContext().getResources().getDisplayMetrics().density;
        boolean light = ThemeMode.isLight(dialog.getContext());
        boolean blur = window != null && Build.VERSION.SDK_INT >= 31
                && window.getWindowManager().isCrossWindowBlurEnabled();
        int fill = ((blur ? (light ? 0xDE : 0xD4) : 0xF5) << 24) | (light ? 0xFFFFFF : 0x1C1C1C);
        Runnable tint = () -> {
            Drawable background = sheet.getBackground();
            if (background instanceof MaterialShapeDrawable) {
                MaterialShapeDrawable shape = (MaterialShapeDrawable) background;
                if (shape.getFillColor() == null || shape.getFillColor().getDefaultColor() != fill) {
                    shape.setFillColor(ColorStateList.valueOf(fill));
                    shape.setStroke(Math.max(1f, density), light ? 0x1F000000 : 0x2EFFFFFF);
                }
            }
        };
        tint.run();
        // The behavior sets its drawable on layout; tint whichever one is there after each one.
        sheet.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> tint.run());
        if (blur) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.setBlurBehindRadius(Math.round(24 * density));
            window.setAttributes(params);
        }
    }
}
