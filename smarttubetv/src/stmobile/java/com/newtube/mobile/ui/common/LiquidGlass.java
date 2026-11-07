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
 * NEWTUBE(liquid): Liquid glass, in the style of nordify/css-liquid-glass
 * (https://github.com/nordify/css-liquid-glass), the look BitChord Dial uses. That library builds
 * the glass from SVG filters over a web page; here the same pieces are one AGSL shader over the
 * backdrop, recorded {@code pad} past each edge so the rim can mirror what is next to the glass.
 *
 * <p>What the glass does to what is behind it, in its order: a light blur and a touch of
 * saturation; refraction that leaves the middle 1:1 and folds the picture in a band along the rim
 * (lines turn back on themselves), with a lip that pulls the inside out into a bright contour; a
 * reflection of the surroundings in that band, weighted like Fresnel; the folding band a little
 * brighter and more saturated; a thin edge that is lit (brighter, very saturated) towards the top
 * right and bottom left and shaded elsewhere; a faint white veil. An additive white highlight on the
 * lit edge goes on last ({@link #drawRim}), over the tint. Blur needs Android 12, the rest Android
 * 13; before that the glass is the tint and a plain rim.</p>
 *
 * <p>Not ported: the soft shadow under the glass (it would have to be drawn outside the view) and
 * the caustic (bright light gathered under the lens, a light-background effect).</p>
 */
public final class LiquidGlass {
    /**
     * css-liquid-glass: blur(2.5px) saturate(1.1). A RenderEffect's blur radius becomes a sigma of
     * 0.577 x radius + 0.5, so 4dp gives the css blur's 2.5.
     */
    static final float BLUR_DP = 4f;
    /** How far past each edge the backdrop is recorded: what the rim's reflection can reach. */
    static final float PAD_DP = 40f;
    /** The additive highlight's stroke, drawn inside the shape (so half of it shows). */
    static final float RIM_DP = 1.4f;
    private static final float SATURATION = 1.1f;
    /** The tint over the glass: kept thin, the css glass has none (only its veil, in the shader). */
    private static final int LIGHT_TINT = 0x29FAFAFA;
    private static final int DARK_TINT = 0x33121212;

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

    // The refraction profile, as in css-liquid-glass's maps.ts (refrProfile): how far the glass
    // samples inwards at q (distance from the arc centre over the radius), in radii.
    private static final float REFR_BAND = 0.39f;
    private static final float REFR_WIDTH = 1.1f;
    private static final float REFR_TILT = 0.2f;

    private static float refrProfile(float q) {
        float s = 1f - (1f - q) / REFR_WIDTH;
        float u = Math.max(0f, Math.min(1f, (s - (1f - REFR_BAND)) / REFR_BAND));
        float v = Math.max(0f, Math.min(1f, (s - 0.7f) / 0.3f));
        float d = (0.5f * u * u * (3f - 2f * u) + 5f * (float) Math.pow(v, 5)) * REFR_WIDTH;
        if (q <= 1e-6f || d <= 0f) {
            return 0f;
        }
        float cap = q * 1.5f;
        float x = d / cap;
        return cap * x / (float) Math.pow(1f + x * x * x * x, 0.25);
    }

    /** The profile's largest pull (REFR_MAG_PEAK): where the tilt fades in is measured against it. */
    private static final float REFR_MAG_PEAK;

    static {
        float m = 1e-6f;
        for (int i = 0; i <= 1000; i++) {
            m = Math.max(m, refrProfile(i / 1000f));
        }
        REFR_MAG_PEAK = m;
    }

    private static final String GLASS =
            "uniform shader content;\n"
            + "uniform float2 size;\n"      // the shape, px
            + "uniform float2 pad;\n"       // where it starts in the recording
            + "uniform float2 area;\n"      // the recording's size
            + "uniform float dp;\n"
            + "uniform float dark;\n"       // 0 light theme, 1 dark
            + "uniform float refraction;\n" // 0-1: how much of the bend
            + "uniform float zoom;\n"       // 1 = none; the nav bar's finger bubble magnifies
            + "uniform float material;\n"   // 0-1: reflection, edge and veil
            + "uniform float magPeak;\n"
            + "float refrProfile(float q) {\n"
            + "    float s = 1.0 - (1.0 - q) / 1.1;\n"
            + "    float u = clamp((s - 0.61) / 0.39, 0.0, 1.0);\n"
            + "    float v = clamp((s - 0.7) / 0.3, 0.0, 1.0);\n"
            + "    float d = (0.5 * u * u * (3.0 - 2.0 * u) + 5.0 * pow(v, 5.0)) * 1.1;\n"
            + "    if (q <= 1e-6 || d <= 0.0) return 0.0;\n"
            + "    float cap = q * 1.5;\n"
            + "    float x = d / cap;\n"
            + "    float x2 = x * x;\n"
            + "    return cap * x / pow(1.0 + x2 * x2, 0.25);\n"
            + "}\n"
            // CSS brightness(b) then saturate(s).
            + "float3 expose(float3 c, float b, float s) {\n"
            + "    float3 x = c * b;\n"
            + "    float l = dot(x, float3(0.213, 0.715, 0.072));\n"
            + "    return clamp(mix(float3(l), x, s), 0.0, 1.0);\n"
            + "}\n"
            + "float4 glassAt(float2 p) {\n"
            + "    return float4(content.eval(clamp(p + pad, float2(0.5), area - float2(0.5))));\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 p = coord - pad;\n"
            + "    float shortSide = min(size.x, size.y);\n"
            + "    float R = shortSide * 0.5;\n"
            + "    float2 inner = clamp(p, float2(R), size - float2(R));\n"
            + "    float2 v = p - inner;\n"
            + "    float dist = length(v);\n"
            + "    if (dist > R) return content.eval(coord);\n"
            + "    float D = R - dist;\n" // depth in from the edge
            + "    float2 n = dist > 1e-4 ? v / dist : float2(0.0);\n"
            + "    float2 c = size * 0.5;\n"
            // Refraction: sample inwards by the profile, plus a pull along the long axis so a
            // straight run bends too.
            + "    float P = refrProfile(min(1.0, dist / R)) * R;\n"
            + "    float run = size.x - 2.0 * R;\n"
            + "    float straight = clamp((size.x * 0.5 - R) / R, 0.0, 1.0);\n"
            + "    float side = run > 1e-4 ? clamp(1.0 - 2.0 * (p.x - R) / run, -1.0, 1.0) : sign(c.x - p.x);\n"
            + "    float g = clamp((P / (R * magPeak) - 0.2) / 0.4, 0.0, 1.0);\n"
            + "    float2 disp = -P * n + float2(0.2 * straight * side * P * g * g * (3.0 - 2.0 * g), 0.0);\n"
            + "    float2 at = c + (p - c) / zoom + disp * refraction;\n"
            + "    float4 col = glassAt(at);\n"
            // Reflection: the light that never enters the glass mirrors what is beside it into
            // the band inside the rim (not the outermost ring, which the edge owns).
            + "    float rimW = 2.0 * dp;\n"
            + "    float band = min(max(14.3 * dp, shortSide * 0.22), shortSide * 0.45);\n"
            + "    float ring = min(band * 2.0, R - dp);\n"
            + "    float fadeEnd = min(ring, rimW + shortSide * 0.22);\n"
            + "    float reach = min(min(2.6 * shortSide, 160.0 * dp), min(pad.x, pad.y));\n"
            + "    if (material > 0.0 && reach > dp && D > rimW && D < fadeEnd && dist > 1e-4) {\n"
            + "        float u = clamp(D / ring, 0.0, 1.0);\n"
            + "        float outw = atan(u * 3.6021) / 1.3;\n" // tan(1.3): a curved mirror
            + "        float4 refl = glassAt(p + n * reach * outw);\n"
            + "        float t = clamp((D - rimW) / (fadeEnd - rimW), 0.0, 1.0);\n"
            + "        float innerW = 0.35 * (1.0 - t * t * t * (t * (t * 6.0 - 15.0) + 10.0));\n"
            + "        float f = clamp(1.0 - D / band, 0.0, 1.0);\n"
            + "        float fres = 0.3 + 0.7 * f * f * f;\n"
            + "        col = mix(col, refl, min(1.0, fres * 2.5 * innerW) * material);\n"
            + "    }\n"
            // The edge: the folding band brightened, then a thin ring lit at 45 and 225 degrees
            // (the angle warped by the shape's aspect) and shaded between.
            + "    float s = shortSide / (60.0 * dp);\n"
            + "    float ringM = clamp((2.0 * dp * s - D) / (1.3 * dp * s), 0.0, 1.0);\n"
            + "    float foldM = clamp(1.0 - D / (REFR_FOLD * R), 0.0, 1.0);\n"
            + "    float2 q = p - c;\n"
            + "    float theta = atan(q.x, -q.y);\n"
            + "    float ax = pow(max(1.0, size.x * 0.5), -0.4);\n"
            + "    float by = pow(max(1.0, size.y * 0.5), -0.4);\n"
            + "    float na = atan(sin(theta) * ax, cos(theta) * by);\n"
            + "    float glint = 0.5 + 0.5 * cos(2.0 * (na - 0.785398));\n"
            + "    float3 rgb = col.rgb;\n"
            + "    rgb = mix(rgb, expose(rgb, 1.08, 1.3), foldM * material);\n"
            + "    rgb = mix(rgb, expose(rgb, 1.35 + 0.1 * dark, 3.1 - 1.1 * dark), ringM * glint * material);\n"
            + "    rgb = mix(rgb, expose(rgb, 0.84 + 0.41 * dark, 2.2 - 0.9 * dark), ringM * (1.0 - glint) * material);\n"
            + "    rgb = mix(rgb, float3(1.0), (0.14 - 0.11 * dark) * material);\n"
            + "    return half4(half3(rgb), half(col.a));\n"
            + "}\n";

    private static final String GLASS_SRC = GLASS.replace("REFR_FOLD", Float.toString(REFR_BAND * REFR_WIDTH));

    /**
     * The additive highlight on the lit edge: white where the edge faces 45 (and 225) degrees,
     * falling off as cos squared - css-liquid-glass's .lg-spec.
     */
    private static final String HIGHLIGHT =
            "uniform float2 size;\n"
            + "layout(color) uniform half4 color;\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 c = size * 0.5;\n"
            + "    float2 q = coord - c;\n"
            + "    float theta = atan(q.x, -q.y);\n"
            + "    float ax = pow(max(1.0, size.x * 0.5), -0.4);\n"
            + "    float by = pow(max(1.0, size.y * 0.5), -0.4);\n"
            + "    float na = atan(sin(theta) * ax, cos(theta) * by);\n"
            + "    return color * (0.5 + 0.5 * cos(2.0 * (na - 0.785398)));\n"
            + "}\n";

    /**
     * The glass's effect on a backdrop recorded {@code pad} past each edge of a pill of
     * {@code width} x {@code height}: a RenderEffect (Android 12+), typed loosely so older
     * platforms never load the class.
     */
    static Object effect(int width, int height, float pad, float blurPx, float density, boolean dark) {
        return Api31.effect(width, height, pad, blurPx, density, dark);
    }

    /**
     * Only the glass (no blur, no colour), for the nav bar's lifted selection pill:
     * {@code presence} is how much of it there is (0-1) and {@code zoom} how much the middle
     * magnifies (1 = none). Null before Android 13 or when there is nothing to do.
     */
    @Nullable
    public static Object lensOnly(int width, int height, float pad, float presence, float zoom, float density,
                                  boolean dark) {
        if (Build.VERSION.SDK_INT < 33 || (presence <= 0f && zoom <= 1f)) {
            return null;
        }
        return Api33.glass(width, height, pad, presence, Math.max(1f, zoom), density, dark);
    }

    /**
     * The highlight on the lit edge, inside a pill from ({@code left}, {@code top}) to
     * ({@code right}, {@code bottom}), added to what is under it.
     */
    public static void drawRim(Canvas canvas, Paint paint, float left, float top, float right, float bottom,
                               float strokePx, float alpha, boolean dark) {
        float width = right - left;
        float height = bottom - top;
        if (width <= 0f || height <= 0f || alpha <= 0f) {
            return;
        }
        float radius = Math.min(width, height) / 2f;
        paint.setStrokeWidth(Math.min(strokePx, radius));
        boolean shaded = Build.VERSION.SDK_INT >= 33;
        if (shaded) {
            paint.setShader(Api33.highlight(width, height, left, top));
            paint.setColor(0xFFFFFFFF);
            // css: rgb(255 255 255 / 0.13), 0.38 over dark backgrounds.
            paint.setAlpha(Math.round(255 * (dark ? 0.38f : 0.13f) * alpha));
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
        static Object effect(int width, int height, float pad, float blurPx, float density, boolean dark) {
            ColorMatrix saturation = new ColorMatrix();
            saturation.setSaturation(SATURATION);
            RenderEffect color = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(saturation));
            RenderEffect blur = RenderEffect.createBlurEffect(blurPx, blurPx, color, Shader.TileMode.CLAMP);
            if (Build.VERSION.SDK_INT < 33) {
                return blur;
            }
            Object glass = Api33.glass(width, height, pad, 1f, 1f, density, dark);
            return RenderEffect.createChainEffect((RenderEffect) glass, blur);
        }
    }

    @RequiresApi(33)
    private static final class Api33 {
        /**
         * Compiled once (UI thread only). A RenderEffect takes the uniforms as they are when it is
         * made, and a paint's shader as they are when it is drawn, so one of each serves every pill.
         */
        @Nullable
        private static RuntimeShader sGlass;
        @Nullable
        private static RuntimeShader sHighlight;

        static Object glass(int width, int height, float pad, float presence, float zoom, float density,
                            boolean dark) {
            if (sGlass == null) {
                sGlass = new RuntimeShader(GLASS_SRC);
            }
            RuntimeShader shader = sGlass;
            int p = Math.round(pad);
            shader.setFloatUniform("size", width, height);
            shader.setFloatUniform("pad", p, p);
            shader.setFloatUniform("area", width + 2 * p, height + 2 * p);
            shader.setFloatUniform("dp", density);
            shader.setFloatUniform("dark", dark ? 1f : 0f);
            shader.setFloatUniform("refraction", Math.max(0f, Math.min(1f, presence)));
            shader.setFloatUniform("zoom", zoom);
            shader.setFloatUniform("material", Math.max(0f, Math.min(1f, presence)));
            shader.setFloatUniform("magPeak", REFR_MAG_PEAK);
            return RenderEffect.createRuntimeShaderEffect(shader, "content");
        }

        static Shader highlight(float width, float height, float left, float top) {
            if (sHighlight == null) {
                sHighlight = new RuntimeShader(HIGHLIGHT);
            }
            RuntimeShader shader = sHighlight;
            shader.setFloatUniform("size", width, height);
            shader.setColorUniform("color", 0xFFFFFFFF);
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.setTranslate(left, top);
            shader.setLocalMatrix(matrix);
            return shader;
        }
    }
}
