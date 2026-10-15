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

    private static final String TAG = "FoldClock/Svc";
    private static final int NOTI_ID = 0x10C0;
    private static final long TICK_MS = 20000L;

    /** 同进程内给设置页的自检面板看的状态 */
    public static volatile boolean isRunning = false;
    public static volatile boolean overlayShowing = false;
    public static volatile int overlayDisplay = -1;

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
        overlay = new OverlayClock(this);
        posture = new PostureMonitor(this);
        posture.setOnChangeListener(new PostureMonitor.OnChangeListener() {
            @Override
            public void onPostureChanged(int rotation) {
                // 手机一转到竖屏 / 平放就立刻收掉时钟，不用等 20 秒的兜底轮询
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
            hide(true);
            return START_STICKY;
        }
        if (ACTION_SHOW.equals(action)) {
            userDismissed = false;
            show("manual");
            return START_STICKY;
        }

        // 其它情况（开机、插电、tick）统一走评估
        evaluate(action == null ? "start" : action);

        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, TICK_MS);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
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
        started = false;
        isRunning = false;
        overlayShowing = false;
        overlayDisplay = -1;
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
        // ★ 只在手机横着放（A 形 / L 形立在桌上）时显示：竖着拿、平躺都不显示。
        boolean landscape = (pose == Surface.ROTATION_90 || pose == Surface.ROTATION_270);

        Log.i(TAG, "evaluate(" + why + ") charging=" + charging
                + " auto=" + Prefs.autoLaunch(this)
                + " coverOnly=" + coverOnly + " 姿态=" + pose
                + " 屏幕=" + OverlayClock.describeDisplays(this)
                + " dismissed=" + userDismissed + " visible=" + visible);

        if (!Prefs.autoLaunch(this)) {
            hideIf("disabled");
            return;
        }
        if (!charging) {
            hideIf("unplugged");
            return;
        }
        // 竖屏 / 平放：不显示；已经在显示的一并收掉（姿态一变就走到这里）
        if (!landscape) {
            Log.i(TAG, "evaluate(" + why + ") 手机不是横着放的（姿态=" + pose + "），不显示");
            hide(false);
            return;
        }

        // ★ v1.4.1：合盖判定改成问系统 —— 「内屏灭、外屏亮」就算合盖。
        // 不再用 TYPE_HINGE_ANGLE：这台机器合上以后那个值会飘到 38~48°，
        // 把 foldAngleMax（默认 30°）顶穿，于是系统说 FOLDED、本 App 说「已展开」，
        // 两套判定打架 → 插电不亮。显示状态没有这个问题。
        boolean was = lastFolded;
        lastFolded = coverOnly;
        // 折叠状态真的变了（展开↔合盖），解除「用户已手动关掉」的抑制
        if (coverOnly != was) {
            userDismissed = false;
        }

        Log.i(TAG, "folded=" + coverOnly + " (was " + was + ") dismissed=" + userDismissed);

        if (Prefs.ignoreHinge(this)) {
            if (!userDismissed) show(why + "/no-fold");
            return;
        }
        if (coverOnly) {
            if (!userDismissed) show(why + "/folded");
        } else {
            hideIf("opened");
        }
    }

    /** 当前能确定的手机姿态；-1 = 判不出来（平放 / 没有传感器 / 还没有读数）。 */
    private int postureRotation() {
        return posture == null ? -1 : posture.currentRotation();
    }

    private void show(String why) {
        if (overlay == null) return;
        int mode = Prefs.showMode(this);
        boolean manual = "manual".equals(why);
        boolean overlayUp = overlay.isShowing();
        if (visible) {
            if (overlayUp) return;
            // 「仅启动界面」模式下没有窗口可查，用时间窗防止每 20 秒重复拉起
            if (mode == Prefs.MODE_ACTIVITY
                    && SystemClock.elapsedRealtime() - lastShowAt < 120000L) {
                return;
            }
        }
        boolean ok = false;

        if (mode != Prefs.MODE_ACTIVITY) {
            StandByView v = new StandByView(this, new StandByView.OnTapListener() {
                @Override
                public void onTap() {
                    userDismissed = true;
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

        if (!ok && mode != Prefs.MODE_OVERLAY) {
            ok = launchActivity();
        }

        if (ok) {
            visible = true;
            // 手动点出来的，不跟着「展开/拔电」自动关
            autoShown = !manual;
            lastShowAt = SystemClock.elapsedRealtime();
            int disp = overlay.isShowing() ? overlay.shownDisplayId() : -1;
            overlayShowing = overlay.isShowing();
            overlayDisplay = disp;
            if (!manual) {
                Prefs.setLastTrigger(this, System.currentTimeMillis());
                Prefs.setLastTriggerReason(this, why);
            }
            Log.i(TAG, "shown by " + why + " (mode=" + mode + ", overlay=" + overlay.isShowing() + ")");
        } else {
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
        if (!force && !visible) return;
        if (overlay != null) {
            // 自动收起走淡出动画（窗口在动画结束后才摘掉，状态先按已收起算）
            overlay.hideAnimated();
        }
        visible = false;
        autoShown = false;
        overlayShowing = false;
        overlayDisplay = -1;
        updateNotification();
    }

    private void refreshStatusLine() {
        // 悬浮窗里的状态行由 StandByView 自己带；这里只需要刷新通知文本
        updateNotification();
    }

    // ---- 启动界面（备用路径）----

    private boolean launchActivity() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        i.putExtra(MainActivity.EXTRA_AUTO, true);
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
        boolean showing = overlay != null && overlay.isShowing();

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
