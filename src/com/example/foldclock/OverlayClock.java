package com.example.foldclock;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.example.foldclock.ui.StandByView;

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

    /** 出现/退出动画时长（跟 StandByView 里的常量保持一致） */
    private static final long ENTER_MS = StandByView.ENTER_MS;
    private static final long EXIT_MS = StandByView.EXIT_MS;
    /**
     * 压暗的节奏，参考 iPhone 息屏时钟：
     * 插电后先用「当前亮度」把时钟淡进来（这段时间亮度不动），
     * 过半秒再花近 2 秒慢慢沉到设定亮度 —— 而不是 300ms 内唰地变暗。
     */
    private static final long DIM_DELAY_MS = 500L;
    private static final long DIM_MS = 1800L;
    /** 收尾兜底：动画回调万一不来（被系统打断），也要在这个时间之后把窗口摘掉 */
    private static final long EXIT_GUARD_MS = EXIT_MS + 150L;

    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View view;
    /** 当前窗口参数（改亮度要复用它去 updateViewLayout） */
    private WindowManager.LayoutParams params;
    private int displayId = -1;
    /** addView 之后回读的"真的上屏了吗"（addView 不抛异常 ≠ 用户能看见） */
    private boolean verified = false;
    private int verifiedDisplayId = -1;
    /** 退出动画排队中的「摘窗口」任务 */
    private Runnable pendingRemove;
    /** 排队中的「开始压暗」任务 */
    private Runnable pendingDim;
    /** 正在跑的亮度动画（换向时要先掐掉，否则两个动画会打架） */
    private ValueAnimator brightnessAnim;
    /** 挂窗口前的屏幕亮度，退出时升回去，避免在 6% 亮度下淡出 */
    private float restoreBrightness = -1f;

    public OverlayClock(Context c) {
        this.app = c.getApplicationContext();
    }

    public boolean canShow() {
        return Settings.canDrawOverlays(app);
    }

    public boolean isShowing() {
        return view != null;
    }

    /** 窗口是不是真的挂在窗口树上：view != null 不代表系统没把它摘掉。 */
    public boolean isAttached() {
        return view != null && view.isAttachedToWindow();
    }

    public int shownDisplayId() {
        return displayId;
    }

    /**
     * @param brightnessPercent 1..100；<=0 或 >=100 表示不改亮度
     * @return 是否成功挂上
     */
    public boolean show(View content, int brightnessPercent, boolean keepScreenOn) {
        // 上一次的退出动画还在播：立刻摘掉，重新挂一块新的（否则会 return true 却什么都没显示）
        if (pendingRemove != null) {
            View old = view;
            cancelPendingRemove();
            removeNow(old);
        }
        if (view != null) {
            if (view.isAttachedToWindow()) return true;
            // 窗口被系统摘掉了（显示器移除/重建、折叠时 display id 对调 —— 实机出现过），
            // 但 view 引用还在。这里必须清干净重挂，否则就是"假装成功"：
            // 服务记 visible=true、屏幕却什么都没有，而且之后永远不再重挂。
            removeNow(view);
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
        // 入场亮度的起点 = 当前屏幕亮度：直接写死目标亮度会「啪」一下变暗
        float targetBrightness = -1f;
        if (brightnessPercent > 0 && brightnessPercent < 100) {
            targetBrightness = brightnessPercent / 100f;
            restoreBrightness = currentBrightness();
            lp.screenBrightness = restoreBrightness;
        }
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
        this.params = lp;
        this.displayId = target == null ? 0 : target.getDisplayId();

        // ★ 回读校验：addView 不抛异常不代表用户看得见（可能落在一块灭屏/被系统摘掉）。
        //   这一条是为"自检面板显示已弹出、屏幕上却什么都没有"准备的。
        verified = false;
        verifiedDisplayId = -1;
        final View checkView = content;
        content.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Display d = checkView.getDisplay();
                    verified = checkView.isAttachedToWindow() && d != null;
                    verifiedDisplayId = verified ? d.getDisplayId() : -1;
                    if (!verified) {
                        Log.w(TAG, "overlay 没有真的上屏 attached="
                                + checkView.isAttachedToWindow());
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "verify overlay failed", t);
                }
            }
        }, 400L);

        try {
            if (content instanceof StandByView) {
                ((StandByView) content).playEnter();
            } else {
                content.setAlpha(0f);
                content.animate().alpha(1f).setDuration(ENTER_MS).start();
            }
            if (targetBrightness > 0) {
                final float dimTo = targetBrightness;
                pendingDim = new Runnable() {
                    @Override
                    public void run() {
                        pendingDim = null;
                        rampBrightness(dimTo, DIM_MS);
                    }
                };
                handler.postDelayed(pendingDim, DIM_DELAY_MS);
            }
        } catch (Throwable t) {
            // 动画只是观感：出任何问题都退化成「直接出现」，不能影响显示本身
            Log.w(TAG, "enter animation failed", t);
            try {
                content.setAlpha(1f);
            } catch (Throwable ignored) {
            }
        }

        Log.i(TAG, "overlay shown on display " + displayId);
        return true;
    }

    /** 当前系统亮度（0~1）；读不到就给 0.5 兜底 */
    private float currentBrightness() {
        try {
            int v = android.provider.Settings.System.getInt(
                    app.getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS);
            if (v > 0) return Math.min(1f, v / 255f);
        } catch (Throwable ignored) {
        }
        return 0.5f;
    }

    /** 窗口亮度渐变（每帧 updateViewLayout） */
    private void rampBrightness(final float to, long durationMs) {
        try {
            rampBrightnessInner(to, durationMs);
        } catch (Throwable t) {
            Log.w(TAG, "brightness ramp failed", t);
            // 退化成直接设目标亮度
            try {
                if (params != null && view != null && wm != null) {
                    params.screenBrightness = to;
                    wm.updateViewLayout(view, params);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void rampBrightnessInner(final float to, long durationMs) {
        final View v = view;
        final WindowManager w = wm;
        final WindowManager.LayoutParams lp = params;
        if (v == null || w == null || lp == null) return;
        if (brightnessAnim != null) {
            brightnessAnim.cancel();
            brightnessAnim = null;
        }
        float from = lp.screenBrightness > 0 ? lp.screenBrightness : currentBrightness();
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(durationMs);
        // 缓入缓出：起步不突兀、收尾不戛然而止
        a.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator an) {
                if (view == null) return;
                lp.screenBrightness = (Float) an.getAnimatedValue();
                try {
                    w.updateViewLayout(v, lp);
                } catch (Throwable ignored) {
                }
            }
        });
        a.start();
        brightnessAnim = a;
    }

    /** 带淡出动画的收起（自动隐藏走这条） */
    public void hideAnimated() {
        if (view == null) {
            wm = null;
            displayId = -1;
            return;
        }
        final View v = view;
        if (pendingDim != null) {
            handler.removeCallbacks(pendingDim);
            pendingDim = null;
        }
        // 亮度先升回去，别在 6% 亮度下淡出
        if (params != null && params.screenBrightness > 0) {
            float to = restoreBrightness > 0 ? restoreBrightness : currentBrightness();
            rampBrightness(to, EXIT_MS);
        }
        try {
            if (v instanceof StandByView) {
                ((StandByView) v).playExit();
            } else {
                v.animate().alpha(0f).setDuration(EXIT_MS).start();
            }
        } catch (Throwable t) {
            Log.w(TAG, "exit animation failed", t);
        }
        pendingRemove = new Runnable() {
            @Override
            public void run() {
                removeNow(v);
            }
        };
        handler.postDelayed(pendingRemove, EXIT_GUARD_MS);
        Log.i(TAG, "overlay hiding (animated)");
    }

    private void cancelPendingRemove() {
        if (pendingRemove != null) {
            handler.removeCallbacks(pendingRemove);
            pendingRemove = null;
        }
    }

    private void removeNow(View v) {
        cancelPendingRemove();
        if (pendingDim != null) {
            handler.removeCallbacks(pendingDim);
            pendingDim = null;
        }
        if (brightnessAnim != null) {
            brightnessAnim.cancel();
            brightnessAnim = null;
        }
        if (v == null) {
            view = null;
            wm = null;
            displayId = -1;
            return;
        }
        try {
            if (wm != null) {
                wm.removeViewImmediate(v);
            }
        } catch (Throwable t) {
            Log.w(TAG, "removeView failed", t);
        }
        if (view == v) {
            view = null;
            wm = null;
            params = null;
            displayId = -1;
        }
    }

    /** 立即摘掉，不播动画（服务停止 / 被销毁时用） */
    public void hide() {
        View v = view;
        if (v == null) {
            cancelPendingRemove();
            wm = null;
            params = null;
            displayId = -1;
            return;
        }
        removeNow(v);
    }

    private WindowManager.LayoutParams buildParams(int brightnessPercent, boolean keepScreenOn) {
        // 刻意**不**加 FLAG_NOT_FOCUSABLE：
        // 悬浮窗不在 Activity 栈里，返回键 / 侧滑手势只会派发给"有焦点的窗口"。
        // 设成不可聚焦时，手势产生的 back 会落到后面的桌面，时钟就永远退不出去。
        // 可聚焦 + StandByView.dispatchKeyEvent 才能收到 KEYCODE_BACK。
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
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
                // 必须是 TRANSLUCENT：OPAQUE 的窗口做 alpha 动画只会淡成黑色，
                // 看不到下面的系统界面。铭铭黑底是靠内容自己画的，不影响观感。
                PixelFormat.TRANSLUCENT);
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

    /**
     * 现在是不是「只亮着外屏」= 合盖。v1.4.1 起用它取代铰链角度判定。
     *
     * 为什么要换掉铰链：这台机器合上以后 TYPE_HINGE_ANGLE 会飘到 38~48°
     * （实测还在 ±2° 抖动），而代码里 foldAngleMax 默认 30° —— 于是同一台「合着」的手机，
     * 系统那边 mIsFolded=true / mDeviceState=FOLDED，本 App 却判成「已展开」，
     * 两套判定打架，结果就是插电不亮、时好时坏。
     *
     * 改问系统，两条路都走通：
     *  ① 能看到两块屏：面积大的那块是内屏 —— 内屏不亮 ⇒ 合盖。
     *  ② HyperOS 折叠时经常只把「当前那块屏」放进 App 的 display group
     *     （实测日志就是 displays=0:ON，内屏压根不在列表里），这时只能看到一块屏，
     *     就按尺寸认它是不是外屏：外屏最小边 1168px ≈ 425dp，内屏 1672px ≈ 608dp，
     *     以 550dp 为界。展开时轮不到外屏亮，所以这个判法不会误弹。
     */
    public boolean isCoverScreenOnly() {
        DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            return false;
        }
        Display[] all;
        try {
            all = dm.getDisplays();
        } catch (Throwable t) {
            return false;
        }
        if (all == null || all.length == 0) return false;

        if (all.length >= 2) {
            // ① 面积最大的那块 = 内屏；它不亮就是合盖
            Display inner = null;
            long innerArea = -1L;
            for (Display d : all) {
                if (d == null) continue;
                try {
                    if ((d.getFlags() & Display.FLAG_PRIVATE) != 0) continue;
                } catch (Throwable ignored) {
                    // getFlags 拿不到就不筛
                }
                Point p = new Point();
                try {
                    d.getSize(p);
                } catch (Throwable t) {
                    continue;
                }
                long area = (long) p.x * (long) p.y;
                if (area > innerArea) {
                    innerArea = area;
                    inner = d;
                }
            }
            if (inner == null) return false;
            try {
                return inner.getState() != Display.STATE_ON;
            } catch (Throwable t) {
                return false;
            }
        }

        // ② 只看得到一块屏：它是外屏吗？
        Display only = all[0];
        if (only == null) return false;
        Point p = new Point();
        try {
            only.getSize(p);
        } catch (Throwable t) {
            return false;
        }
        int minPx = Math.min(p.x, p.y);
        int dpi = app.getResources().getDisplayMetrics().densityDpi;
        if (dpi <= 0) dpi = 440;
        int minDp = Math.round(minPx * 160f / dpi);
        boolean coverOnly = minDp < 550;
        Log.i(TAG, "只有一块屏 " + only.getDisplayId() + " " + p.x + "x" + p.y
                + " (最小边 " + minDp + "dp) -> coverOnly=" + coverOnly);
        return coverOnly;
    }

    /** 给自检页看的：重算一次 coverOnly 的判定依据，压成一句话。 */
    public String coverReason(Context c) {
        DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return "no-dm";
        Display[] all;
        try {
            all = dm.getDisplays();
        } catch (Throwable t) {
            return "ex";
        }
        if (all == null || all.length == 0) return "0disp";
        if (all.length >= 2) {
            Display inner = null;
            long innerArea = -1L;
            for (Display d : all) {
                if (d == null) continue;
                try {
                    if ((d.getFlags() & Display.FLAG_PRIVATE) != 0) continue;
                } catch (Throwable ignored) {
                }
                Point p = new Point();
                try {
                    d.getSize(p);
                } catch (Throwable t) {
                    continue;
                }
                long a = (long) p.x * (long) p.y;
                if (a > innerArea) {
                    innerArea = a;
                    inner = d;
                }
            }
            if (inner == null) return all.length + "disp no-inner";
            int st;
            try {
                st = inner.getState();
            } catch (Throwable t) {
                st = -999;
            }
            return all.length + "disp inner" + inner.getDisplayId()
                    + "=" + (st == -999 ? "?" : String.valueOf(st));
        }
        Display only = all[0];
        Point p = new Point();
        try {
            only.getSize(p);
        } catch (Throwable t) {
            return "1disp ex";
        }
        int minPx = Math.min(p.x, p.y);
        int dpi = app.getResources().getDisplayMetrics().densityDpi;
        if (dpi <= 0) dpi = 440;
        return "1disp minDp=" + Math.round(minPx * 160f / dpi);
    }

    /**
     * 挑一块能画的屏。
     *
     * 实机教训（E 系列取证）：
     *  - 旧版只认 STATE_ON：外屏处于 doze/AOD 或系统只是没报 ON 时直接挑不到，
     *    然后静默退回默认屏，可能挂到一块灭着的内屏上 —— 表现为"记成已弹出，但屏幕是黑的"。
     *    所以这里放宽到"只要不是 OFF"。
     *  - 旧版取 id 最小的那块：折叠时 display id 会对调，落点不可靠。
     *    改成优先"不是面积最大的那块"（面积最大 = 内屏），也就是优先外屏。
     */
    private Display pickDisplay() {
        DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
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

        // ★ v2 改动：内外屏都要支持，所以不再「固定优先外屏」，
        //   改成「优先当前真正亮着的那块屏」。
        //   折叠时：外屏 ON、内屏 OFF/doze → 选中外屏
        //   展开时：内屏 ON、外屏 OFF       → 选中内屏
        //   若一块 ON 的都没有（例如外屏处于 doze），退回旧策略：取 id 最小的那块（通常是外屏）。
        Display onLargest = null;     // 亮着的里面面积最大 = 当前正在用的那块
        long onArea = -1L;
        Display smallest = null;      // 兜底：非 OFF 里 id 最小
        for (Display d : all) {
            if (d == null) continue;
            int state;
            try {
                state = d.getState();
            } catch (Throwable t) {
                continue;
            }
            if (state == Display.STATE_OFF) continue;
            try {
                if ((d.getFlags() & Display.FLAG_PRIVATE) != 0) continue;
            } catch (Throwable ignored) {
                // getFlags 拿不到就不筛
            }
            Point p = new Point();
            long area = 0L;
            try {
                d.getSize(p);
                area = (long) p.x * (long) p.y;
            } catch (Throwable ignored) {
            }
            if (state == Display.STATE_ON && area > onArea) {
                onArea = area;
                onLargest = d;
            }
            if (smallest == null || d.getDisplayId() < smallest.getDisplayId()) {
                smallest = d;
            }
        }
        if (onLargest != null) return onLargest;
        return smallest;
    }

    /** 已经显示时，姿态变了就地把内容转过去（否则会一直保持插电那一刻的方向）。 */
    public void updateRotation(int rotation) {
        if (view instanceof StandByView) {
            ((StandByView) view).setDesiredRotation(rotation);
        }
    }

    /** 上一次 addView 之后回读的"真的上屏了吗"。 */
    public boolean verified() {
        return verified;
    }

    public int verifiedDisplayId() {
        return verifiedDisplayId;
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
