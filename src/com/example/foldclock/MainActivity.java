package com.example.foldclock;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.example.foldclock.ui.StandByView;

/**
 * 全屏时钟界面。既可用作手动打开的主界面，也可被服务在充电合盖时拉起来。
 */
public class MainActivity extends Activity {

    public static final String EXTRA_AUTO = "auto";
    /** 这次是不是"锁屏状态下被全屏通知拉起来的" */
    public static final String EXTRA_LOCKED = "locked";

    private StandByView view;
    private PostureMonitor posture;
    private boolean autoShown;
    private boolean charging;
    private boolean launchedLocked;

    /**
     * 锁屏拉起来的这次：用户一解锁就把界面收掉，
     * 否则时钟会一直盖在他要用的桌面上。
     */
    private final BroadcastReceiver userPresentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (launchedLocked) {
                android.util.Log.i("FoldClock/Main", "用户已解锁 → 收起锁屏时钟");
                finish();
            }
        }
    };

    /** 用户解锁接收器当前是否已注册（onNewIntent 之后要能重新决定） */
    private boolean userPresentRegistered;

    /** 服务要求收起（通知里点隐藏、关开关、拔电、展开…）：界面必须真的退场 */
    private final BroadcastReceiver finishUiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            serviceRequestedFinish = true;
            android.util.Log.i("FoldClock/Main", "服务要求收起 → 关闭时钟界面");
            finish();
        }
    };

    /** 由服务（而不是用户）收起的：不要在 onDestroy 里再报一次"用户主动关掉" */
    private boolean serviceRequestedFinish;

    /**
     * 按 launchedLocked 决定是否监听「用户解锁」。
     * 单独抽出来是因为 onNewIntent 也会改变 launchedLocked，
     * 而接收器原本只在 onResume 里按当时的值注册 —— 单实例被锁屏再次拉起时，
     * 解锁即退的逻辑不会武装，时钟就会一直盖在桌面上。
     */
    private void syncLockedReceiver() {
        if (launchedLocked && !userPresentRegistered) {
            try {
                registerReceiver(userPresentReceiver,
                        new IntentFilter(Intent.ACTION_USER_PRESENT));
                userPresentRegistered = true;
            } catch (Throwable ignored) {
            }
        } else if (!launchedLocked && userPresentRegistered) {
            try {
                unregisterReceiver(userPresentReceiver);
            } catch (Throwable ignored) {
            }
            userPresentRegistered = false;
        }
    }

    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) {
                charging = true;
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) {
                charging = false;
            }
            updateStatus();
            // 「仅启动界面」模式下也要跟着拔电关掉，否则界面会一直留在屏幕上
            if (!charging && autoShown && Prefs.exitOnUnplug(MainActivity.this)) {
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        autoShown = getIntent() != null && getIntent().getBooleanExtra(EXTRA_AUTO, false);
        launchedLocked = getIntent() != null && getIntent().getBooleanExtra(EXTRA_LOCKED, false);

        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setShowWhenLocked(true);
        setTurnScreenOn(true);

        applyOrientation();

        // 点击关闭已取消（Prefs.hideOnTap 固定 false）：退出走系统返回手势 / 拔电 / 展开
        view = new StandByView(this, null);
        setContentView(view);

        charging = readCharging();

        // 自动弹出开着的话，顺手保证常驻服务在跑
        if (Prefs.autoLaunch(this)) {
            AutoLaunchService.start(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null) {
            autoShown = intent.getBooleanExtra(EXTRA_AUTO, false);
            // 单实例被重新拉起时，这两个状态可能整个变了（例如从普通显示变成锁屏显示），
            // 必须同步，否则"解锁即退"的接收器不会武装，时钟会一直盖在桌面上。
            launchedLocked = intent.getBooleanExtra(EXTRA_LOCKED, false);
        }
        syncLockedReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyBrightness();
        enterFullscreen();
        updateStatus();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(powerReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(powerReceiver, f);
            }
        } catch (Throwable ignored) {
        }

        // 服务要求收起（所有 hide() 路径）—— 必须能收到，否则服务以为收了、界面还在
        try {
            registerReceiver(finishUiReceiver,
                    new IntentFilter(AutoLaunchService.ACTION_FINISH_UI));
        } catch (Throwable ignored) {
        }

        // 锁屏拉起来的这次：用户解锁后就收掉，别占着他要用的桌面
        syncLockedReceiver();

        // 自动亮起来的这种，展开就该自己关掉
        if (autoShown && Prefs.exitOnUnfold(this)) {
            posture = new PostureMonitor(this);
            posture.start();
            watchUnfold();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        try {
            unregisterReceiver(powerReceiver);
        } catch (Throwable ignored) {
        }
        try {
            unregisterReceiver(finishUiReceiver);
        } catch (Throwable ignored) {
        }
        if (userPresentRegistered) {
            try {
                unregisterReceiver(userPresentReceiver);
            } catch (Throwable ignored) {
            }
            userPresentRegistered = false;
        }
        if (posture != null) {
            posture.stop();
            posture = null;
        }
    }

    @Override
    public void onBackPressed() {
        // 用户自己按返回：这是"主动关掉"，要让服务记住别再自动弹回来
        userClosed = true;
        super.onBackPressed();
    }

    /** 用户按返回主动关掉的（区别于解锁自动收起、被服务收起、被系统回收） */
    private boolean userClosed;

    @Override
    protected void onDestroy() {
        // 用户主动关掉：告诉服务"别再自动弹回来"（否则 20 秒轮询会立刻把它拉起来）。
        // 解锁自动收起 / 服务要求收起这两种不算，否则用户解一次锁就再也弹不出来了。
        if (autoShown && userClosed && !serviceRequestedFinish) {
            AutoLaunchService.sendCommand(this, AutoLaunchService.ACTION_HIDE);
        }
        super.onDestroy();
    }

    /** v1.4.2：不再看铰链角度，改看手机姿态 —— 竖过来了（用户拿走手机）就退出。 */
    private void watchUnfold() {
        if (posture == null) return;
        posture.request(1500L, new PostureMonitor.Callback() {
            @Override
            public void onRotation(int rotation) {
                charging = readCharging();
                updateStatus();
                if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
                    finish();
                }
            }
        });
    }

    /** 定时复查一次角度，展开即退出；顺手刷新状态行。 */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            watchUnfold();
            updateStatus();
            view.postDelayed(this, 5000L);
        }
    };

    private void applyOrientation() {
        // 整个 App 只有横屏一套排版（时钟左、日历右）。
        // 用 SENSOR_LANDSCAPE：系统只会决定横屏的左右朝向，永远不会切回竖屏。
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void applyBrightness() {
        int b = Prefs.brightness(this);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (b > 0 && b < 100) {
            lp.screenBrightness = b / 100f;
        } else {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
        getWindow().setAttributes(lp);
    }

    private void enterFullscreen() {
        try {
            Window w = getWindow();
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } catch (Throwable ignored) {
        }
    }

    private void updateStatus() {
        if (view == null) return;
        if (!Prefs.showStatusLine(this)) return;
        String pose = "—";
        if (posture != null) {
            int r = posture.lastRotation();
            if (r == Surface.ROTATION_0 || r == Surface.ROTATION_180) {
                pose = "竖放";
            } else if (r >= 0) {
                pose = "横放";
            }
        }
        view.setStatus((charging ? "充电中" : "未充电") + "  ·  姿态 " + pose
                + (autoShown ? "  ·  自动" : "  ·  手动"));
    }

    private boolean readCharging() {
        Intent b = null;
        try {
            b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable ignored) {
        }
        if (b == null) return false;
        int plugged = b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
        return plugged != 0;
    }

    @Override
    protected void onStart() {
        super.onStart();
        view.postDelayed(watchdog, 5000L);
    }

    @Override
    protected void onStop() {
        if (view != null) {
            view.removeCallbacks(watchdog);
        }
        super.onStop();
    }
}
