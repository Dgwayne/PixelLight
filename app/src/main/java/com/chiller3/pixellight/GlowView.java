/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.pixellight;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

/**
 * Large circular power button. The halo around it grows and brightens with the torch level so the
 * UI visually reflects how bright the flashlight is.
 */
public class GlowView extends View {
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    @ColorInt private int accent = Color.WHITE;
    @ColorInt private int surface = Color.rgb(0x1E, 0x1E, 0x26);
    @ColorInt private int iconOff = Color.rgb(0xC8, 0xC8, 0xD2);

    /** 0 when off, otherwise brightness fraction in (0, 1]. Animated. */
    private float level = 0f;
    private boolean on = false;
    private boolean boost = false;
    private float pulse = 0f;
    private ValueAnimator levelAnimator;
    private final ValueAnimator pulseAnimator;

    public GlowView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        ringPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);

        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(1600);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.addUpdateListener(a -> {
            pulse = (float) a.getAnimatedValue();
            invalidate();
        });

        setClickable(true);
        setFocusable(true);
    }

    public void setAccent(@ColorInt int accent) {
        this.accent = accent;
        invalidate();
    }

    public void setState(boolean on, float fraction, boolean boost) {
        final float target = on ? Math.max(0.08f, Math.min(1f, fraction)) : 0f;
        this.on = on;
        this.boost = on && boost;

        if (levelAnimator != null) {
            levelAnimator.cancel();
        }
        levelAnimator = ValueAnimator.ofFloat(level, target);
        levelAnimator.setDuration(320);
        levelAnimator.setInterpolator(new DecelerateInterpolator());
        levelAnimator.addUpdateListener(a -> {
            level = (float) a.getAnimatedValue();
            invalidate();
        });
        levelAnimator.start();

        if (this.boost && isAttachedToWindow()) {
            if (!pulseAnimator.isStarted()) {
                pulseAnimator.start();
            }
        } else {
            pulseAnimator.cancel();
            pulse = 0f;
        }

        setContentDescription(getContext().getString(on
                ? R.string.action_turn_off : R.string.action_turn_on));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (boost && !pulseAnimator.isStarted()) {
            pulseAnimator.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        pulseAnimator.cancel();
        if (levelAnimator != null) {
            levelAnimator.cancel();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int w = MeasureSpec.getSize(widthMeasureSpec);
        final int h = MeasureSpec.getSize(heightMeasureSpec);
        final int size = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? w : Math.min(w, h);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final float cx = getWidth() / 2f;
        final float cy = getHeight() / 2f;
        final float maxR = Math.min(cx, cy);
        final float discR = maxR * 0.52f;

        // Halo. Radius and intensity scale with the brightness level.
        if (level > 0f) {
            final float haloR = discR + (maxR - discR) * (0.45f + 0.55f * level)
                    + pulse * maxR * 0.03f;
            final float alpha = 0.25f + 0.6f * level;
            glowPaint.setShader(new RadialGradient(cx, cy, haloR,
                    new int[] {
                            Accents.withAlpha(accent, alpha),
                            Accents.withAlpha(accent, alpha * 0.35f),
                            Color.TRANSPARENT,
                    },
                    new float[] {discR / haloR, 0.7f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, haloR, glowPaint);
        }

        // Track ring showing the level as an arc.
        final float ringR = discR + maxR * 0.07f;
        final float ringW = maxR * 0.035f;
        ringPaint.setStrokeWidth(ringW);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setColor(Accents.withAlpha(Color.WHITE, 0.08f));
        canvas.drawCircle(cx, cy, ringR, ringPaint);
        if (level > 0f) {
            ringPaint.setColor(accent);
            arcRect.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR);
            canvas.drawArc(arcRect, -90f, 360f * level, false, ringPaint);
        }

        // Disc.
        discPaint.setColor(on ? accent : surface);
        canvas.drawCircle(cx, cy, discR, discPaint);

        // Power icon.
        final float iconR = discR * 0.36f;
        iconPaint.setStrokeWidth(discR * 0.085f);
        iconPaint.setColor(on ? Accents.onColor(accent) : iconOff);
        arcRect.set(cx - iconR, cy - iconR, cx + iconR, cy + iconR);
        canvas.drawArc(arcRect, -60f, 300f, false, iconPaint);
        canvas.drawLine(cx, cy - iconR * 1.25f, cx, cy - iconR * 0.15f, iconPaint);
    }
}
