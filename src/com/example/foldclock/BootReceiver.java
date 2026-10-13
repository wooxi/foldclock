package com.example.foldclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * v1.3.7 缺的就是这个：没有开机接收器，重启之后前台服务永远不会自己回来，
 * 「充电自动弹出」自然就废了。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "FoldClock/Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        boolean relevant = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!relevant) return;

        if (!Prefs.autoLaunch(context)) {
            Log.i(TAG, action + " 收到，但自动弹出没开，跳过");
            return;
        }
        Log.i(TAG, action + " 收到，拉起常驻服务");
        AutoLaunchService.start(context);
    }
}
