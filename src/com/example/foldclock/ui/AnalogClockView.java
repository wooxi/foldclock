package com.example.foldclock.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.example.foldclock.Prefs;

import java.util.Calendar;

/**
 * 模拟表盘。全部自己画，没有第三方依赖，也没有一张图片资源。
 */
public class AnalogClockView extends View {

    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hourPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint minPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint secPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Calendar cal = Calendar.getInstance();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean showSeconds = true;
    private long tickMs = 1000L;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            invalidate();
            handler.postDelayed(this, tickMs);
        }
    };

    public AnalogClockView(Context c) {
        super(c);
        init();
    }

    private void init() {
        showSeconds = Prefs.showSeconds(getContext());
        tickMs = showSeconds ? 1000L : 15000L;

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        numPaint.setTextAlign(Paint.Align.CENTER);
        numPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));

        hourPaint.setStyle(Paint.Style.STROKE);
        hourPaint.setStrokeCap(Paint.Cap.ROUND);
        minPaint.setStyle(Paint.Style.STROKE);
        minPaint.setStrokeCap(Paint.Cap.ROUND);
        secPaint.setStyle(Paint.Style.STROKE);
        secPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, tickMs);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(ticker);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f * 0.94f;
        if (r <= 0) return;

        int fg = Color.parseColor("#EDEDED");
        int dim = Color.parseColor("#8A8A8A");
        int accent = Color.parseColor("#FF4D3D");

        // 刻度：60 根，整点更粗更长
        for (int i = 0; i < 60; i++) {
            boolean major = (i % 5 == 0);
            boolean quarter = (i % 15 == 0);
            tickPaint.setColor(major ? fg : dim);
            tickPaint.setStrokeWidth(quarter ? r * 0.022f : (major ? r * 0.018f : r * 0.010f));
            float len = quarter ? r * 0.10f : (major ? r * 0.08f : r * 0.05f);
            double a = Math.toRadians(i * 6 - 90);
            float sx = cx + (float) Math.cos(a) * (r * 0.98f);
            float sy = cy + (float) Math.sin(a) * (r * 0.98f);
            float ex = cx + (float) Math.cos(a) * (r * 0.98f - len);
            float ey = cy + (float) Math.sin(a) * (r * 0.98f - len);
            canvas.drawLine(sx, sy, ex, ey, tickPaint);
        }

        // 1..12
        numPaint.setColor(fg);
        numPaint.setTextSize(r * 0.22f);
        float numR = r * 0.76f;
        for (int n = 1; n <= 12; n++) {
            double a = Math.toRadians(n * 30 - 90);
            float x = cx + (float) Math.cos(a) * numR;
            float y = cy + (float) Math.sin(a) * numR;
            // 让数字视觉居中
            Paint.FontMetrics fm = numPaint.getFontMetrics();
            float off = (fm.ascent + fm.descent) / 2f;
            canvas.drawText(String.valueOf(n), x, y - off, numPaint);
        }

        cal.setTimeInMillis(System.currentTimeMillis());
        int hour = cal.get(Calendar.HOUR);
        int minute = cal.get(Calendar.MINUTE);
        int second = cal.get(Calendar.SECOND);
        int millis = cal.get(Calendar.MILLISECOND);

        float minFrac = minute + second / 60f;
        float hourFrac = (hour % 12) + minFrac / 60f;

        // 时针
        hourPaint.setColor(fg);
        hourPaint.setStrokeWidth(r * 0.055f);
        drawHand(canvas, cx, cy, hourFrac * 30f - 90f, r * 0.50f, hourPaint);
        // 分针
        minPaint.setColor(fg);
        minPaint.setStrokeWidth(r * 0.036f);
        drawHand(canvas, cx, cy, minFrac * 6f - 90f, r * 0.76f, minPaint);
        // 秒针
        if (showSeconds) {
            secPaint.setColor(accent);
            secPaint.setStrokeWidth(r * 0.014f);
            float secFrac = showSeconds ? second + millis / 1000f : second;
            drawHand(canvas, cx, cy, secFrac * 6f - 90f, r * 0.86f, secPaint);
        }

        // 中心轴
        Paint dot = secPaint;
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(showSeconds ? accent : fg);
        canvas.drawCircle(cx, cy, r * 0.028f, dot);
        dot.setStyle(Paint.Style.STROKE);
    }

    private void drawHand(Canvas canvas, float cx, float cy, float deg, float len, Paint p) {
        double a = Math.toRadians(deg);
        float ex = cx + (float) Math.cos(a) * len;
        float ey = cy + (float) Math.sin(a) * len;
        canvas.drawLine(cx, cy, ex, ey, p);
    }
}
