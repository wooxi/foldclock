package com.example.foldclock;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

/**
 * 手机姿态监听（重力方向）—— 「外屏时钟只在手机横着放的时候出现」这条规则的唯一依据。
 *
 * 为什么不能用 Display.getRotation()：
 * 外屏的方向是「谁在前台谁说了算」——桌面、设置这些竖屏锁定的界面在前台时，
 * 外屏永远停在 1168x1712（ROTATION_0），就算手机正立在桌上也不会转
 * （实测日志：display 0 size=1168x1712 rotation=0 -> 竖；而同一时刻重力读数是横的）。
 *
 * 符号约定跟系统一致（同 AOSP WindowOrientationListener 那套）：
 *   |gy| >= |gx| → 竖：gy > 0 正竖 ROTATION_0 / gy < 0 倒竖 ROTATION_180
 *   否则        → 横：gx > 0 ROTATION_90 / gx < 0 ROTATION_270
 * 平放（|gz| 占绝对主导）时横竖没有意义，算「判不出来」(-1) —— 实测两个目标姿态
 * （A 字形 / L 字形立在桌上）都能读出明确的横向角度，而平躺会读成 -1，正好用来区分。
 *
 * 采样方式：常驻低频监听 + 姿态一变就回调（带 400ms 去抖）。合盖后 SoC 会挂起、
 * 光等传感器事件叫不醒进程，所以另有 request() 供主动取样。
 */
public final class PostureMonitor implements SensorEventListener {

    public interface Callback {
        /** Surface.ROTATION_0/90/180/270；-1 = 读不到或方向无意义（平放）。 */
        void onRotation(int rotation);
    }

    /** 姿态变化回调 —— 服务用它做到「手机一转到竖屏/平放就立刻收掉时钟」。 */
    public interface OnChangeListener {
        void onPostureChanged(int rotation);
    }

    private static final String TAG = "FoldClock/Posture";

    /** 缓存超过这个时间就不敢用了（request 用） */
    private static final long FRESH_MS = 20000L;
    /**
     * |gz| 超过它且最大 → 认为手机是平放的。
     * 9.2 ≈ 离水平面 20° 以内才算平放（原来 8.0 相当于 35°，太宽，
     * 用户 L 形摆得稍微躺一点就被判成平放、时钟直接退出 —— 实测反馈后收紧）。
     */
    private static final float FLAT_Z = 9.2f;
    /** 参与判定的那个轴至少要这么大力（m/s²），否则当噪声 */
    private static final float UPRIGHT_MIN = 3.0f;
    /** 姿态变化的去抖时间：新姿态要稳定这么久才通知出去 */
    private static final long CHANGE_DEBOUNCE_MS = 400L;

    private final SensorManager sm;
    private final Sensor sensor;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int lastRotation = -1;
    private long lastAt = 0L;
    /** 当前读数是不是「平放」——平放时横竖没有意义 */
    private boolean flat = true;

    private Callback pending;
    private Runnable pendingTimeout;

    private OnChangeListener changeListener;
    private int notifiedRotation = -1;
    private int pendingRotation = -1;
    /** 最近一帧原始重力，只用来打日志、方便按实机数据调阈值 */
    private float gx, gy, gz;
    private final Runnable changeNotifier = new Runnable() {
        @Override
        public void run() {
            if (pendingRotation == notifiedRotation) return;
            notifiedRotation = pendingRotation;
            Log.i(TAG, "姿态变化 -> " + notifiedRotation
                    + " (g=" + fmt(gx) + "," + fmt(gy) + "," + fmt(gz) + ")");
            if (changeListener != null) {
                changeListener.onPostureChanged(notifiedRotation);
            }
        }
    };

    public PostureMonitor(Context c) {
        sm = (SensorManager) c.getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        Sensor s = null;
        if (sm != null) {
            s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        sensor = s;
    }

    public boolean available() {
        return sensor != null;
    }

    /** 最近一次读到的方向；-1 = 还没有可用读数，或最近一次是平放。 */
    public int lastRotation() {
        return lastRotation;
    }

    public boolean hasFresh() {
        return lastRotation >= 0 && SystemClock.elapsedRealtime() - lastAt < FRESH_MS;
    }

    /**
     * 当前能确定的姿态（Surface.ROTATION_0/90/180/270）。
     *
     * 平放一律返回 -1：外屏时钟只在手机横着放（A 形 / L 形立在桌上）时出现，
     * 平躺和竖着拿都不显示 —— 所以这里刻意不做「沿用上一次姿态」的兜底。
     */
    public int currentRotation() {
        if (flat) return -1;
        return lastRotation;
    }

    public void setOnChangeListener(OnChangeListener l) {
        changeListener = l;
    }

    /** 常驻低频监听；拿不到也不影响 request()。 */
    public void start() {
        if (sm == null || sensor == null) return;
        try {
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Throwable t) {
            Log.w(TAG, "registerListener failed", t);
        }
    }

    public void stop() {
        cancelPending();
        handler.removeCallbacks(changeNotifier);
        if (sm != null && sensor != null) {
            try {
                sm.unregisterListener(this);
            } catch (Throwable t) {
                Log.w(TAG, "unregisterListener failed", t);
            }
        }
    }

    /** 取一次「当前」姿态：有 20 秒内的读数直接回调，否则开一段高频采样，最多等 timeoutMs。 */
    public void request(long timeoutMs, final Callback cb) {
        if (cb == null) return;
        if (sm == null || sensor == null) {
            cb.onRotation(-1);
            return;
        }
        if (hasFresh()) {
            cb.onRotation(currentRotation());
            return;
        }
        cancelPending();
        try {
            sm.unregisterListener(this);
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST);
        } catch (Throwable t) {
            Log.w(TAG, "burst register failed", t);
            cb.onRotation(currentRotation());
            return;
        }
        pending = cb;
        pendingTimeout = new Runnable() {
            @Override
            public void run() {
                finish(currentRotation());
            }
        };
        handler.postDelayed(pendingTimeout, timeoutMs);
    }

    private void finish(int rotation) {
        if (pendingTimeout != null) {
            handler.removeCallbacks(pendingTimeout);
            pendingTimeout = null;
        }
        Callback cb = pending;
        pending = null;
        if (sm != null && sensor != null) {
            try {
                sm.unregisterListener(this);
                sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
            } catch (Throwable t) {
                Log.w(TAG, "restore listener failed", t);
            }
        }
        if (cb != null) {
            cb.onRotation(rotation);
        }
    }

    private void cancelPending() {
        if (pendingTimeout != null) {
            handler.removeCallbacks(pendingTimeout);
            pendingTimeout = null;
        }
        pending = null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null) return;
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        if (event.values == null || event.values.length < 3) return;

        gx = event.values[0];
        gy = event.values[1];
        gz = event.values[2];
        int r = classify(gx, gy, gz);
        if (r < 0) {
            flat = true;
        } else {
            lastRotation = r;
            lastAt = SystemClock.elapsedRealtime();
            flat = false;
        }
        if (pending != null) {
            finish(currentRotation());
            return;
        }
        notifyIfChanged(currentRotation());
    }

    /** 姿态变了才通知，并且要稳定 CHANGE_DEBOUNCE_MS 才发出去（防抖）。 */
    private void notifyIfChanged(int rotation) {
        if (rotation == pendingRotation) return;
        pendingRotation = rotation;
        handler.removeCallbacks(changeNotifier);
        handler.postDelayed(changeNotifier, CHANGE_DEBOUNCE_MS);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 不关心
    }

    private static String fmt(float v) {
        return String.valueOf(Math.round(v * 10) / 10f);
    }

    /** 重力向量 → 设备方向；-1 = 平放/噪声，方向无意义。 */
    private static int classify(float x, float y, float z) {
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        float az = Math.abs(z);
        if (az > FLAT_Z && az > ax && az > ay) {
            return -1;              // 平放
        }
        if (ay >= ax) {
            if (ay < UPRIGHT_MIN) return -1;
            return y > 0 ? Surface.ROTATION_0 : Surface.ROTATION_180;
        }
        if (ax < UPRIGHT_MIN) return -1;
        return x > 0 ? Surface.ROTATION_90 : Surface.ROTATION_270;
    }
}
