package com.example.foldclock.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.example.foldclock.Lunar;
import com.example.foldclock.Prefs;

import java.util.Calendar;

/**
 * 右半边（竖屏时是下半边）：公历月视图 + 农历月日。
 *
 * 排版取向参考 v1.3.7 与息屏类时钟的克制感：整块内容**垂直居中**、四周留足黑边，
 * 不铺满面板。字号不按面板高度直接取，而是取「按列宽」和「按高度预算」里更小的那个，
 * 这样横屏那种又窄又高的面板也不会被撑满。
 */
public class CalendarPanelView extends View {

    private static final String[] WEEK = {"一", "二", "三", "四", "五", "六", "日"};

    /**
     * 日历内容块占面板高度的比例 —— 其余留成黑边。用户要的 60%。
     * 排版固定为横屏（时钟左、日历右），所以这块面板永远是「又窄又高」的那一半，
     * 一个值就够了。
     */
    private static final float HEIGHT_BUDGET = 0.60f;
    /** 四周基础留白（相对面板短边） */
    private static final float PAD_RATIO = 0.070f;
    /** 字号相对列宽的比例（旧版数字约占列宽 72%，两位数的「28」也放得下） */
    private static final float WIDTH_FACTOR = 0.66f;

    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lunarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint weekPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint otherPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint todayBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Calendar cal = Calendar.getInstance();
    private final Calendar work = Calendar.getInstance();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean showLunar = true;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            invalidate();
            handler.postDelayed(this, 60000L);
        }
    };

    public CalendarPanelView(Context c) {
        super(c);
        showLunar = Prefs.showLunar(c);

        titlePaint.setColor(Color.parseColor("#FF4D3D"));
        titlePaint.setTextAlign(Paint.Align.LEFT);
        titlePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));

        lunarPaint.setColor(Color.parseColor("#8A8A8A"));
        lunarPaint.setTextAlign(Paint.Align.LEFT);

        weekPaint.setColor(Color.parseColor("#8A8A8A"));
        weekPaint.setTextAlign(Paint.Align.CENTER);

        dayPaint.setColor(Color.parseColor("#EDEDED"));
        dayPaint.setTextAlign(Paint.Align.CENTER);

        otherPaint.setColor(Color.parseColor("#3A3A3A"));
        otherPaint.setTextAlign(Paint.Align.CENTER);

        todayBgPaint.setColor(Color.parseColor("#FF4D3D"));
        todayBgPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 60000L);
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

        long now = System.currentTimeMillis();
        cal.setTimeInMillis(now);
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH);
        int today = cal.get(Calendar.DAY_OF_MONTH);

        // 这个月实际需要几行（5 或 6）。
        // 以前固定画 6 行，遇到只需 5 行的月份，最后一行整行都是下月日期，
        // 渲染成暗灰，视觉上就是底部一大块空。
        work.set(year, month, 1);
        int firstDow = work.get(Calendar.DAY_OF_WEEK); // 周日 = 1
        int lead = (firstDow + 5) % 7;                 // 一周从周一开始
        int daysInMonth = work.getActualMaximum(Calendar.DAY_OF_MONTH);
        int rows = (lead + daysInMonth + 6) / 7;

        float pad = Math.min(w, h) * PAD_RATIO;
        float colW = (w - pad * 2f) / 7f;

        // 基础字号：按列宽 与 按高度预算 取小。
        // 内容块高 ≈ (3.9 + 1.5*rows) * daySize —— 系数来自下面各行的间距设定。
        // 高度预算是主导：横竖屏都让内容块约占面板高度的 HEIGHT_BUDGET，其余留成黑边。
        float daySize = Math.max(8f, Math.min(
                colW * WIDTH_FACTOR,
                (h * HEIGHT_BUDGET) / (3.9f + 1.5f * rows)));

        float titleSize = daySize * 1.35f;
        float weekSize = daySize * 0.85f;
        float gap1 = weekSize * 1.10f;   // 标题 → 星期表头
        float gap2 = weekSize * 0.90f;   // 星期表头 → 日期网格
        float rowGap = daySize * 1.50f;

        float blockH = titleSize + gap1 + weekSize + gap2 + rows * rowGap;
        // 垂直居中：上下黑边对称
        float top = Math.max(pad * 0.5f, (h - blockH) / 2f);

        // ── 标题：10月 九月初四 ──
        titlePaint.setTextSize(titleSize);
        lunarPaint.setTextSize(titleSize * 0.92f);
        float titleBaseline = top + titleSize * 0.82f;
        String monthStr = (month + 1) + "月";
        canvas.drawText(monthStr, pad, titleBaseline, titlePaint);
        if (showLunar) {
            String l = Lunar.monthDay(now);
            if (l.length() > 0) {
                float x = pad + titlePaint.measureText(monthStr) + titleSize * 0.30f;
                canvas.drawText(l, x, titleBaseline, lunarPaint);
            }
        }

        // ── 星期表头：一 二 三 四 五 六 日 ──
        weekPaint.setTextSize(weekSize);
        float weekBaseline = top + titleSize + gap1 + weekSize * 0.82f;
        for (int i = 0; i < 7; i++) {
            canvas.drawText(WEEK[i], pad + colW * (i + 0.5f), weekBaseline, weekPaint);
        }

        // ── 日期网格 ──
        float gridTop = top + titleSize + gap1 + weekSize + gap2;
        dayPaint.setTextSize(daySize);
        otherPaint.setTextSize(daySize);
        Paint.FontMetrics fm = dayPaint.getFontMetrics();
        float textOff = (fm.ascent + fm.descent) / 2f;
        float todayR = daySize * 0.78f;

        work.set(year, month, 1);
        work.add(Calendar.DAY_OF_MONTH, -lead);

        for (int row = 0; row < rows; row++) {
            float cy = gridTop + rowGap * (row + 0.5f);
            for (int col = 0; col < 7; col++) {
                int d = work.get(Calendar.DAY_OF_MONTH);
                boolean inMonth = work.get(Calendar.MONTH) == month;
                float cx = pad + colW * (col + 0.5f);
                boolean isToday = inMonth && d == today;
                if (isToday) {
                    canvas.drawCircle(cx, cy, todayR, todayBgPaint);
                }
                Paint p = (inMonth || isToday) ? dayPaint : otherPaint;
                p.setColor(isToday ? Color.parseColor("#FF101010")
                        : (inMonth ? Color.parseColor("#EDEDED") : Color.parseColor("#3A3A3A")));
                canvas.drawText(String.valueOf(d), cx, cy - textOff, p);
                work.add(Calendar.DAY_OF_MONTH, 1);
            }
        }
    }
}
