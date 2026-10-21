package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * A translucent glass slab: soft sheen fill, bright top specular, crisp rim and a faint outer glow.
 * One instance floats over the grid and is moved with spring animations between tiles.
 */
public class GlassView extends View {

    public static final float INSET_DP = 8f;

    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint darkRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private float radius;
    /** Glow colour as 0xRRGGBB: white normally, a festival's accent (diya gold) while a festive skin is on. */
    private int glowRgb = 0xFFFFFF;
    /** Glow strength: the white glow stays faint, a coloured one needs more alpha to read as warm light. */
    private int glowAlpha = 0x30;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;

        shadowPaint.setStyle(Paint.Style.FILL);

        // Contrast-aware dual rim: a wider, low-alpha dark outline behind the bright rim ensures
        // the focus border is clearly legible on pure white/light cards (YouTube, LocalSend, AirScreen).
        darkRim.setStyle(Paint.Style.STROKE);
        darkRim.setStrokeWidth(3f * density);
        darkRim.setColor(0x38000000);

        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * density);
        rim.setColor(0xCCFFFFFF);
        glow.setStyle(Paint.Style.STROKE);
        // Cache the slab as a texture. The spring only changes translation, so the GPU can
        // move it without replaying clipPath and the glow on every frame. That path is
        // software-rendered on the MT5895 and is what makes the highlight crawl.
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** Tints the rim and the outer glow; 0 restores the plain white glass. */
    void setAccent(int argb) {
        if (argb == 0) {
            glowRgb = 0xFFFFFF;
            glowAlpha = 0x30;
            rim.setColor(0xCCFFFFFF);
        } else {
            glowRgb = argb & 0xFFFFFF;
            glowAlpha = 0x70;
            // The rim is the accent lifted towards white, so the edge still reads as glass, not paint.
            int r = (((glowRgb >> 16) & 0xFF) + 255 * 2) / 3, g = (((glowRgb >> 8) & 0xFF) + 255 * 2) / 3,
                    b = ((glowRgb & 0xFF) + 255 * 2) / 3;
            rim.setColor(0xE6000000 | (r << 16) | (g << 8) | b);
        }
        invalidate();
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fill.setShader(new LinearGradient(0, 0, 0, h, 0x3DFFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP));
        specular.setShader(new LinearGradient(0, 0, 0, h * 0.45f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = INSET_DP * density;   // room for the shadow and diffusion
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        // 1. Directional grounding drop shadow (clean, subtle elevation below the card)
        // Multi-tier GPU-safe rounded rect fills with low alpha
        float dy1 = 2f * density, s1 = 2f * density;
        shadowPaint.setColor(0x14000000);
        canvas.drawRoundRect(rect.left - s1, rect.top - s1 + dy1, rect.right + s1, rect.bottom + s1 + dy1,
                radius + s1, radius + s1, shadowPaint);

        float dy2 = 3.5f * density, sx2 = 1f * density, sy2 = 2f * density;
        shadowPaint.setColor(0x1C000000);
        canvas.drawRoundRect(rect.left - sx2, rect.top - sy2 + dy2, rect.right + sx2, rect.bottom + sy2 + dy2,
                radius + sx2, radius + sx2, shadowPaint);

        float dy3 = 4.5f * density, sy3 = 1.5f * density;
        shadowPaint.setColor(0x16000000);
        canvas.drawRoundRect(rect.left, rect.top + dy3, rect.right, rect.bottom + sy3 + dy3,
                radius, radius, shadowPaint);

        // 2. Tight focus diffusion (restrained outer specular softening, no muddy pillow)
        float o1 = 0.75f * density;
        glow.setStrokeWidth(1.5f * density);
        glow.setColor((Math.round(glowAlpha * 0.50f) << 24) | glowRgb);
        canvas.drawRoundRect(rect.left - o1, rect.top - o1, rect.right + o1, rect.bottom + o1,
                radius + o1, radius + o1, glow);

        float o2 = 1.75f * density;
        glow.setStrokeWidth(1.5f * density);
        glow.setColor((Math.round(glowAlpha * 0.18f) << 24) | glowRgb);
        canvas.drawRoundRect(rect.left - o2, rect.top - o2, rect.right + o2, rect.bottom + o2,
                radius + o2, radius + o2, glow);

        // 3. Translucent glass fill
        canvas.drawRoundRect(rect, radius, radius, fill);

        // 4. Sheen across the top 45%: clipRect stays on the GPU (clipPath did not on the MT5895) and
        // the round rect under it keeps the top corners rounded with a flat bottom edge.
        canvas.save();
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.45f);
        canvas.drawRoundRect(rect, radius, radius, specular);
        canvas.restore();

        // 5. Dual-rim: dark contrast outline behind bright white/accent rim for visibility on white cards
        canvas.drawRoundRect(rect, radius, radius, darkRim);
        canvas.drawRoundRect(rect, radius, radius, rim);
    }
}
