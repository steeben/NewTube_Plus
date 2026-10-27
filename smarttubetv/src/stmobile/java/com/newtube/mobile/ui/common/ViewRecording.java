package com.newtube.mobile.ui.common;

import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.os.Build;
import android.view.View;

/**
 * NEWTUBE(glass-stretch): copies a view, scrolled where it is, into a recording - the glass's
 * backdrop, the liquid pill's, a tab slide's picture.
 *
 * Android 12's stretch overscroll is not drawn into the canvas: a scrolling view's {@code draw()}
 * hands it to the RenderNode being recorded ({@code RecordingCanvas.mNode.stretch()}). Called
 * straight into the glass's own node, an over-scrolled You panel stretched the glass itself - the
 * blur "scrolled away" at the top - and since only a view's own node is ever cleared of a stretch,
 * it stayed broken after the panel was gone. Each copy now gets a throwaway node of its own, so
 * the stretch lands there (and the copy stretches like the view does).
 */
public final class ViewRecording {
    private ViewRecording() {
    }

    /** Draws {@code view} at the canvas's origin, offset by its own scroll as its parent would. */
    public static void draw(Canvas canvas, View view) {
        if (Build.VERSION.SDK_INT >= 31 && canvas instanceof RecordingCanvas) {
            Api31.draw((RecordingCanvas) canvas, view);
            return;
        }
        int save = canvas.save();
        canvas.translate(-view.getScrollX(), -view.getScrollY()); // draw() leaves it out
        view.draw(canvas);
        canvas.restoreToCount(save);
    }

    @androidx.annotation.RequiresApi(31)
    private static final class Api31 {
        static void draw(RecordingCanvas into, View view) {
            int width = Math.max(1, view.getWidth());
            int height = Math.max(1, view.getHeight());
            RenderNode node = new RenderNode("newtube-view-copy");
            node.setPosition(0, 0, width, height);
            node.setClipToBounds(false);
            RecordingCanvas recording = node.beginRecording(width, height);
            try {
                recording.translate(-view.getScrollX(), -view.getScrollY()); // draw() leaves it out
                view.draw(recording);
            } finally {
                node.endRecording();
            }
            into.drawRenderNode(node);
        }
    }
}
