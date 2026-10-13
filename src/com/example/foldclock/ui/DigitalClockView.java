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
import java.util.Locale;

/** 大字号数字时间。外屏上比模拟表盘更好认。 */
public class DigitalClockView extends View {

    private final Paint mainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint secPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Calendar cal = Calendar.getInstance();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean use24h = true;
    private boolean showSeconds = true;
    private long tickMs = 1000L;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            invalidate();
            handler.postDelayed(this, tickMs);
        }
    };

    public DigitalClockView(Context c) {
        super(c);
        use24h = Prefs.use24h(c);
        showSeconds = Prefs.showSeconds(c);
        tickMs = showSeconds ? 1000L : 15000L;
        mainPaint.setColor(Color.parseColor("#EDEDED"));
        mainPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        mainPaint.setTextAlign(Paint.Align.CENTER);
        secPaint.setColor(Color.parseColor("#FF4D3D"));
        secPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        secPaint.setTextAlign(Paint.Align.LEFT);
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

        cal.setTimeInMillis(System.currentTimeMillis());
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);

        String hh;
        if (use24h) {
            hh = String.format(Locale.US, "%02d", hour);
        } else {
            int h12 = hour % 12;
            if (h12 == 0) h12 = 12;
            hh = String.format(Locale.US, "%d", h12);
        }
        String mm = String.format(Locale.US, "%02d", minute);
        String main = hh + ":" + mm;
        String sec = showSeconds ? String.format(Locale.US, "%02d", cal.get(Calendar.SECOND)) : "";

        // 让主时间尽量填满：先按高度定字号，再确保不超宽
        float sizeByHeight = h * 0.62f;
        mainPaint.setTextSize(sizeByHeight);
        float wMain = mainPaint.measureText(main);
        float secW = showSeconds ? secPaint.getTextSize() * 2.2f : 0f;
        float availW = w * 0.94f - secW;
        if (wMain > availW && availW > 0) {
            sizeByHeight = sizeByHeight * availW / wMain;
            mainPaint.setTextSize(sizeByHeight);
        }

        float cx = w * 0.5f;
        Paint.FontMetrics fm = mainPaint.getFontMetrics();
        float baseline = h * 0.5f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(main, cx, baseline, mainPaint);

        if (showSeconds && sec.length() > 0) {
            secPaint.setTextSize(sizeByHeight * 0.28f);
            float x = cx + mainPaint.measureText(main) / 2f + sizeByHeight * 0.06f;
            float secBaseline = baseline - (fm.ascent + fm.descent) / 2f
                    + (fm.ascent + fm.descent) / 2f * 0.35f;
            canvas.drawText(sec, x, secBaseline, secPaint);
        }
    }
}
