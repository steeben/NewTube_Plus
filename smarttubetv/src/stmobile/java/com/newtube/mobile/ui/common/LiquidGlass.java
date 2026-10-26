package com.newtube.mobile.ui.common;

import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * NEWTUBE(liquid): Liquid glass, ported to views from BitChord Dial's
 * (app/.../ui/components/LiquidGlass.kt), whose rendering is Kyant0/backdrop v2.0.0
 * (https://github.com/Kyant0/backdrop, Copyright 2025 Kyant0, Apache License 2.0). The shaders
 * below are that library's, unchanged; the constants are BitChord's Apple-matched defaults.
 *
 * <p>What the glass does to what is behind it, in order: saturation up by half, a light blur,
 * then a lens that bends the picture near the rim (and splits its colours a little). Over that
 * goes a tint (light glass in the light theme, dark in the dark one) and a rim of light. Blur
 * needs Android 12 and the lens and the rim's light Android 13; before that the glass is the
 * tint and a plain rim.</p>
 */
public final class LiquidGlass {
    static final float BLUR_DP = 8f;
    /** The lens: how far in from the edge it bends, and how far it pulls (BitChord: 0.5 x 48dp). */
    static final float LENS_HEIGHT_DP = 24f;
    static final float LENS_AMOUNT_DP = 24f;
    /** The rim of light: half its stroke, which is drawn inside the shape. */
    static final float RIM_DP = 0.5f;
    private static final float SATURATION = 1.5f;
    /** The tint over the glass: 40% of a near-white or a near-black. */
    private static final int LIGHT_TINT = 0x66FAFAFA;
    private static final int DARK_TINT = 0x66121212;

    private LiquidGlass() {
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 31;
    }

    /** The tint for the theme of {@code context}. */
    public static int tint(android.content.Context context) {
        if (!isSupported()) {
            return Pills.glassTint(context); // no blur behind it: as thick as frosted glass's tint
        }
        return ThemeMode.isLight(context) ? LIGHT_TINT : DARK_TINT;
    }

    private static final String ROUNDED_RECT_SDF =
            "float radiusAt(float2 coord, float4 radii) {\n"
            + "    if (coord.x >= 0.0) {\n"
            + "        if (coord.y <= 0.0) return radii.y;\n"
            + "        else return radii.z;\n"
            + "    } else {\n"
            + "        if (coord.y <= 0.0) return radii.x;\n"
            + "        else return radii.w;\n"
            + "    }\n"
            + "}\n"
            + "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    float outside = length(max(cornerCoord, 0.0)) - radius;\n"
            + "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n"
            + "    return outside + inside;\n"
            + "}\n"
            + "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n"
            + "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n"
            + "    } else {\n"
            + "        float gradX = step(cornerCoord.y, cornerCoord.x);\n"
            + "        return sign(coord) * float2(gradX, 1.0 - gradX);\n"
            + "    }\n"
            + "}\n";

    private static final String REFRACTION_WITH_DISPERSION =
            "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float2 offset;\n"
            + "uniform float4 cornerRadii;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float depthEffect;\n"
            + "uniform float chromaticAberration;\n"
            + ROUNDED_RECT_SDF
            + "float circleMap(float x) {\n"
            + "    return 1.0 - sqrt(1.0 - x * x);\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centeredCoord = (coord + offset) - halfSize;\n"
            + "    float radius = radiusAt(coord, cornerRadii);\n"
            + "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n"
            // NEWTUBE(pills): the lens never reaches the pill's middle line. On a short pill (the
            // navigation tucked in) it did, and the edge's slope flips there: a line through it.
            + "    float lensHeight = min(refractionHeight, min(halfSize.x, halfSize.y) * 0.85);\n"
            + "    if (-sd >= lensHeight) {\n"
            + "        return content.eval(coord);\n"
            + "    }\n"
            + "    sd = min(sd, 0.0);\n"
            + "    float d = circleMap(1.0 - -sd / lensHeight) * refractionAmount;\n"
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));\n"
            + "    float2 refractedCoord = coord + d * grad;\n"
            + "    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));\n"
            + "    float2 dispersedCoord = d * grad * dispersionIntensity;\n"
            + "    half4 color = half4(0.0);\n"
            + "    half4 red = content.eval(refractedCoord + dispersedCoord);\n"
            + "    color.r += red.r / 3.5;\n"
            + "    color.a += red.a / 7.0;\n"
            + "    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));\n"
            + "    color.r += orange.r / 3.5;\n"
            + "    color.g += orange.g / 7.0;\n"
            + "    color.a += orange.a / 7.0;\n"
            + "    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));\n"
            + "    color.r += yellow.r / 3.5;\n"
            + "    color.g += yellow.g / 3.5;\n"
            + "    color.a += yellow.a / 7.0;\n"
            + "    half4 green = content.eval(refractedCoord);\n"
            + "    color.g += green.g / 3.5;\n"
            + "    color.a += green.a / 7.0;\n"
            + "    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));\n"
            + "    color.g += cyan.g / 3.5;\n"
            + "    color.b += cyan.b / 3.0;\n"
            + "    color.a += cyan.a / 7.0;\n"
            + "    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));\n"
            + "    color.b += blue.b / 3.0;\n"
            + "    color.a += blue.a / 7.0;\n"
            + "    half4 purple = content.eval(refractedCoord - dispersedCoord);\n"
            + "    color.r += purple.r / 7.0;\n"
            + "    color.b += purple.b / 3.0;\n"
            + "    color.a += purple.a / 7.0;\n"
            + "    return color;\n"
            + "}\n";

    private static final String DEFAULT_HIGHLIGHT =
            "uniform float2 size;\n"
            + "uniform float4 cornerRadii;\n"
            + "layout(color) uniform half4 color;\n"
            + "uniform float angle;\n"
            + "uniform float falloff;\n"
            + ROUNDED_RECT_SDF
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centeredCoord = coord - halfSize;\n"
            + "    float radius = radiusAt(coord, cornerRadii);\n"
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);\n"
            + "    float2 normal = float2(cos(angle), sin(angle));\n"
            + "    float d = dot(grad, normal);\n"
            + "    float intensity = pow(abs(d), falloff);\n"
            + "    return color * intensity;\n"
            + "}\n";

    /**
     * The glass's effect on a backdrop recorded {@code pad} past each edge of a pill of
     * {@code width} x {@code height}: a RenderEffect (Android 12+), typed loosely so older
     * platforms never load the class.
     */
    static Object effect(int width, int height, float pad, float blurPx, float lensHeightPx, float lensAmountPx) {
        return Api31.effect(width, height, pad, blurPx, lensHeightPx, lensAmountPx);
    }

    /**
     * Only the lens (no blur, no colour): the nav bar's lifted selection pill. Null where there is
     * no lens (before Android 13) or nothing to bend.
     */
    @Nullable
    public static Object lensOnly(int width, int height, float pad, float lensHeightPx, float lensAmountPx) {
        if (Build.VERSION.SDK_INT < 33 || lensHeightPx <= 0f || lensAmountPx <= 0f) {
            return null;
        }
        return Api33.lens(width, height, pad, lensHeightPx, lensAmountPx);
    }

    /**
     * The rim of light, inside a pill from ({@code left}, {@code top}) to ({@code right},
     * {@code bottom}): brightest where the edge faces 45 degrees, added to what is under it.
     */
    public static void drawRim(Canvas canvas, Paint paint, float left, float top, float right, float bottom,
                               float strokePx, float alpha) {
        float width = right - left;
        float height = bottom - top;
        if (width <= 0f || height <= 0f || alpha <= 0f) {
            return;
        }
        float radius = Math.min(width, height) / 2f;
        paint.setStrokeWidth(Math.min(strokePx, radius));
        boolean shaded = Build.VERSION.SDK_INT >= 33;
        if (shaded) {
            paint.setShader(Api33.highlight(width, height, radius, left, top));
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(Math.round(255 * 0.5f * alpha));
        } else {
            paint.setShader(null);
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(Math.round(255 * 0.38f * alpha));
        }
        if (Build.VERSION.SDK_INT >= 29) {
            paint.setBlendMode(BlendMode.PLUS);
        }
        Path path = new Path();
        path.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(path);
        canvas.drawPath(path, paint);
        canvas.restoreToCount(save);
    }

    @RequiresApi(31)
    private static final class Api31 {
        static Object effect(int width, int height, float pad, float blurPx, float lensHeightPx, float lensAmountPx) {
            ColorMatrix saturation = new ColorMatrix();
            saturation.setSaturation(SATURATION);
            RenderEffect color = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(saturation));
            RenderEffect blur = RenderEffect.createBlurEffect(blurPx, blurPx, color, Shader.TileMode.CLAMP);
            if (Build.VERSION.SDK_INT < 33) {
                return blur;
            }
            Object lens = Api33.lens(width, height, pad, lensHeightPx, lensAmountPx);
            return RenderEffect.createChainEffect((RenderEffect) lens, blur);
        }
    }

    @RequiresApi(33)
    private static final class Api33 {
        /**
         * Compiled once (UI thread only). A RenderEffect takes the uniforms as they are when it is
         * made, and a paint's shader as they are when it is drawn, so one of each serves every pill.
         */
        @Nullable
        private static RuntimeShader sLens;
        @Nullable
        private static RuntimeShader sHighlight;

        static Object lens(int width, int height, float pad, float lensHeightPx, float lensAmountPx) {
            if (sLens == null) {
                sLens = new RuntimeShader(REFRACTION_WITH_DISPERSION);
            }
            RuntimeShader shader = sLens;
            float radius = Math.min(width, height) / 2f;
            shader.setFloatUniform("size", width, height);
            shader.setFloatUniform("offset", -pad, -pad);
            shader.setFloatUniform("cornerRadii", radius, radius, radius, radius);
            shader.setFloatUniform("refractionHeight", lensHeightPx);
            shader.setFloatUniform("refractionAmount", -lensAmountPx);
            shader.setFloatUniform("depthEffect", 1f);
            shader.setFloatUniform("chromaticAberration", 1f);
            return RenderEffect.createRuntimeShaderEffect(shader, "content");
        }

        static Shader highlight(float width, float height, float radius, float left, float top) {
            if (sHighlight == null) {
                sHighlight = new RuntimeShader(DEFAULT_HIGHLIGHT);
            }
            RuntimeShader shader = sHighlight;
            shader.setFloatUniform("size", width, height);
            shader.setFloatUniform("cornerRadii", radius, radius, radius, radius);
            shader.setColorUniform("color", 0xFFFFFFFF);
            shader.setFloatUniform("angle", (float) Math.toRadians(45));
            shader.setFloatUniform("falloff", 1f);
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.setTranslate(left, top);
            shader.setLocalMatrix(matrix);
            return shader;
        }
    }
}
