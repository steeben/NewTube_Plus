package com.newtube.mobile.ui.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * NEWTUBE(accent): a colour wheel - hue around the circle, saturation from the middle (white) out
 * to the edge - with a ring on the chosen point. Brightness is a slider beside it (AccentDialog);
 * the wheel itself is drawn at full brightness.
 */
public final class ColorWheelView extends View {
    public interface Listener {
        /** The finger moved the ring: the new hue (0-360) and saturation (0-1). */
        void onHueSaturation(float hue, float saturation);
    }

    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRingShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    @Nullable
    private Bitmap mWheel;
    private float mHue;
    private float mSaturation;
    @Nullable
    private Listener mListener;

    public ColorWheelView(Context context) {
        this(context, null);
    }

    public ColorWheelView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStrokeWidth(3f * density);
        mRingPaint.setColor(Color.WHITE);
        mRingShadowPaint.setStyle(Paint.Style.STROKE);
        mRingShadowPaint.setStrokeWidth(5f * density);
        mRingShadowPaint.setColor(0x66000000);
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    public void setHueSaturation(float hue, float saturation) {
        mHue = hue;
        mSaturation = Math.max(0f, Math.min(1f, saturation));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int size = Math.min(width, Math.round(260 * getResources().getDisplayMetrics().density));
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mWheel = null; // drawn again at the new size
    }

    private float radius() {
        float inset = 6f * getResources().getDisplayMetrics().density;
        return Math.max(1f, Math.min(getWidth(), getHeight()) / 2f - inset);
    }

    private Bitmap wheel() {
        int size = Math.max(1, Math.round(radius() * 2f));
        if (mWheel != null && mWheel.getWidth() == size) {
            return mWheel;
        }
        // Drawn once per size: every pixel's hue is its angle, its saturation its distance.
        int[] pixels = new int[size * size];
        float r = size / 2f;
        float[] hsv = new float[] {0f, 0f, 1f};
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = x + 0.5f - r;
                float dy = y + 0.5f - r;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                if (distance > r) {
                    pixels[y * size + x] = Color.TRANSPARENT;
                    continue;
                }
                hsv[0] = (float) ((Math.toDegrees(Math.atan2(dy, dx)) + 360.0) % 360.0);
                hsv[1] = distance / r;
                int color = Color.HSVToColor(hsv);
                // A pixel's worth of soft edge.
                float edge = Math.min(1f, r - distance);
                pixels[y * size + x] = (Math.round(edge * 255) << 24) | (color & 0x00FFFFFF);
            }
        }
        mWheel = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        return mWheel;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = radius();
        canvas.drawBitmap(wheel(), cx - r, cy - r, mBitmapPaint);
        double angle = Math.toRadians(mHue);
        float px = cx + (float) Math.cos(angle) * mSaturation * r;
        float py = cy + (float) Math.sin(angle) * mSaturation * r;
        float ring = 9f * getResources().getDisplayMetrics().density;
        canvas.drawCircle(px, py, ring, mRingShadowPaint);
        canvas.drawCircle(px, py, ring, mRingPaint);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - getWidth() / 2f;
                float dy = event.getY() - getHeight() / 2f;
                mHue = (float) ((Math.toDegrees(Math.atan2(dy, dx)) + 360.0) % 360.0);
                mSaturation = Math.min(1f, (float) Math.sqrt(dx * dx + dy * dy) / radius());
                invalidate();
                if (mListener != null) {
                    mListener.onHueSaturation(mHue, mSaturation);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }
}
