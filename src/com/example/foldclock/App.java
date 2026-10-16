package com.example.foldclock;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

public class App extends Application {

    /** 前台服务用的通知渠道；重要度压到最低，尽量不打扰。 */
    public static final String CHANNEL_ID = "foldclock";

    /**
     * 锁屏时用的渠道：必须 IMPORTANCE_HIGH，否则全屏通知（full-screen intent）
     * 不会把界面拉起来 —— 这是系统闹钟在锁屏上弹出界面的标准做法。
     * 单独一个渠道，免得把常驻通知也变得吵。
     */
    public static final String CHANNEL_FSI = "foldclock_lockscreen";

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_MIN);
            ch.setDescription(getString(R.string.notif_channel_desc));
            ch.setShowBadge(false);
            ch.enableVibration(false);
            ch.enableLights(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CHANNEL_FSI) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_FSI,
                    getString(R.string.notif_channel_lock_name),
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription(getString(R.string.notif_channel_lock_desc));
            ch.setShowBadge(false);
            ch.enableVibration(false);
            ch.enableLights(false);
            ch.setSound(null, null);   // 高重要度但不发声：只为拿到全屏通知的能力
            nm.createNotificationChannel(ch);
        }
    }
}
