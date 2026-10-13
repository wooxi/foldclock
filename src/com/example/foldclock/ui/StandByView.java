package com.example.foldclock.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.foldclock.Prefs;
import com.example.foldclock.SettingsActivity;

/**
 * 整块外屏画面：左边时钟、右边日历。
 *
 * 只做横屏一套排版 —— 不做竖屏上下堆叠。宿主负责保证窗口是横的：
 *  · MainActivity 用 SCREEN_ORIENTATION_SENSOR_LANDSCAPE（系统决定左右朝向）
 *  · 悬浮窗跟随外屏旋转
 *
 * 同一套 View 被三种宿主复用：Activity、悬浮窗、DreamService。
 */
public class StandByView extends FrameLayout {

    public interface OnTapListener {
        void onTap();
    }

    private static final String TAG = "FoldClock/View";

    /** 防烧屏平移周期 */
    private static final long SHIFT_MS = 15000L;

    private final LinearLayout content;
    private final TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final boolean burnIn;
    private final boolean showStatus;
    private View clock;
    private View calendar;

    private int shiftStep;
    private int lastW = -1;
    private int lastH = -1;

    private final Runnable shifter = new Runnable() {
        @Override
        public void run() {
            applyShift();
            handler.postDelayed(this, SHIFT_MS);
        }
    };

    public StandByView(Context c, final OnTapListener tapListener) {
        this(c, tapListener, true);
    }

    /**
     * @param showSettings 右上角是否显示齿轮入口（系统屏保里不需要）
     */
    public StandByView(Context c, final OnTapListener tapListener, boolean showSettings) {
        super(c);
        burnIn = Prefs.burnIn(c);
        showStatus = Prefs.showStatusLine(c);

        setBackgroundColor(Color.BLACK);

        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER);
        addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        clock = Prefs.clockStyle(c) == Prefs.STYLE_DIGITAL
                ? new DigitalClockView(c)
                : new AnalogClockView(c);
        content.addView(clock, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        if (Prefs.showCalendar(c)) {
            calendar = new CalendarPanelView(c);
            content.addView(calendar, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }

        status = new TextView(c);
        status.setTextColor(Color.parseColor("#C08A8A8A"));
        status.setTextSize(11f);
        status.setTypeface(Typeface.MONOSPACE);
        LayoutParams slp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.BOTTOM | Gravity.START;
        slp.leftMargin = 24;
        slp.bottomMargin = 16;
        status.setLayoutParams(slp);
        status.setVisibility(showStatus ? VISIBLE : GONE);
        addView(status);

        if (showSettings) {
            addView(buildGear(c));
        }

        if (Prefs.hideOnTap(c) && tapListener != null) {
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    tapListener.onTap();
                }
            });
        }
    }

    /**
     * 右上角的设置入口。
     * 原版把齿轮贴在屏幕最边缘，正好落在外屏的挖孔/圆角区里，很难点到；
     * 这里往下往左让开一点，并给足点击热区。
     */
    private View buildGear(Context c) {
        TextView gear = new TextView(c);
        gear.setText("⚙");
        gear.setTextSize(20f);
        gear.setTextColor(Color.parseColor("#66FFFFFF"));
        gear.setGravity(Gravity.CENTER);
        int size = dp(c, 52);
        LayoutParams lp = new LayoutParams(size, size);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = dp(c, 20);
        lp.rightMargin = dp(c, 20);
        gear.setLayoutParams(lp);
        gear.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(v.getContext(), SettingsActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    v.getContext().startActivity(i);
                } catch (Throwable t) {
                    Log.w(TAG, "打开设置页失败", t);
                }
            }
        });
        return gear;
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 状态行文本，宿主（服务/Activity）可以随时更新。 */
    public void setStatus(String text) {
        if (status == null) return;
        if (!showStatus) {
            status.setVisibility(GONE);
            return;
        }
        status.setText(text == null ? "" : text);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (getWidth() != lastW || getHeight() != lastH) {
            lastW = getWidth();
            lastH = getHeight();
            Log.i(TAG, "layout " + lastW + "x" + lastH
                    + " clock=" + (clock == null ? "-" : clock.getWidth() + "x" + clock.getHeight())
                    + " calendar=" + (calendar == null ? "未创建"
                    : calendar.getWidth() + "x" + calendar.getHeight()));
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.removeCallbacks(shifter);
        if (burnIn) {
            handler.postDelayed(shifter, SHIFT_MS);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(shifter);
        super.onDetachedFromWindow();
    }

    /** 每 15 秒在 2×2 像素里挪一格。 */
    private void applyShift() {
        shiftStep = (shiftStep + 1) % 4;
        float dx = (shiftStep == 1 || shiftStep == 2) ? 1f : 0f;
        float dy = (shiftStep >= 2) ? 1f : 0f;
        content.setTranslationX(dx);
        content.setTranslationY(dy);
        if (status != null) {
            status.setTranslationX(dx);
            status.setTranslationY(dy);
        }
    }
}
