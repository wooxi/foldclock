package com.example.foldclock;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 外屏悬浮窗。
 *
 * 这是「充电自动显示」最可靠的一条路：往屏幕上加一个 TYPE_APPLICATION_OVERLAY 窗口
 * 不属于「后台启动 Activity」，所以完全绕开 Android 10+ 的后台启动限制，
 * 也不受 HyperOS「后台弹出界面」开关的约束（只要悬浮窗权限给了）。
 *
 * 关键点：折叠时主屏是灭的，必须把窗口加到「当前亮着的那块屏」上，
 * 也就是用 DisplayManager 挑一块 STATE_ON 且非私有的显示器，再 createDisplayContext。
 */
public final class OverlayClock {

    private static final String TAG = "FoldClock/Overlay";

    private final Context app;
    private WindowManager wm;
    private View view;
    private int displayId = -1;

    public OverlayClock(Context c) {
        this.app = c.getApplicationContext();
    }

    public boolean canShow() {
        return Settings.canDrawOverlays(app);
    }

    public boolean isShowing() {
        return view != null;
    }

    public int shownDisplayId() {
        return displayId;
    }

    /**
     * @param brightnessPercent 1..100；<=0 或 >=100 表示不改亮度
     * @return 是否成功挂上
     */
    public boolean show(View content, int brightnessPercent, boolean keepScreenOn) {
        if (view != null) {
            return true;
        }
        if (content == null) {
            return false;
        }
        if (!canShow()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted");
            return false;
        }

        Display target = pickDisplay();
        Context ctx = target == null ? app : app.createDisplayContext(target);
        WindowManager w = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (w == null) {
            w = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        }
        if (w == null) {
            return false;
        }

        WindowManager.LayoutParams lp = buildParams(brightnessPercent, keepScreenOn);
        WindowManager used = w;
        try {
            used.addView(content, lp);
        } catch (Throwable t) {
            Log.w(TAG, "addView on display " + (target == null ? "default" : target.getDisplayId())
                    + " failed, retry on default", t);
            WindowManager def = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (def == null || def == w) {
                return false;
            }
            try {
                def.addView(content, lp);
                used = def;
                target = null;
            } catch (Throwable t2) {
                Log.w(TAG, "fallback addView failed", t2);
                return false;
            }
        }

        this.wm = used;
        this.view = content;
        this.displayId = target == null ? 0 : target.getDisplayId();
        Log.i(TAG, "overlay shown on display " + displayId);
        return true;
    }

    public void hide() {
        if (view == null) {
            wm = null;
            displayId = -1;
            return;
        }
        try {
            if (wm != null) {
                wm.removeViewImmediate(view);
            }
        } catch (Throwable t) {
            Log.w(TAG, "removeView failed", t);
        }
        view = null;
        wm = null;
        displayId = -1;
    }

    private WindowManager.LayoutParams buildParams(int brightnessPercent, boolean keepScreenOn) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        if (keepScreenOn) {
            flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } catch (Throwable ignored) {
            // 老系统没有这个字段
        }
        // ★ 关键：不参与任何 inset 计算，窗口必须铺满整块屏幕。
        // 否则系统会把状态栏/导航栏的高度从窗口尺寸里扣掉（外屏 1712 高只给到 1657），
        // 底下那一条就盖不住，直接露出系统桌面。
        try {
            lp.setFitInsetsTypes(0);
            lp.setFitInsetsSides(0);
        } catch (Throwable ignored) {
            // API 30 以下没有；本工程 minSdk 32，正常不会走到这里
        }
        if (brightnessPercent > 0 && brightnessPercent < 100) {
            lp.screenBrightness = brightnessPercent / 100f;
        }
        return lp;
    }

    /**
     * 当前亮着的那块屏是不是横的。
     *
     * 本 App 只做横屏一套排版：外屏立着（竖屏）的时候**什么都不显示**，
     * 只有横过来才亮时钟 —— 所以服务在决定显示之前要先问这一句。
     *
     * 注意不能用 Display.getWidth()/getHeight()：那两个返回的是面板的**原始**尺寸，
     * 不随旋转变化，永远会把外屏判成竖的。要用 getSize()，它按当前旋转给尺寸。
     */
    public boolean isDisplayLandscape() {
        Display d = pickDisplay();
        if (d == null) return false;
        try {
            Point p = new Point();
            d.getSize(p);
            Log.i(TAG, "display " + d.getDisplayId() + " size=" + p.x + "x" + p.y
                    + " rotation=" + d.getRotation() + " -> "
                    + (p.x > p.y ? "横" : "竖"));
            return p.x > p.y;
        } catch (Throwable t) {
            Log.w(TAG, "getSize failed", t);
            return false;
        }
    }

    /** 挑当前亮着的、非私有的显示器；多个时取 id 最小的那个。 */
    private Display pickDisplay() {        DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            return null;
        }
        Display[] all;
        try {
            all = dm.getDisplays();
        } catch (Throwable t) {
            return null;
        }
        if (all == null) {
            return null;
        }
        Display best = null;
        for (Display d : all) {
            if (d == null) continue;
            int state;
            try {
                state = d.getState();
            } catch (Throwable t) {
                continue;
            }
            if (state != Display.STATE_ON) continue;
            try {
                if ((d.getFlags() & Display.FLAG_PRIVATE) != 0) continue;
            } catch (Throwable ignored) {
                // getFlags 拿不到就不筛
            }
            if (best == null || d.getDisplayId() < best.getDisplayId()) {
                best = d;
            }
        }
        return best;
    }

    /** 给自检页看的：当前有几块屏、都是什么状态。 */
    public static String describeDisplays(Context c) {
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return "n/a";
        Display[] all = dm.getDisplays();
        if (all == null) return "n/a";
        StringBuilder sb = new StringBuilder();
        for (Display d : all) {
            if (d == null) continue;
            if (sb.length() > 0) sb.append(", ");
            String state;
            switch (d.getState()) {
                case Display.STATE_ON:
                    state = "ON";
                    break;
                case Display.STATE_OFF:
                    state = "off";
                    break;
                case Display.STATE_DOZE:
                    state = "doze";
                    break;
                case Display.STATE_DOZE_SUSPEND:
                    state = "doze-suspend";
                    break;
                case Display.STATE_UNKNOWN:
                    state = "unknown";
                    break;
                default:
                    state = String.valueOf(d.getState());
                    break;
            }
            sb.append(d.getDisplayId()).append(':').append(state);
        }
        return sb.toString();
    }
}
