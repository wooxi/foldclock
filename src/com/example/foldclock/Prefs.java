package com.example.foldclock;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 全部设置项集中在这里。没有第三方依赖，纯 SharedPreferences。
 */
public final class Prefs {

    /** 显示方式 */
    public static final int MODE_AUTO = 0;      // 先试悬浮窗，失败再启动界面
    public static final int MODE_OVERLAY = 1;   // 只走悬浮窗（不需要「后台弹出界面」权限）
    public static final int MODE_ACTIVITY = 2;  // 只启动界面

    /** 表盘样式 */
    public static final int STYLE_ANALOG = 0;
    public static final int STYLE_DIGITAL = 1;

    private static final String FILE = "foldclock";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static void putBoolean(Context c, String k, boolean v) {
        sp(c).edit().putBoolean(k, v).apply();
    }

    private static void putInt(Context c, String k, int v) {
        sp(c).edit().putInt(k, v).apply();
    }

    // ---- 自动弹出 ----

    public static boolean autoLaunch(Context c) {
        return sp(c).getBoolean("auto_launch", false);
    }

    public static void setAutoLaunch(Context c, boolean v) {
        putBoolean(c, "auto_launch", v);
    }

    public static int showMode(Context c) {
        return sp(c).getInt("show_mode", MODE_AUTO);
    }

    public static void setShowMode(Context c, int v) {
        putInt(c, "show_mode", v);
    }

    public static boolean ignoreHinge(Context c) {
        return sp(c).getBoolean("ignore_hinge", false);
    }

    public static void setIgnoreHinge(Context c, boolean v) {
        putBoolean(c, "ignore_hinge", v);
    }

    public static int foldAngleMax(Context c) {
        return sp(c).getInt("fold_angle_max", 30);
    }

    public static void setFoldAngleMax(Context c, int v) {
        putInt(c, "fold_angle_max", v);
    }

    /** 超过这个角度就认为已经展开 */
    public static int unfoldAngle(Context c) {
        return sp(c).getInt("unfold_angle", 150);
    }

    public static void setUnfoldAngle(Context c, int v) {
        putInt(c, "unfold_angle", v);
    }

    // ---- 行为 ----

    public static boolean exitOnUnfold(Context c) {
        return sp(c).getBoolean("exit_on_unfold", true);
    }

    public static void setExitOnUnfold(Context c, boolean v) {
        putBoolean(c, "exit_on_unfold", v);
    }

    /** 拔电就自动收起来 —— 默认开，符合「充电时钟」的直觉（手动点出来的不受影响） */
    public static boolean exitOnUnplug(Context c) {
        return sp(c).getBoolean("exit_on_unplug", true);
    }

    public static void setExitOnUnplug(Context c, boolean v) {
        putBoolean(c, "exit_on_unplug", v);
    }

    /**
     * 点击时钟关闭：固定 false，不再做这个开关。
     * 退出的正常方式是系统返回手势 / 拔电 / 展开，而不是点一下屏幕。
     */
    public static boolean hideOnTap(Context c) {
        return false;
    }

    // ---- 显示 ----
    // 以下各项不再暴露开关，固定成定稿默认值（避免"设置项一堆但实测没作用"）。

    /** 固定模拟表盘 */
    public static int clockStyle(Context c) {
        return STYLE_ANALOG;
    }

    /** 固定显示秒针 */
    public static boolean showSeconds(Context c) {
        return true;
    }

    /** 固定 24 小时制（仅数字表盘用得到） */
    public static boolean use24h(Context c) {
        return true;
    }

    /** 固定显示农历 */
    public static boolean showLunar(Context c) {
        return true;
    }

    /** 固定显示日历面板（用户确认：默认开启，不留开关） */
    public static boolean showCalendar(Context c) {
        return true;
    }

    /** 固定不显示状态行 */
    public static boolean showStatusLine(Context c) {
        return false;
    }

    /** 固定开启防烧屏像素位移 */
    public static boolean burnIn(Context c) {
        return true;
    }

    /** 固定保持常亮 */
    public static boolean keepScreenOn(Context c) {
        return true;
    }

    /** 伪 AOD 亮度，百分比 1..100 */
    public static int brightness(Context c) {
        return sp(c).getInt("brightness", 6);
    }

    public static void setBrightness(Context c, int v) {
        putInt(c, "brightness", v);
    }

    // ---- 自检用的运行记录 ----

    public static long lastTrigger(Context c) {
        return sp(c).getLong("last_trigger", 0L);
    }

    public static void setLastTrigger(Context c, long v) {
        sp(c).edit().putLong("last_trigger", v).apply();
    }

    public static String lastTriggerReason(Context c) {
        return sp(c).getString("last_reason", "");
    }

    public static void setLastTriggerReason(Context c, String v) {
        sp(c).edit().putString("last_reason", v).apply();
    }
}
