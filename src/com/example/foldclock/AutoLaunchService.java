package com.example.foldclock;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.example.foldclock.ui.StandByView;

/**
 * 常驻前台服务：充电 + 合盖 → 自动把时钟亮出来。
 *
 * v1.3.7 为什么会「充电时自动开启」不生效，这里逐条修：
 *  1. 加了 BootReceiver（开机 / 覆盖安装后自己把服务拉起来）。原版没有——重启一次就永久失效。
 *  2. 合盖判定：v1.4.0 用 TYPE_HINGE_ANGLE 主动取样（角度 ≤ 30° 算合盖）→ 实测合盖后会飘到
 *     38~48°，阈值根本靠不住；v1.4.1 换成问系统「内屏灭、外屏亮」（OverlayClock.isCoverScreenOnly）。
 *  2.5 显示方向：只有横屏一套排版，而且**只在手机横着放的时候显示**。
 *     判定用重力姿态（PostureMonitor），不用屏幕方向 —— 外屏方向是「谁在前台谁说了算」，
 *     桌面等竖屏锁定的界面在前台时外屏永远 ROTATION_0，而实测这时手机其实是横着立着的。
 *     横着放（A 形 / L 形立在桌上）→ 显示（屏幕被锁成竖屏时内容整体转 90° 铺满）；
 *     竖着拿 / 平躺 → 不显示，已经在显示的立刻退出（由姿态变化回调驱动，不用等轮询）。
 *  3. 默认走悬浮窗，而不是 startActivity()。悬浮窗不是「后台启动 Activity」，
 *     绕开 Android 10+ 的 BAL 限制，也不用求 HyperOS 的「后台弹出界面」。
 *  4. 真要启动界面时，targetSdk 34+ 用 PendingIntent + 显式声明
 *     MODE_BACKGROUND_ACTIVITY_START_ALLOWED，把自己（同时是 creator 和 sender）
 *     的 SYSTEM_ALERT_WINDOW 特权显式委托出去——AOSP 的 BAL 检查认这一条。
 *  5. 20 秒兜底轮询，漏掉的广播/传感器事件能自愈。
 */
public class AutoLaunchService extends Service {

    public static final String ACTION_REFRESH = "com.example.foldclock.action.REFRESH";
    public static final String ACTION_SHOW = "com.example.foldclock.action.SHOW";
    public static final String ACTION_HIDE = "com.example.foldclock.action.HIDE";
    public static final String ACTION_STOP = "com.example.foldclock.action.STOP";
    /** 设置页进入前台：收起时钟（悬浮窗在所有 Activity 之上，不收起来设置页就没法用） */
    public static final String ACTION_UI_ON = "com.example.foldclock.action.UI_ON";
    /** 设置页不再可见（onStop）：先起一个宽限计时，别立刻弹回来 */
    public static final String ACTION_UI_OFF = "com.example.foldclock.action.UI_OFF";
    /** 设置页彻底关闭（返回/销毁）：解除让位状态并（免延迟）把时钟弹回来 */
    public static final String ACTION_RESUME = "com.example.foldclock.action.RESUME";
    /** 服务要求"以 Activity 形式显示的时钟"退场（收起时钟时发） */
    public static final String ACTION_FINISH_UI = "com.example.foldclock.action.FINISH_UI";

    private static final String TAG = "FoldClock/Svc";
    private static final int NOTI_ID = 0x10C0;
    /** 锁屏时那条"全屏通知"的 id */
    private static final int NOTI_ID_FSI = 0x10C1;
    private static final long TICK_MS = 20000L;

    /** 同进程内给设置页的自检面板看的状态 */
    public static volatile boolean isRunning = false;
    public static volatile boolean overlayShowing = false;
    public static volatile int overlayDisplay = -1;
    /**
     * 这次显示是由 Activity 承担的（锁屏场景，或悬浮窗失败退回）。
     * 必须有这个标志：锁屏时不挂悬浮窗，于是 show() 里"窗口还在就别重复显示"的判断失效，
     * 会变成每个 tick 都重复拉起 Activity + 重复发全屏通知。
     * 同时给自检面板看 —— 否则锁屏下正常的 Activity 显示会被误判成 E-SHOW-FAILED。
     */
    public static volatile boolean activityShowing = false;

    // ---- 自检面板（Diag）用的观测点：全部是"出事了能一眼看出卡在哪"的事实 ----
    /** 服务创建时刻（elapsedRealtime），0 = 没在跑 */
    public static volatile long startedAt = 0L;
    /** 最后一次收到 ACTION_POWER_CONNECTED 的墙上时间；0 = 从来没收到过 */
    public static volatile long lastPowerBcAt = 0L;
    /** 收到插电广播的次数 */
    public static volatile int powerBcCount = 0;
    /** evaluate() 被调用的次数与最后一次的原因 */
    public static volatile int evaluateCount = 0;
    public static volatile String lastEvaluateWhy = "";
    /** 最后一次 show() 的结果与失败原因 */
    public static volatile boolean lastShowOk = false;
    public static volatile String lastShowErr = "";
    /** 悬浮窗是否真的挂在窗口树上（view != null 不等于挂上了，见 E 系列取证） */
    public static volatile boolean overlayAttached = false;
    /** addView 之后回读确认"真的上屏了" */
    public static volatile boolean overlayVerified = false;
    /** 抑制状态 / 当前是否可见，供自检页直接读 */
    public static volatile boolean dismissedNow = false;
    public static volatile boolean visibleNow = false;

    /** 姿态主动取样的最小间隔（避免 display-changed 风暴里反复触发 burst 采样） */
    private static final long SAMPLE_MIN_INTERVAL_MS = 3000L;
    /** 自动弹出前的延迟：iPhone 息屏时钟那种"不是啪一下马上出来"的节奏 */
    private static final long SHOW_DELAY_MS = 1000L;
    private long lastSampleAt = 0L;
    private PowerManager.WakeLock sampleWake;
    /** 排队中的"延迟弹出"任务；非 null = 正在等 */
    private Runnable pendingShow;
    /**
     * 在这个时刻之前，自动弹出不再延迟（设置页返回时用）。
     * 用"时间窗"而不是布尔标志：万一条件一直不满足，它会自己过期，不会残留状态。
     */
    private long showNoDelayUntil = 0L;

    /** 服务已进入销毁流程：迟到的回调一律早退，不再碰窗口 */
    private volatile boolean destroyed = false;

    /** show() 之后 700ms 的那次回读评估；必须是字段，否则 onDestroy 取消不掉 */
    private final Runnable postShow = new Runnable() {
        @Override
        public void run() {
            evaluate("post-show");
        }
    };

    /**
     * 设置页是否在前台 —— 独立的让位状态。
     *
     * 刻意**不复用 userDismissed**：后者会被插拔电/折叠变化重置，
     * 于是"在设置页里插一下电"就会让时钟重新盖回设置页上面；
     * 而设置页此时已经是 resumed、onStart 不再触发，也就再也不会让位
     * —— 表现就是"反复进出之后，点齿轮没反应"。
     */
    private volatile boolean settingsForeground = false;
    /** onStop 之后的宽限：用户可能只是跳去系统权限页，马上会回来 */
    private static final long SETTINGS_GRACE_MS = 5000L;
    /**
     * 进入设置页时，时钟要多盖这么久再淡出。
     *
     * 目的：把这期间系统的"设置页从右滑入"动画整个遮在时钟后面。
     * 主题里的 windowAnimationStyle、启动方的 ActivityOptions、Activity 自己的
     * overrideActivityTransition 在 HyperOS 上都会被忽略（实测三种都无效），
     * 与其跟系统动画较劲，不如用"悬浮窗永远在最上层"这一点把过程盖住：
     * 用户看到的就是"时钟淡成设置页"，底下的系统页面一秒都不会露。
     */
    private static final long SETTINGS_COVER_MS = 420L;
    private final Runnable uiHide = new Runnable() {
        @Override
        public void run() {
            if (!settingsForeground) return;
            Log.i(TAG, "遮罩结束 → 收起时钟，让出设置页");
            hide(true);
        }
    };
    private final Runnable settingsGone = new Runnable() {
        @Override
        public void run() {
            if (!settingsForeground) return;
            settingsForeground = false;
            Log.i(TAG, "设置页离开超过 " + SETTINGS_GRACE_MS + "ms → 恢复自动弹出");
            evaluate("settings-gone");
        }
    };

    private PostureMonitor posture;
    private OverlayClock overlay;
    private DisplayManager displayManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 屏幕一旋转就重新判定：竖过来要立刻收掉，横过去要立刻亮出来。 */
    private final DisplayManager.DisplayListener displayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    evaluate("display-removed");
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    evaluate("display-changed");
                }
            };

    private boolean charging;
    /** 当前是不是「有东西显示着」（悬浮窗或界面） */
    private boolean visible;
    /** 这次显示是不是自动触发的（自动的才跟着展开/拔电策略关） */
    private boolean autoShown;
    /** 用户手动关掉了：在插拔电/折叠状态真正变化之前，不要再自己弹回来 */
    private boolean userDismissed;
    private boolean lastFolded;
    private boolean started;
    private long lastShowAt;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            evaluate("tick");
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String a = intent.getAction();
            if (Intent.ACTION_POWER_CONNECTED.equals(a)) {
                charging = true;
                userDismissed = false;
                lastPowerBcAt = System.currentTimeMillis();
                powerBcCount++;
                Log.i(TAG, "POWER_CONNECTED");
                evaluate("power-connected");
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(a)) {
                charging = false;
                userDismissed = false;
                Log.i(TAG, "POWER_DISCONNECTED");
                evaluate("power-disconnected");
            }
        }
    };

    // ---- 生命周期 ----

    @Override
    public void onCreate() {
        super.onCreate();
        startedAt = SystemClock.elapsedRealtime();
        overlay = new OverlayClock(this);
        posture = new PostureMonitor(this);
        posture.setOnChangeListener(new PostureMonitor.OnChangeListener() {
            @Override
            public void onPostureChanged(int rotation) {
                // 只有"转成横放"才就地更新内容方向；转成竖屏/平放时**不要**更新，
                // 否则会先按新姿态重排一次版（屏幕上闪一下"竖屏里的横屏时钟"）再收掉。
                boolean landscape = (rotation == Surface.ROTATION_90
                        || rotation == Surface.ROTATION_270);
                if (landscape && overlay != null && overlay.isShowing()) {
                    overlay.updateRotation(rotation);
                }
                evaluate("posture");
            }
        });
        posture.start();
        charging = readCharging();
        registerPowerReceiver();
        try {
            displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (displayManager != null) {
                displayManager.registerDisplayListener(displayListener, handler);
            }
        } catch (Throwable t) {
            Log.w(TAG, "register display listener failed", t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        started = true;
        isRunning = true;

        String action = intent == null ? null : intent.getAction();

        // ★ 先把 20 秒兜底轮询武装好，再分派命令。
        //   以前只有默认分支和 RESUME 会武装它，于是"服务的第一个命令是 UI_ON / HIDE"
        //   这类路径下服务长期存活却永远没有自愈轮询，错过的广播/传感器事件再也补不回来。
        armTicker();

        if (ACTION_STOP.equals(action)) {
            hide(true);
            handler.removeCallbacks(ticker);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // 每次被拉起都刷一遍通知与服务状态
        updateNotification();

        if (ACTION_HIDE.equals(action)) {
            // 必须记住"用户主动关掉了"，否则 20 秒轮询 / display-changed 会立刻把它弹回来
            userDismissed = true;
            dismissedNow = true;
            hide(true);
            return START_STICKY;
        }
        if (ACTION_SHOW.equals(action)) {
            userDismissed = false;
            show("manual");
            return START_STICKY;
        }
        if (ACTION_UI_ON.equals(action)) {
            // 设置页来了：先**不要**立刻收时钟 —— 让它盖住系统那段"设置页滑入"动画，
            // SETTINGS_COVER_MS 之后再淡出，这时设置页已经落定，露出的就是它本身。
            settingsForeground = true;
            handler.removeCallbacks(settingsGone);
            handler.removeCallbacks(uiHide);
            handler.postDelayed(uiHide, SETTINGS_COVER_MS);
            Log.i(TAG, "设置页进入 → 时钟继续遮罩 " + SETTINGS_COVER_MS + "ms");
            return START_STICKY;
        }
        if (ACTION_UI_OFF.equals(action)) {
            // 设置页 onStop：可能只是跳去系统权限页，给一点宽限再恢复
            handler.removeCallbacks(settingsGone);
            handler.postDelayed(settingsGone, SETTINGS_GRACE_MS);
            Log.i(TAG, "设置页 onStop → " + SETTINGS_GRACE_MS + "ms 宽限后再恢复");
            return START_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            // 设置页彻底关闭：解除让位并（免延迟）把时钟弹回来
            handler.removeCallbacks(settingsGone);
            settingsForeground = false;
            userDismissed = false;
            dismissedNow = false;
            showNoDelayUntil = SystemClock.elapsedRealtime() + 2500L;
            Log.i(TAG, "设置页已关闭 → 解除让位、免延迟重新评估");
            evaluate("resume");
            handler.removeCallbacks(ticker);
            handler.postDelayed(ticker, TICK_MS);
            return START_STICKY;
        }

        // 其它情况（开机、插电、tick）统一走评估
        evaluate(action == null ? "start" : action);

        armTicker();
        return START_STICKY;
    }

    /** 武装 20 秒兜底轮询（错过的广播/传感器事件靠它自愈）。 */
    private void armTicker() {
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, TICK_MS);
    }

    @Override
    public void onDestroy() {
        // 先立"已销毁"的旗：迟到的回调（post-show 评估、遮罩计时、位置取样）之后一律不再碰窗口，
        // 否则会在一个已经死掉的服务对象上 addView，挂出一块谁都摘不掉的窗口。
        destroyed = true;
        handler.removeCallbacks(postShow);
        handler.removeCallbacks(uiHide);
        handler.removeCallbacks(settingsGone);
        handler.removeCallbacks(ticker);
        cancelPendingShow();
        releaseSampleWake();
        try {
            unregisterReceiver(powerReceiver);
        } catch (Throwable t) {
            Log.w(TAG, "unregister failed", t);
        }
        if (displayManager != null) {
            try {
                displayManager.unregisterDisplayListener(displayListener);
            } catch (Throwable ignored) {
            }
        }
        if (posture != null) {
            posture.stop();
        }
        if (overlay != null) {
            overlay.hide();
        }
        // 服务都没了，Activity 形式的时钟也不该留在屏幕上
        finishActivityUi();
        started = false;
        isRunning = false;
        overlayShowing = false;
        overlayDisplay = -1;
        overlayAttached = false;
        visibleNow = false;
        startedAt = 0L;
        // 实例状态也一并复位：服务对象虽然死了，但这些字段会被自检面板读到，
        // 留着会让面板显示上一次的"显示中/已验证/上次成功"，误导排查。
        visible = false;
        autoShown = false;
        settingsForeground = false;
        activityShowing = false;
        overlayVerified = false;
        lastShowOk = false;
        lastShowErr = null;
        dismissedNow = false;
        userDismissed = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- 对外入口 ----

    public static void start(Context c) {
        Intent i = new Intent(c, AutoLaunchService.class);
        i.setAction(ACTION_REFRESH);
        try {
            c.startForegroundService(i);
        } catch (Throwable t) {
            Log.w(TAG, "startForegroundService failed", t);
        }
    }

    public static void stop(Context c) {
        Intent i = new Intent(c, AutoLaunchService.class);
        i.setAction(ACTION_STOP);
        try {
            c.startForegroundService(i);
        } catch (Throwable t) {
            Log.w(TAG, "stop failed", t);
        }
    }

    /**
     * 设置页生命周期 → 服务让位状态。
     *
     * 悬浮窗是 TYPE_APPLICATION_OVERLAY，永远画在所有 Activity 之上，所以设置页
     * 一出现就必须把时钟收起来。三个入口分开：
     *   onStart   → shown()  ：收起时钟、进入让位状态
     *   onStop    → hidden() ：可能只是跳去系统权限页，给 SETTINGS_GRACE_MS 宽限
     *   返回/销毁 → closed() ：彻底关闭，免延迟把时钟弹回来
     *
     * 让位状态独立于 userDismissed：后者会被插拔电清掉，那样就会出现
     * "在设置页里插一下电 → 时钟盖回来 → 而且因为设置页已 resumed，onStart 不再触发，
     *  再点齿轮也不会让位"这种死局。
     */
    public static void onSettingsShown(Context c) {
        sendCommand(c, ACTION_UI_ON);
    }

    public static void onSettingsHidden(Context c) {
        sendCommand(c, ACTION_UI_OFF);
    }

    public static void onSettingsClosed(Context c) {
        sendCommand(c, ACTION_RESUME);
    }

    public static void sendCommand(Context c, String action) {
        Intent i = new Intent(c, AutoLaunchService.class);
        i.setAction(action);
        try {
            c.startForegroundService(i);
        } catch (Throwable t) {
            Log.w(TAG, "sendCommand " + action + " failed", t);
        }
    }

    // ---- 判定逻辑 ----

    private void evaluate(String why) {
        if (destroyed) return;
        evaluateCount++;
        lastEvaluateWhy = why;
        dismissedNow = userDismissed;
        visibleNow = visible;
        overlayAttached = overlay != null && overlay.isAttached();
        overlayVerified = overlay != null && overlay.verified();
        // 插电/拔电广播里已经明确知道状态了。这里若再去读一次 sticky 的
        // ACTION_BATTERY_CHANGED，有可能读到的还是上一个状态的旧值（广播先到、
        // sticky 还没刷新），结果就是「插了电却被判成没插」而错过这次弹出。
        // 所以电源事件直接采信广播带来的状态，其余场合才重新读取。
        boolean powerEvent = "power-connected".equals(why) || "power-disconnected".equals(why);
        if (!powerEvent) {
            charging = readCharging();
        }

        // 只在插着电的时候才去问屏幕/姿态（没插电的 tick 不用白跑）
        boolean coverOnly = false;
        int pose = -1;                  // 手机姿态，-1 = 判不出来（平放 / 没读数）
        if (charging && overlay != null) {
            coverOnly = overlay.isCoverScreenOnly();
            pose = postureRotation();
        }

        // ★ 实机结论（E 系列取证）：插电广播把 AP 唤醒的那一刻，姿态缓存可能是几十秒前的
        //   —— 加速度计不是 wakeup 传感器，SoC 挂起期间不投递。这时直接按缓存判定，
        //   就会"插着电、手机横着、却判成不是横着放"→ 收屏。
        //   所以缓存不新鲜时先主动取一次，这一次不下结论，等回调再判。
        if (charging && pose < 0 && samplePostureIfUnknown()) {
            Log.i(TAG, "evaluate(" + why + ") 姿态未知/缓存过期 → 已发起主动取样，本次跳过判定");
            return;
        }

        // ★ 只在手机横着放（A 形 / L 形立在桌上）时显示：竖着拿、平躺都不显示。
        boolean landscape = (pose == Surface.ROTATION_90 || pose == Surface.ROTATION_270);

        // ★ 折叠态采样 + "用户主动关掉"的复位，必须在下面所有 early-return **之前**做。
        //   以前它放在完整判定路径末尾，于是「显示中按返回收起 → 拿起来展开（通常竖着拿或
        //   顺手拔线，都会走 early-return）→ 重新合盖放回」这条最常见的操作序列里，
        //   lastFolded 从头到尾没变过，userDismissed 永远解不开 —— 时钟再也不显示，
        //   只能靠一次插拔电广播碰运气（而 HyperOS 会丢广播）。
        boolean wasFolded = lastFolded;
        lastFolded = coverOnly;
        if (coverOnly != wasFolded) {
            userDismissed = false;
            Log.i(TAG, "折叠态变化 " + wasFolded + " → " + coverOnly + "：解除「用户已关掉」的抑制");
        }

        Log.i(TAG, "evaluate(" + why + ") charging=" + charging
                + " auto=" + Prefs.autoLaunch(this)
                + " coverOnly=" + coverOnly + " 姿态=" + pose
                + " 屏幕=" + OverlayClock.describeDisplays(this)
                + " dismissed=" + userDismissed + " visible=" + visible);

        if (!Prefs.autoLaunch(this)) {
            cancelPendingShow();
            hideIf("disabled");
            return;
        }
        // ★ 设置页在前台：悬浮窗会盖住它。注意这个状态独立于 userDismissed，
        //   插拔电不会清掉它，所以不会出现"时钟盖着设置页、再点齿轮也没反应"。
        if (settingsForeground) {
            cancelPendingShow();
            hide(false);
            return;
        }
        if (!charging) {
            cancelPendingShow();
            hideIf("unplugged");
            return;
        }
        // 竖屏 / 平放：不显示；已经在显示的一并收掉（姿态一变就走到这里）
        if (!landscape) {
            Log.i(TAG, "evaluate(" + why + ") 手机不是横着放的（姿态=" + pose + "），不显示");
            cancelPendingShow();
            hide(false);
            return;
        }

        // ★ v1.4.1：合盖判定改成问系统 —— 「内屏灭、外屏亮」就算合盖。
        // 不再用 TYPE_HINGE_ANGLE：这台机器合上以后那个值会飘到 38~48°，
        // 把 foldAngleMax（默认 30°）顶穿，于是系统说 FOLDED、本 App 说「已展开」，
        // 两套判定打架 → 插电不亮。显示状态没有这个问题。
        // （折叠态采样与抑制复位已提到本方法开头，这里只负责决定显示与否）
        if (Prefs.ignoreHinge(this)) {
            if (!userDismissed) scheduleShow(why + "/no-fold");
            return;
        }
        if (coverOnly) {
            if (!userDismissed) scheduleShow(why + "/folded");
        } else {
            cancelPendingShow();
            hideIf("opened");
        }
    }

    /**
     * 自动弹出前先等一会儿（iPhone 息屏时钟那种"不是啪一下出来"的节奏）。
     *
     * 注意：已经在排队就**不要**重置计时 —— display-changed 一秒能来几十次，
     * 每次都重新 postDelayed 的话这个延迟永远不会到期。
     */
    private void scheduleShow(final String why) {
        // 已经显示着（窗口真的挂在树上）就不要再排队：
        // 否则每 20 秒的 tick、以及 display-changed 都会排一次，一秒后又白跑一遍 show()，
        // 日志被"延迟到点 → 弹出"刷屏，而且始终挂着一个待触发的任务。
        if (visible && overlay != null && overlay.isAttached()) return;
        if (pendingShow != null) return;
        // 设置页刚返回：这一次不延迟，立刻弹（用户是主动回来的，不该再等）
        if (SystemClock.elapsedRealtime() < showNoDelayUntil) {
            Log.i(TAG, "设置页返回 → 立刻弹出（跳过 " + SHOW_DELAY_MS + "ms 延迟）");
            show(why);
            return;
        }
        pendingShow = new Runnable() {
            @Override
            public void run() {
                pendingShow = null;
                Log.i(TAG, "延迟到点 → 弹出（" + why + "）");
                show(why);
            }
        };
        handler.postDelayed(pendingShow, SHOW_DELAY_MS);
        Log.i(TAG, "判定该显示 → " + SHOW_DELAY_MS + "ms 后弹出（" + why + "）");
    }

    private void cancelPendingShow() {
        if (pendingShow != null) {
            handler.removeCallbacks(pendingShow);
            Log.i(TAG, "取消排队中的弹出");
            pendingShow = null;
        }
    }

    /** 当前能确定的手机姿态；-1 = 判不出来（平放 / 没有传感器 / 还没有读数）。 */
    private int postureRotation() {
        return posture == null ? -1 : posture.currentRotation();
    }

    /**
     * 姿态未知（或缓存过期）时主动取一次。
     *
     * @return true = 已经发起取样，调用方这一次不要下结论
     */
    private boolean samplePostureIfUnknown() {
        if (posture == null || !posture.available()) return false;
        // 缓存"有帧"就是新鲜的：平放时 currentRotation() 返回 -1 是**真结论**，不必重取
        if (posture.hasRecentEvent()) return false;
        long now = SystemClock.elapsedRealtime();
        if (now - lastSampleAt < SAMPLE_MIN_INTERVAL_MS) return false;
        lastSampleAt = now;
        acquireSampleWake();
        Log.i(TAG, "姿态缓存不新鲜，主动取样一次（age=" + posture.cacheAgeMs() + "ms）");
        posture.request(1500L, new PostureMonitor.Callback() {
            @Override
            public void onRotation(int rotation) {
                releaseSampleWake();
                evaluate("posture-sample");
            }
        });
        return true;
    }

    /** 取样期间别让 AP 睡下去（否则第一帧加速度永远来不了，回调也不会触发）。 */
    private void acquireSampleWake() {
        try {
            if (sampleWake == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                sampleWake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "foldclock:sample");
                sampleWake.setReferenceCounted(false);
            }
            sampleWake.acquire(2500L);
        } catch (Throwable t) {
            Log.w(TAG, "取样 wakelock 获取失败", t);
        }
    }

    private void releaseSampleWake() {
        try {
            if (sampleWake != null && sampleWake.isHeld()) {
                sampleWake.release();
            }
        } catch (Throwable ignored) {
        }
    }

    private void show(String why) {
        if (destroyed) return;
        if (overlay == null) return;
        cancelPendingShow();
        int mode = Prefs.showMode(this);
        boolean manual = "manual".equals(why);
        boolean overlayUp = overlay.isAttached();
        if (visible) {
            if (overlayUp) return;
            // 注意：这里用 isAttached() 而不是 isShowing()。窗口被系统摘掉（显示器移除/重建、
            // 折叠时 display id 对调）时 view 引用还在，isShowing() 会一直为 true，
            // 于是永远不再重挂、屏幕上一直是黑的。isAttached() 才能发现"已经不在窗口树上"。
            // 界面由 Activity 承担时（锁屏 / 悬浮窗失败退回）没有窗口可查，
            // 用时间窗防止每 20 秒重复拉起；时间窗同时也是"Activity 被系统静默回收"的兜底。
            if ((activityShowing || mode == Prefs.MODE_ACTIVITY)
                    && SystemClock.elapsedRealtime() - lastShowAt < 120000L) {
                return;
            }
        }
        boolean ok = false;
        boolean locked = isKeyguardLocked();

        // 锁屏时**不要**挂悬浮窗：它在锁屏之上画不出来（实测），挂了只会在下面拉起的
        // Activity 之上再叠一个同样的时钟 —— 两者排版差一两个像素，边缘就出现重影/抖动。
        if (mode != Prefs.MODE_ACTIVITY && !locked) {
            // 点击关闭已取消（Prefs.hideOnTap 固定 false），不再传 tap 回调
            StandByView v = new StandByView(this, null);
            // 设置页返回：直接就位，不做透明渐入（渐变过程会先露出系统页面，突兀）
            v.setInstantEnter(SystemClock.elapsedRealtime() < showNoDelayUntil);
            // 返回键 / 侧滑手势退出：悬浮窗收不到 onBackPressed，在这里接管
            v.setOnBackListener(new StandByView.OnBackListener() {
                @Override
                public void onBack() {
                    userDismissed = true;
                    dismissedNow = true;
                    Log.i(TAG, "返回手势：收起时钟并抑制自动重弹");
                    hide(true);
                }
            });
            // 姿态明确时按姿态摆（屏幕被前台锁成竖屏就整体转 90°铺满）；
            // 判不出来（平放）就 -1，跟随屏幕方向，保证画出来的方向就是用户看屏幕的方向。
            v.setDesiredRotation(postureRotation());
            ok = overlay.show(v, Prefs.brightness(this), Prefs.keepScreenOn(this));
            if (ok) {
                refreshStatusLine();
            }
        }

        // ★ 锁屏状态：悬浮窗**画不到锁屏之上**（实测：窗口挂上了，屏幕上只有锁屏壁纸）。
        //   能盖住锁屏的只有 Activity，所以把两条"系统帮忙"的路**都**试一遍：
        //   ① 全屏通知（full-screen intent）：系统代启界面。实测同一条通知重复 post
        //      只算"更新"，不会再触发一次全屏界面 —— 顶多第一次有效。
        //   ② 直接委托启动 Activity：需要厂商「后台弹出界面」权限，
        //      只要授权了，实测每次都灵。
        //   两条都需要用户在系统里额外授权；没授权时锁屏下就是不显示（自检里能看到原因）。
        if (locked && mode != Prefs.MODE_OVERLAY) {
            // 全屏通知只在系统真的允许时发：未授予时它会被降级成普通通知，白发一条
            if (canUseFullScreenIntent()) {
                postFullScreenShow();
            } else {
                Log.i(TAG, "未授予「全屏通知」→ 跳过 FSI，直接走 Activity");
            }
            activityShowing = launchActivity(true) || activityShowing;
            ok = true;
        } else if (!ok && mode != Prefs.MODE_OVERLAY) {
            activityShowing = launchActivity(false) || activityShowing;
            ok = true;
        }

        lastShowOk = ok;
        if (ok) {
            visible = true;
            visibleNow = true;
            // 手动点出来的，不跟着「展开/拔电」自动关
            autoShown = !manual;
            lastShowAt = SystemClock.elapsedRealtime();
            int disp = overlay.isShowing() ? overlay.shownDisplayId() : -1;
            overlayShowing = overlay.isShowing();
            overlayDisplay = disp;
            // ★ 注意：这里**不要**用 isAttached() 判断成败。
            //   addView 返回时视图还没走到 attach（attach 发生在下一帧），当场读必然是 false，
            //   会造成"明明显示正常、自检却报 no-attach"的误报。
            //   attached/verified 由 400ms 后的回读校验 + 每次 evaluate 刷新。
            lastShowErr = "";
            if (!manual) {
                Prefs.setLastTrigger(this, System.currentTimeMillis());
                Prefs.setLastTriggerReason(this, why);
            }
            Log.i(TAG, "shown by " + why + " (mode=" + mode + ", overlay=" + overlay.isShowing()
                    + ", disp=" + disp + ")");
            // 回读校验跑在 400ms 后，这里再补一次评估，让自检状态能自己收敛（不用等 20 秒 tick）
            handler.removeCallbacks(postShow);
            handler.postDelayed(postShow, 700L);
        } else {
            lastShowErr = !overlay.canShow() ? "no-saw" : ("mode" + mode + "/addView-fail");
            Log.w(TAG, "show failed by " + why
                    + " — 悬浮窗权限=" + overlay.canShow() + " 显示方式=" + mode);
        }
        updateNotification();
    }

    /** 自动弹出的才跟着「展开关闭 / 拔电关闭」的策略走；手动点出来的不动。 */
    private void hideIf(String why) {
        if (!visible || !autoShown) {
            refreshStatusLine();
            return;
        }
        boolean unplugged = "unplugged".equals(why) || "power-disconnected".equals(why);
        boolean opened = "opened".equals(why);
        if (opened && !Prefs.exitOnUnfold(this)) return;
        if (unplugged && !Prefs.exitOnUnplug(this)) {
            refreshStatusLine();
            return;
        }
        hide(false);
    }

    private void hide(boolean force) {
        if (!force && !visible) {
            return;
        }
        cancelPendingShow();
        // 把锁屏那条全屏通知也撤掉：留着的话下次同 id post 只算"更新"，
        // 系统不会再触发一次全屏界面（第一次之后再也不会弹的元凶）。
        cancelFullScreenShow();
        if (overlay != null) {
            // 自动收起走淡出动画（窗口在动画结束后才摘掉，状态先按已收起算）
            overlay.hideAnimated();
        }
        visible = false;
        autoShown = false;
        overlayShowing = false;
        overlayDisplay = -1;
        overlayAttached = false;
        visibleNow = false;
        activityShowing = false;
        // ★ Activity 形式的时钟必须跟着一起退场：hide() 是所有收起路径的唯一出口
        //   （通知里的隐藏、关掉总开关、拔电、展开、设置页进入…）。只收悬浮窗的话，
        //   会出现"服务认为已收起、屏幕上却还挂着一块全屏时钟"。
        finishActivityUi();
        updateNotification();
    }

    /** 让以 Activity 形式显示的时钟（MainActivity）收起来。 */
    private void finishActivityUi() {
        try {
            Intent i = new Intent(ACTION_FINISH_UI);
            i.setPackage(getPackageName());
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    private void refreshStatusLine() {
        // 悬浮窗里的状态行由 StandByView 自己带；这里只需要刷新通知文本
        updateNotification();
    }

    // ---- 启动界面（备用路径）----

    /**
     * 锁屏时唯一能盖住锁屏的路径：全屏通知。
     *
     * 悬浮窗（TYPE_APPLICATION_OVERLAY）在锁屏之上画不出来；而 Activity 虽然能，
     * 但从后台直接 startActivity 会被后台启动限制/厂商开关拦。全屏通知是系统
     * 代我们启动界面的机制（系统闹钟、来电界面都是这么做的），正好绕开这一环。
     * 前提：渠道重要性必须是 HIGH，且 Android 14+ 用户允许了「全屏通知」。
     */
    private void postFullScreenShow() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra(MainActivity.EXTRA_AUTO, true);
        open.putExtra(MainActivity.EXTRA_LOCKED, true);
        PendingIntent pi = PendingIntent.getActivity(this, 0x31, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, App.CHANNEL_FSI)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text_showing))
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .setTimeoutAfter(30000L)
                .build();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTI_ID_FSI, n);
            Log.i(TAG, "锁屏中 → 发全屏通知拉起界面（canUseFSI=" + canUseFullScreenIntent() + "）");
        } catch (Throwable t) {
            Log.w(TAG, "全屏通知失败", t);
        }
    }

    /** 撤掉锁屏那条全屏通知（收起时钟时调用，保证下次 post 是"全新通知"）。 */
    private void cancelFullScreenShow() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTI_ID_FSI);
        } catch (Throwable ignored) {
        }
    }

    /** Android 14+ 用户可能关掉「全屏通知」；关掉了我们就退回悬浮窗（锁屏下看不见，但别更糟）。 */    private boolean canUseFullScreenIntent() {
        try {
            if (Build.VERSION.SDK_INT < 34) return true;
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            return nm == null || nm.canUseFullScreenIntent();
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean isKeyguardLocked() {
        try {
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean launchActivity(boolean locked) {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        i.putExtra(MainActivity.EXTRA_AUTO, true);
        if (locked) i.putExtra(MainActivity.EXTRA_LOCKED, true);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(this, 0x11, i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Bundle opts = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        .toBundle();
                pi.send(this, 0, null, null, null, null, opts);
            } else {
                startActivity(i);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "launchActivity failed", t);
            return false;
        }
    }

    // ---- 充电状态 ----

    /**
     * 只认「充电器是否插着」= EXTRA_PLUGGED != 0。
     *
     * 之前还看了 EXTRA_STATUS == CHARGING/FULL，那是错的：状态位在某些机型/场景下
     * 会在拔线后继续残留（充满、涓流、被别的 App 改过电池状态等），
     * 结果就是「没插电也一直当成充电中、反复弹窗」。插没插电，plugged 这一个字段就够了。
     */
    private boolean readCharging() {
        Intent b = null;
        try {
            b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable t) {
            Log.w(TAG, "read battery failed", t);
        }
        if (b == null) return false;
        return b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    private void registerPowerReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(powerReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(powerReceiver, f);
            }
        } catch (Throwable t) {
            Log.w(TAG, "register power receiver failed", t);
        }
    }

    // ---- 前台通知 ----

    private void startForegroundCompat() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTI_ID, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed", t);
        }
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            nm.notify(NOTI_ID, buildNotification());
        } catch (Throwable t) {
            Log.w(TAG, "notify failed", t);
        }
    }

    private Notification buildNotification() {
        // 用服务的 visible，而不是 overlay.isShowing()：
        // Activity（锁屏）路径下时钟明明在屏幕上，只看悬浮窗会得出"运行中"并给出「显示」按钮，
        // 用户永远拿不到「隐藏」；而且窗口被系统摘掉时 isShowing() 还会骗人。
        boolean showing = visible;

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent piOpen = PendingIntent.getActivity(this, 0x21, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent set = new Intent(this, SettingsActivity.class);
        set.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent piSet = PendingIntent.getActivity(this, 0x22, set,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent toggle = new Intent(this, AutoLaunchService.class);
        toggle.setAction(showing ? ACTION_HIDE : ACTION_SHOW);
        PendingIntent piToggle = PendingIntent.getService(this, 0x23, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Icon icon = Icon.createWithResource(this, R.drawable.ic_notification);

        Notification.Builder b = new Notification.Builder(this, App.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(showing ? R.string.notif_text_showing : R.string.notif_text_running))
                .setContentIntent(showing ? piSet : piOpen)
                .setOngoing(true)
                .setShowWhen(false);
        b.addAction(new Notification.Action.Builder(
                icon, getString(R.string.notif_action_toggle), piToggle).build());
        b.addAction(new Notification.Action.Builder(
                icon, getString(R.string.notif_action_settings), piSet).build());
        return b.build();
    }
}
