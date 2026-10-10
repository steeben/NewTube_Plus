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

    /**
     * A watch-page card (Comments, Live chat, the playlist queue) of glass: bg_watch_card's 12dp
     * rounded surface, see-through with a fine light edge.
     */
    public static Drawable card(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        boolean light = ThemeMode.isLight(context);
        GradientDrawable glass = new GradientDrawable();
        glass.setCornerRadius(12 * density);
        glass.setColor(light ? 0x8CFFFFFF : 0x17FFFFFF);
        glass.setStroke(Math.max(1, Math.round(density)), light ? 0x1A000000 : 0x2EFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(light ? 0x1F000000 : 0x33FFFFFF), glass, null);
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
     * Subscribe as glass. Not subscribed it is still the strongest of the row: bright, nearly
     * opaque glass in the main-action colours (near-white on the dark page, near-black on the
     * light one) with a fine edge; subscribed, it is a see-through pill like the others.
     */
    public static void applySubscribe(MaterialButton button, boolean subscribed, int mainColor, int mainText,
                                      int quietText) {
        Context context = button.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        boolean light = ThemeMode.isLight(context);
        int fill = subscribed ? (light ? 0x8CFFFFFF : 0x1FFFFFFF)
                : light ? 0xD90F0F0F : 0xE6F5F5F5;
        int edge = subscribed ? (light ? 0x1F000000 : 0x38FFFFFF)
                : light ? 0x33FFFFFF : 0xFFFFFFFF;
        button.setBackgroundTintList(ColorStateList.valueOf(fill));
        button.setStrokeWidth(Math.max(1, Math.round(density)));
        button.setStrokeColor(ColorStateList.valueOf(edge));
        button.setTextColor(subscribed ? quietText : mainText);
    }
}
