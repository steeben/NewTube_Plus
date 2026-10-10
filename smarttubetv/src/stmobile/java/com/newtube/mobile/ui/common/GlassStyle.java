package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;

import com.google.android.material.button.MaterialButton;

/**
 * NEWTUBE(frosted): the watch page's buttons as glass (Settings > Appearance > Frosted glass): Like
 * and Dislike, Share, Download, Save and Subscribe become see-through pills with a fine light edge
 * - frosted over whatever is behind them (the ambient glow, when that is on).
 */
public final class GlassStyle {
    private GlassStyle() {
    }

    public static boolean isOn(Context context) {
        return UiPrefs.isFrostedGlass(context);
    }

    /** A pill like mobile_watch_pill_bg_touch (36dp inside a 48dp target, with a ripple), of glass. */
    public static Drawable pill(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        boolean light = ThemeMode.isLight(context);
        GradientDrawable glass = new GradientDrawable();
        glass.setCornerRadius(20 * density);
        glass.setColor(light ? 0x8CFFFFFF : 0x1FFFFFFF);
        glass.setStroke(Math.max(1, Math.round(density)), light ? 0x1F000000 : 0x38FFFFFF);
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(light ? 0x1F000000 : 0x33FFFFFF), glass, null);
        int inset = Math.round(6 * density);
        return new InsetDrawable(ripple, 0, inset, 0, inset);
    }

    /** A view's pill background, made glass (and its padding kept). */
    public static void applyPill(View view) {
        if (view == null) {
            return;
        }
        int l = view.getPaddingLeft();
        int t = view.getPaddingTop();
        int r = view.getPaddingRight();
        int b = view.getPaddingBottom();
        view.setBackground(pill(view.getContext()));
        view.setPadding(l, t, r, b);
    }

    /**
     * Subscribe as glass: still the strongest of the row while not subscribed (nearly opaque, in
     * the main-action colours), see-through once subscribed.
     */
    public static void applySubscribe(MaterialButton button, boolean subscribed, int mainColor, int mainText,
                                      int quietText) {
        Context context = button.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        boolean light = ThemeMode.isLight(context);
        int fill = subscribed ? (light ? 0x8CFFFFFF : 0x1FFFFFFF) : (mainColor & 0x00FFFFFF) | 0xD9000000;
        button.setBackgroundTintList(ColorStateList.valueOf(fill));
        button.setStrokeWidth(subscribed ? Math.max(1, Math.round(density)) : 0);
        button.setStrokeColor(ColorStateList.valueOf(light ? 0x1F000000 : 0x38FFFFFF));
        button.setTextColor(subscribed ? quietText : mainText);
    }
}
