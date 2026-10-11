package com.newtube.mobile.ui.common;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(logo-tint): the top bar's Play logo takes the progress bar's colour while a video plays
 * (the accent, or the video's), and the accent again once it is closed. The player tells this; the
 * logo listens and blends to it.
 */
public final class LogoTint {
    public interface Listener {
        void onLogoTint(int color);
    }

    private static int sColor;
    private static boolean sSet;
    private static final List<WeakReference<Listener>> sListeners = new ArrayList<>();

    private LogoTint() {
    }

    /** The colour now (0 when nothing set one: the accent). */
    public static int get() {
        return sSet ? sColor : 0;
    }

    public static void set(int color) {
        if (sSet && sColor == color) {
            return;
        }
        sSet = true;
        sColor = color;
        notifyListeners(color);
    }

    /** The video was closed: back to the accent. */
    public static void reset() {
        if (!sSet) {
            return;
        }
        sSet = false;
        notifyListeners(0);
    }

    public static void addListener(Listener listener) {
        sListeners.add(new WeakReference<>(listener));
    }

    private static void notifyListeners(int color) {
        for (int i = sListeners.size() - 1; i >= 0; i--) {
            Listener listener = sListeners.get(i).get();
            if (listener == null) {
                sListeners.remove(i);
            } else {
                listener.onLogoTint(color);
            }
        }
    }
}
