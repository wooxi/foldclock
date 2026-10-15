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

    private StandByView view;
    private PostureMonitor posture;
    private boolean autoShown;
    private boolean charging;

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
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        autoShown = getIntent() != null && getIntent().getBooleanExtra(EXTRA_AUTO, false);

        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setShowWhenLocked(true);
        setTurnScreenOn(true);

        applyOrientation();

        view = new StandByView(this, new StandByView.OnTapListener() {
            @Override
            public void onTap() {
                if (autoShown) {
                    finish();
                }
            }
        });
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
        }
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
        if (posture != null) {
            posture.stop();
            posture = null;
        }
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
