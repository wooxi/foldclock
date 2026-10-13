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

/**
 * 铰链角度监听。
 *
 * 这里有个很容易踩的坑：TYPE_HINGE_ANGLE 不是 wake-up 传感器。合盖以后屏幕熄灭、
 * SoC 进入挂起，回调不会把进程唤醒——所以「等合盖事件」这种写法在真机上经常哑火。
 * 正确做法是：被别的事件（插电广播）唤醒之后，主动去要一次当前角度。
 * request() 就是干这个的：短暂切到 FASTEST 采样，拿到第一帧或超时即回调。
 */
public final class HingeMonitor implements SensorEventListener {

    public interface Callback {
        /** angle < 0 表示读不到（没有传感器 / 超时且无历史值）。 */
        void onAngle(float angle);
    }

    private static final String TAG = "FoldClock/Hinge";

    /** 历史角度超过这个时间就不敢直接用了。 */
    private static final long FRESH_MS = 20000L;

    private final SensorManager sm;
    private final Sensor sensor;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float lastAngle = -1f;
    private long lastAt = 0L;

    private Callback pending;
    private Runnable pendingTimeout;

    public HingeMonitor(Context c) {
        sm = (SensorManager) c.getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        Sensor s = null;
        if (sm != null) {
            s = sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        }
        sensor = s;
    }

    public boolean available() {
        return sensor != null;
    }

    public float lastAngle() {
        return lastAngle;
    }

    /** 常驻低频率监听；拿不到也不影响 request()。 */
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
        if (sm != null && sensor != null) {
            try {
                sm.unregisterListener(this);
            } catch (Throwable t) {
                Log.w(TAG, "unregisterListener failed", t);
            }
        }
    }

    /**
     * 取一次「当前」角度。已有 20 秒内的读数就直接回调，否则开一段高频采样，
     * 最多等 timeoutMs 毫秒。
     */
    public void request(long timeoutMs, final Callback cb) {
        if (cb == null) return;
        if (sm == null || sensor == null) {
            cb.onAngle(-1f);
            return;
        }
        if (lastAngle >= 0f && SystemClock.elapsedRealtime() - lastAt < FRESH_MS) {
            cb.onAngle(lastAngle);
            return;
        }
        cancelPending();
        try {
            sm.unregisterListener(this);
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST);
        } catch (Throwable t) {
            Log.w(TAG, "burst register failed", t);
            cb.onAngle(lastAngle >= 0f ? lastAngle : -1f);
            return;
        }
        pending = cb;
        pendingTimeout = new Runnable() {
            @Override
            public void run() {
                finish(lastAngle >= 0f ? lastAngle : -1f);
            }
        };
        handler.postDelayed(pendingTimeout, timeoutMs);
    }

    private void finish(float angle) {
        if (pendingTimeout != null) {
            handler.removeCallbacks(pendingTimeout);
            pendingTimeout = null;
        }
        Callback cb = pending;
        pending = null;
        // 回到常驻低频监听
        if (sm != null && sensor != null) {
            try {
                sm.unregisterListener(this);
                sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
            } catch (Throwable t) {
                Log.w(TAG, "restore listener failed", t);
            }
        }
        if (cb != null) {
            cb.onAngle(angle);
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
        if (event.sensor.getType() != Sensor.TYPE_HINGE_ANGLE) return;
        if (event.values == null || event.values.length == 0) return;
        lastAngle = event.values[0];
        lastAt = SystemClock.elapsedRealtime();
        if (pending != null) {
            finish(lastAngle);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 不关心
    }
}
