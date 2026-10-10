package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;

/**
 * NEWTUBE(error-toasts): the toasts that report a failure (a request that errored, an action that
 * could not be done), kept apart from the app's other messages so Settings > Advanced > Error
 * messages can hide them. The phone app sets {@link #setEnabled} from its preference.
 */
public final class ErrorMessages {
    private static volatile boolean sEnabled = true;

    private ErrorMessages() {
    }

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static void showMessage(Context context, String message) {
        if (sEnabled) {
            MessageHelpers.showMessage(context, message);
        }
    }

    public static void showMessage(Context context, int resId) {
        if (sEnabled) {
            MessageHelpers.showMessage(context, resId);
        }
    }

    public static void showLongMessage(Context context, String message) {
        if (sEnabled) {
            MessageHelpers.showLongMessage(context, message);
        }
    }
}
