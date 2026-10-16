package com.example.foldclock;

import android.app.AppOpsManager;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;

/**
 * 诊断快照：把定位一次故障需要的所有事实压成固定几行，一屏截完。
 *
 * 设计原则（来自实机取证）：
 *  - 用户不需要看懂，只要能截图；所以全部 key=value，机器可读，行宽压到 ~60 字符。
 *  - 最有价值的不是"权限给没给"，而是"广播到底有没有到"、"系统有没有在丢"、
 *    "窗口到底有没有真的上屏"。
 *  - 结论码放在最后一行，一条链路上第一个不满足的门就是它。
 *
 * 实机结论（小米 18 Fold / HyperOS / Android 17）：
 *  - 插电广播是隐式广播，清单 receiver 收不到，只有运行期注册的能收到（必须是活进程）。
 *  - HyperOS 的「自启动」= appops 10008，决定系统肯不肯为广播拉起进程（开机/覆盖安装都靠它）。
 *  - HyperOS 的 Greezer 会在进程被判 idle/cached 后静默丢弃插电广播。
 *  - 没有 SYSTEM_ALERT_WINDOW 时后台起前台服务会抛 ForegroundServiceStartNotAllowedException。
 */
public final class Diag {

    private Diag() {
    }

    // ---- 结论码 ----
    public static final String OK = "OK";
    public static final String E_AUTOOFF = "E-AUTOOFF";
    public static final String E_SVC_DEAD = "E-SVC-DEAD";
    public static final String E_NO_AUTOSTART = "E-NO-AUTOSTART";
    public static final String E_NO_RESTRICT = "E-NO-RESTRICT";
    public static final String E_NO_SAW = "E-NO-SAW";
    public static final String E_POWER_DROPPED = "E-POWER-DROPPED";
    public static final String E_NO_DISPLAY = "E-NO-DISPLAY";
    public static final String E_SHOW_FAILED = "E-SHOW-FAILED";
    public static final String E_POSTURE_STALE = "E-POSTURE-STALE";

    /** 一行版结论码（最后一行用）。 */
    public static String verdict(Context c) {
        if (!Prefs.autoLaunch(c)) return E_AUTOOFF;
        if (!AutoLaunchService.isRunning) return E_SVC_DEAD;
        // 自认为"显示中"，但既没有悬浮窗、也不是 Activity 在显示 → 典型的假成功
        if (Prefs.showMode(c) != Prefs.MODE_ACTIVITY
                && AutoLaunchService.visibleNow
                && !AutoLaunchService.overlayAttached
                && !AutoLaunchService.activityShowing) return E_SHOW_FAILED;
        String as = miuiAutostart(c);
        // 读不到（"?..."）**不算**失败：反射隐藏 API 在部分系统上会被拦，
        // 那时误报 E-NO-AUTOSTART 会把用户带去查一个根本没坏的东西。
        if ("ignore".equals(as) || "deny".equals(as)) return E_NO_AUTOSTART;
        if (!noRestrict(c) && !battOpt(c)) return E_NO_RESTRICT;
        if (!Perms.canDrawOverlays(c)) return E_NO_SAW;
        if (plugged(c) != 0 && lastPowerBcAgoMs() > 10 * 60_000L) return E_POWER_DROPPED;
        if (!targetDisplayOk(c)) return E_NO_DISPLAY;
        return OK;
    }

    /** 结论码对应的处置一句话。 */
    public static String hint(Context c, String v) {
        switch (v) {
            case E_AUTOOFF:
                return "总开关未开：设置页第一项「自动在外屏启动时钟」";
            case E_SVC_DEAD:
                return "常驻服务没跑：把总开关关了再开一次，或点「启动常驻服务」";
            case E_NO_AUTOSTART:
                return "HyperOS 自启动未开：设置→应用→外屏时钟→自启动（更新后会被重置）";
            case E_NO_RESTRICT:
                return "省电策略未设无限制：充电广播会被系统静默丢弃（Greezer）";
            case E_NO_SAW:
                return "缺悬浮窗权限：后台起前台服务会被系统拒绝";
            case E_POWER_DROPPED:
                return "插着电但很久没收到插电广播：系统在丢广播，见上一条";
            case E_NO_DISPLAY:
                return "当前没有可绘制的外屏：合盖/亮屏后再看";
            case E_SHOW_FAILED:
                return "上次显示失败：看下方 OVL 行与目标屏";
            default:
                return "链路正常";
        }
    }

    // ---- 快照正文 ----
    public static String snapshot(Context c, PostureMonitor posture) {
        StringBuilder b = new StringBuilder();

        // 1 版本 / 机型 / 系统 / 开机时长
        String ver = "?";
        long vc = -1;
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            ver = pi.versionName;
            vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable ignored) {
        }
        line(b, "FC " + ver + "(" + vc + ") " + Build.MODEL + " A" + Build.VERSION.RELEASE
                + " " + uptime());

        // 2 开关
        line(b, "AUTO " + (Prefs.autoLaunch(c) ? "on" : "off")
                + " mode=" + modeName(Prefs.showMode(c))
                + " ignoreFold=" + (Prefs.ignoreHinge(c) ? "on" : "off")
                + " keepOn=" + (Prefs.keepScreenOn(c) ? "on" : "off"));

        // 3 供电 + 广播到达情况（最关键）
        int plug = plugged(c);
        long bc = lastPowerBcAgoMs();
        line(b, "PWR plugged=" + plug + " chg=" + (plug != 0 ? "yes" : "no")
                + " lastBc=" + (bc < 0 ? "NEVER" : ago(bc))
                + " x" + AutoLaunchService.powerBcCount);

        // 4 姿态
        if (posture == null || !posture.available()) {
            line(b, "POST 本机无加速度计");
        } else {
            int r = posture.lastRotation();
            line(b, "POST rot=" + r + "(" + rotName(r) + ")"
                    + " flat=" + (posture.isFlat() ? "yes" : "no")
                    + " ev=" + (posture.hasRecentEvent() ? "yes" : "no")
                    + " age=" + ms(posture.cacheAgeMs()));
        }

        // 5 折叠/外屏判定
        boolean coverOnly = false;
        String coverWhy = "n/a";
        try {
            OverlayClock oc = new OverlayClock(c);
            coverOnly = oc.isCoverScreenOnly();
            coverWhy = oc.coverReason(c);
        } catch (Throwable ignored) {
        }
        line(b, "FOLD coverOnly=" + (coverOnly ? "yes" : "no") + " [" + coverWhy + "]");

        // 6 悬浮窗
        line(b, "OVL perm=" + (Perms.canDrawOverlays(c) ? "yes" : "no")
                + " attach=" + (AutoLaunchService.overlayAttached ? "yes" : "no")
                + " ok=" + (AutoLaunchService.overlayVerified ? "yes" : "no")
                + " disp=" + AutoLaunchService.overlayDisplay
                + " show=" + (AutoLaunchService.lastShowOk ? "ok" : "fail")
                + " err=" + shortErr());

        // 7 服务
        line(b, "SVC run=" + (AutoLaunchService.isRunning ? "yes" : "no")
                + " up=" + ms(AutoLaunchService.startedAt == 0 ? -1
                : SystemClock.elapsedRealtime() - AutoLaunchService.startedAt)
                + " eval=" + AutoLaunchService.evaluateCount
                + " last=" + AutoLaunchService.lastEvaluateWhy);

        // 8 HyperOS 三道闸门
        line(b, "MIUI autostart=" + miuiAutostart(c)
                + " norestr=" + (noRestrict(c) ? "yes" : "no")
                + " battOpt=" + (battOpt(c) ? "yes" : "no")
                + " bucket=" + bucket(c));

        // 9 显示器逐块
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        Display[] all = dm == null ? null : dm.getDisplays();
        if (all == null || all.length == 0) {
            line(b, "DISP n/a");
        } else {
            for (Display d : all) {
                if (d == null) continue;
                Point p = new Point();
                try {
                    d.getSize(p);
                } catch (Throwable ignored) {
                    continue;
                }
                int flags;
                try {
                    flags = d.getFlags();
                } catch (Throwable t) {
                    flags = 0;
                }
                line(b, "DISP " + d.getDisplayId() + ":" + stateName(d.getState())
                        + " " + p.x + "x" + p.y + " r" + d.getRotation()
                        + " " + (isPrivate(flags) ? "PRIV" : "pub"));
            }
        }

        // 10 上次触发
        long t = Prefs.lastTrigger(c);
        line(b, "TRIG last=" + (t <= 0 ? "NEVER" : ago(System.currentTimeMillis() - t))
                + " why=" + Prefs.lastTriggerReason(c)
                + " dism=" + (AutoLaunchService.dismissedNow ? "yes" : "no")
                + " vis=" + (AutoLaunchService.visibleNow ? "yes" : "no"));

        // 11 锁屏场景（HyperOS）：这两个开关决定"锁屏状态下插电能不能弹出来"
        //    popup = 后台弹出界面（10021），screen = 锁屏显示（10020），fsi = 全屏通知权限
        line(b, "LOCK popup=" + miuiOp(c, 10021) + " screen=" + miuiOp(c, 10020)
                + " fsi=" + (fsiAllowed(c) ? "yes" : "no"));

        // 12 屏保（Dream）状态：本机是否把我们选成屏保、屏保开关是否打开。
        //    注意：本机被选中 ≠ 系统会真的启动它 —— HyperOS 等自带 AOD 的机型
        //    由系统 doze 接管息屏，AOSP 的 Dream 不会被拉起（实测息屏 50s 仍是 mCurrentDream=null）。
        line(b, "DREAM selected=" + (dreamSelected(c) ? "yes" : "no")
                + " en=" + dreamEnabled(c)
                + " hint=" + (dreamSelected(c) ? "系统不一定触发" : "-"));

        // 12 结论（单独一行由界面大字显示，这里不重复）
        return b.toString();
    }

    /** 复制/分享用：快照 + 结论码 + 处置建议。 */
    public static String fullText(Context c, PostureMonitor posture) {
        String v = verdict(c);
        return snapshot(c, posture) + "VERDICT " + v + "\nHINT " + hint(c, v) + "\n";
    }

    // ---------------- 各字段实现 ----------------

    private static String shortErr() {
        String e = AutoLaunchService.lastShowErr;
        if (e == null || e.isEmpty()) return "-";
        e = e.replace('\n', ' ');
        return e.length() > 22 ? e.substring(0, 22) : e;
    }

    static long lastPowerBcAgoMs() {
        long t = AutoLaunchService.lastPowerBcAt;
        return t <= 0 ? -1 : System.currentTimeMillis() - t;
    }

    static int plugged(Context c) {
        try {
            Intent b = c.registerReceiver(null, new android.content.IntentFilter(
                    Intent.ACTION_BATTERY_CHANGED));
            return b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean targetDisplayOk(Context c) {
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        Display[] all = dm == null ? null : dm.getDisplays();
        if (all == null) return false;
        for (Display d : all) {
            if (d == null) continue;
            try {
                if (d.getState() != Display.STATE_OFF
                        && (d.getFlags() & Display.FLAG_PRIVATE) == 0) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** HyperOS 自启动 = appops 10008 */
    static String miuiAutostart(Context c) {
        return miuiOp(c, 10008);
    }

    /**
     * 通用：读 MIUI 私有 appop 的当前模式。
     * 实测编号：10008=自启动，10020=锁屏显示，10021=后台弹出界面。
     */
    static String miuiOp(Context c, int op) {
        try {
            AppOpsManager aom = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            java.lang.reflect.Method m = AppOpsManager.class.getMethod(
                    "checkOpNoThrow", int.class, int.class, String.class);
            Object r = m.invoke(aom, op, android.os.Process.myUid(), c.getPackageName());
            if (!(r instanceof Integer)) return "?type";
            int mode = (Integer) r;
            if (mode == AppOpsManager.MODE_ALLOWED) return "allow";
            if (mode == AppOpsManager.MODE_IGNORED) return "ignore";
            if (mode == AppOpsManager.MODE_ERRORED) return "deny";
            return "m" + mode;
        } catch (Throwable t) {
            return "?" + t.getClass().getSimpleName();
        }
    }

    /** Android 14+：用户是否允许本应用使用「全屏通知」。 */
    static boolean fsiAllowed(Context c) {
        try {
            if (Build.VERSION.SDK_INT < 34) return true;
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.canUseFullScreenIntent();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否在 HyperOS 的"不受限"名单里（PowerKeeper / miui-services 真的读这个 key）。 */
    static boolean noRestrict(Context c) {
        try {
            String v = Settings.System.getString(c.getContentResolver(), "MILLET_NO_RESTRICT_APP");
            return v != null && v.contains(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean battOpt(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    static int bucket(Context c) {        try {
            UsageStatsManager u = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            return u == null ? -1 : u.getAppStandbyBucket();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 本 App 的 DreamService 是不是被系统选成了屏保。 */
    static boolean dreamSelected(Context c) {
        try {
            String v = Settings.Secure.getString(c.getContentResolver(), "screensaver_components");
            return v != null && v.contains(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 屏保开关（0/1）；读不到返回 "?"。 */
    static String dreamEnabled(Context c) {
        try {
            int v = Settings.Secure.getInt(c.getContentResolver(), "screensaver_enabled", -1);
            return v < 0 ? "?" : String.valueOf(v);
        } catch (Throwable t) {
            return "?";
        }
    }

    // ---------------- 小工具 ----------------

    private static void line(StringBuilder b, String s) {
        if (s.length() > 62) s = s.substring(0, 62);
        b.append(s).append('\n');
    }

    static String modeName(int m) {
        return m == Prefs.MODE_OVERLAY ? "overlay" : (m == Prefs.MODE_ACTIVITY ? "activity" : "auto");
    }

    static String stateName(int s) {
        switch (s) {
            case Display.STATE_ON:
                return "ON";
            case Display.STATE_OFF:
                return "OFF";
            case Display.STATE_DOZE:
                return "DOZE";
            case Display.STATE_DOZE_SUSPEND:
                return "DOZES";
            case Display.STATE_UNKNOWN:
                return "UNK";
            default:
                return String.valueOf(s);
        }
    }

    static boolean isPrivate(int flags) {
        return (flags & Display.FLAG_PRIVATE) != 0;
    }

    static String rotName(int r) {
        switch (r) {
            case 0:
                return "PORT";
            case 1:
                return "LAND";
            case 2:
                return "PORT180";
            case 3:
                return "LAND270";
            default:
                return "?";
        }
    }

    static String ms(long v) {
        if (v < 0) return "-";
        if (v < 1000) return v + "ms";
        if (v < 60_000) return (v / 1000) + "s";
        return (v / 60_000) + "m";
    }

    static String ago(long ms) {
        if (ms < 0) return "-";
        if (ms < 1000) return "0s";
        if (ms < 60_000) return (ms / 1000) + "s";
        if (ms < 3_600_000) return (ms / 60_000) + "m";
        return (ms / 3_600_000) + "h";
    }

    static String uptime() {
        long up = SystemClock.elapsedRealtime() / 1000L;
        long d = up / 86400;
        long h = (up % 86400) / 3600;
        long m = (up % 3600) / 60;
        if (d > 0) return "up=" + d + "d" + h + "h";
        if (h > 0) return "up=" + h + "h" + m + "m";
        return "up=" + m + "m" + (up % 60) + "s";
    }
}
