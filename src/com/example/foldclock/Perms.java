package com.example.foldclock;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

/**
 * 权限相关的查询与跳转。全部用平台 API，不依赖 AndroidX。
 * 说明：能不能「后台弹出界面」这种事系统不提供查询接口，只能把用户送到对应的设置页。
 */
public final class Perms {

    private static final String TAG = "FoldClock/Perms";

    private Perms() {
    }

    public static boolean canDrawOverlays(Context c) {
        return Settings.canDrawOverlays(c);
    }

    public static void requestOverlay(Activity a) {
        if (canDrawOverlays(a)) return;
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Throwable t) {
            startSafe(a, appDetails(a));
        }
    }

    public static boolean notificationsEnabled(Context c) {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled();
    }

    public static void requestNotifications(Activity a) {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        i.putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName());
        startSafe(a, i, appDetails(a));
    }

    public static boolean ignoringBatteryOptimizations(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    public static void requestBatteryUnrestricted(Activity a) {
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        i.setData(Uri.parse("package:" + a.getPackageName()));
        startSafe(a, i, appDetails(a));
    }

    public static Intent appDetails(Context c) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + c.getPackageName()));
        return i;
    }

    /** MIUI / HyperOS：自启动管理 */
    public static void openAutoStart(Activity a) {
        Intent i = new Intent();
        i.setComponent(new ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"));
        startSafe(a, i, appDetails(a));
    }

    /** MIUI / HyperOS：应用权限编辑（含「后台弹出界面」） */
    public static void openBgPopup(Activity a) {
        Intent i = new Intent("miui.intent.action.APP_PERM_EDITOR");
        i.setComponent(new ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity"));
        i.putExtra("extra_pkgname", a.getPackageName());
        Intent fallback = new Intent("miui.intent.action.APP_PERM_EDITOR");
        fallback.setPackage("com.miui.securitycenter");
        fallback.putExtra("extra_pkgname", a.getPackageName());
        startSafe(a, i, fallback, appDetails(a));
    }

    /** 系统屏保（Dream）选择页 */
    public static void openDreamPicker(Activity a) {
        Intent i = new Intent("android.settings.DREAM_SETTINGS");
        startSafe(a, i, appDetails(a));
    }

    /** 依次尝试若干 Intent，全失败就退回应用详情页。 */
    public static void startSafe(Activity a, Intent... intents) {
        for (Intent i : intents) {
            if (i == null) continue;
            try {
                a.startActivity(i);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "start failed: " + i, t);
            }
        }
    }

    /** 机型提示：这台机器是不是小米折叠屏（决定要不要显示 MIUI 专属项）。 */
    public static boolean isMiuiFamily() {
        String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase();
        String manu = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        return brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")
                || manu.contains("xiaomi");
    }
}
