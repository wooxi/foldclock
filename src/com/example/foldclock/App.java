package com.example.foldclock;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

public class App extends Application {

    /** 前台服务用的通知渠道；重要度压到最低，尽量不打扰。 */
    public static final String CHANNEL_ID = "foldclock";

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
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
}
