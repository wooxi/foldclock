package com.example.foldclock;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 设置 + 自检。整个界面代码搭建，不依赖 XML 布局。
 * 重点是「自检」区：把每一项权限的真实状态、充电状态、手机姿态、悬浮窗所在显示器
 * 都实时显示出来，这样「为什么没自动弹」一眼就能定位。
 */
public class SettingsActivity extends Activity {

    private static final int C_FG = 0xFFEDEDED;
    private static final int C_DIM = 0xFF8A8A8A;
    private static final int C_ACCENT = 0xFFFF4D3D;
    private static final int C_OK = 0xFF4CC38A;
    private static final int C_BAD = 0xFFE5484D;

    private LinearLayout root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PostureMonitor posture;

    /** 返回键那条路已经发过 RESUME 就不再在 onDestroy 里发一次 */
    private boolean resumeSent;

    private TextView vDiag;
    private TextView vVerdict;
    private TextView vOverlayPerm;
    private TextView vNotiPerm;
    private TextView vBattery;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        posture = new PostureMonitor(this);
        posture.start();

        // 声明本 Activity 自己的开/关转场为"无动画"。
        // 主题里的 windowAnimationStyle、启动方的 ActivityOptions 在某些 ROM（含 HyperOS）
        // 上会被忽略，而这个 API 是 Activity 自己声明、优先级更高。
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            try {
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
            } catch (Throwable t) {
                android.util.Log.w("FoldClock/Diag", "overrideActivityTransition failed", t);
            }
        }

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.BLACK);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(24), pad, dp(40));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);

        buildTitle();
        buildAutoSection();
        buildPermissionSection();
        buildDisplaySection();
        buildBehaviorSection();
        buildAboutSection();
        buildDiag();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Android 13+ 通知要运行时授权：没有通知权限，常驻通知不显示，
        // 锁屏用的「全屏通知」也会被系统直接丢掉 → 锁屏场景彻底失效。
        // 主动申请一次（系统只会弹一次；拒绝后仍可从设置页里的权限入口去开）。
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                            new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 0x91);
                }
            } catch (Throwable ignored) {
            }
        }
        // 悬浮窗永远画在所有 Activity 之上：设置页一出现就得把时钟收起来。
        if (AutoLaunchService.isRunning) {
            AutoLaunchService.onSettingsShown(this);
        }
    }

    @Override
    protected void onStop() {
        // 只是不再可见（可能是跳去系统权限页，也可能按了 Home）。
        // 服务那边给几秒宽限；若真的离开了就让位状态自动失效。
        if (AutoLaunchService.isRunning) {
            AutoLaunchService.onSettingsHidden(this);
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    /**
     * 返回键：**先把时钟显示出来，再关闭设置页**。
     *
     * 如果不这样做，顺序会变成「设置页关掉 → 露出系统页面 → 几百毫秒后悬浮窗才挂上」，
     * 中间那一下系统页面闪烁就是用户看到的"突兀"。提前挂上悬浮窗，设置页的退场动画
     * 整段都在时钟后面，用户感知就是"按返回 → 时钟"。
     */
    @Override
    public void onBackPressed() {
        if (AutoLaunchService.isRunning && !resumeSent) {
            resumeSent = true;
            AutoLaunchService.onSettingsClosed(this);
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (posture != null) posture.stop();
        // 彻底离开（兜底路径）：解除让位 + 重新评估。
        // resumeSent：返回键那条路已经发过就不再发，免得日志里出现两次。
        if (AutoLaunchService.isRunning && !resumeSent) {
            AutoLaunchService.onSettingsClosed(this);
        }
        super.onDestroy();
    }

    // ---------------- 界面搭建 ----------------

    private void buildTitle() {
        TextView t = new TextView(this);
        t.setText(R.string.settings_title);
        t.setTextColor(C_FG);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(t, matchWrap());
        root.addView(space(dp(8)));
    }

    /**
     * 一屏自诊：等宽、高密度、机器可读，截图这一块就够定位；底部单独一行给结论码 + 处置。
     * 字段含义见 Diag 类注释（全部来自实机取证）。
     */
    /**
     * 关于：只显示版本 + 一个"打开发布页"按钮。
     * 刻意不做 App 内检查更新 —— 那需要 INTERNET 权限，会推翻"系统层面无法联网"这条。
     * 浏览器自己去联网，本 App 依旧零网络权限。
     */
    private void buildAboutSection() {
        root.addView(section(getString(R.string.sec_about)));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, dp(10), 0, dp(10));

        TextView t = new TextView(this);
        t.setText(getString(R.string.about_version, versionText()));
        t.setTextColor(C_FG);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        box.addView(t, weight());

        Button b = new Button(this);
        b.setText(R.string.btn_open_release);
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(getString(R.string.release_url))));
                } catch (Throwable t) {
                    android.util.Log.w("FoldClock/Diag", "open release page failed", t);
                }
            }
        });
        box.addView(b);
        root.addView(box, matchWrap());
    }

    /** "1.4.3(19)" */
    private String versionText() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            long vc = android.os.Build.VERSION.SDK_INT >= 28
                    ? pi.getLongVersionCode() : pi.versionCode;
            return pi.versionName + "(" + vc + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    private void buildDiag() {
        root.addView(section("诊断快照 · 截这一块"));

        vDiag = new TextView(this);
        vDiag.setTextColor(C_FG);
        vDiag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        vDiag.setTypeface(Typeface.MONOSPACE);
        vDiag.setLineSpacing(0f, 1.05f);
        vDiag.setTextIsSelectable(true);
        vDiag.setPadding(dp(8), dp(8), dp(8), dp(8));
        vDiag.setBackgroundColor(0xFF101010);
        root.addView(vDiag, matchWrap());

        vVerdict = new TextView(this);
        vVerdict.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        vVerdict.setTypeface(Typeface.DEFAULT_BOLD);
        vVerdict.setPadding(dp(8), dp(6), dp(8), dp(6));
        root.addView(vVerdict, matchWrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = new Button(this);
        copy.setText("复制诊断信息");
        copy.setAllCaps(false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                            "foldclock-diag", Diag.fullText(SettingsActivity.this, posture)));
                } catch (Throwable t) {
                    android.util.Log.w("FoldClock/Diag", "copy failed", t);
                }
            }
        });
        Button share = new Button(this);
        share.setText("分享诊断信息");
        share.setAllCaps(false);
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    android.content.Intent i = new android.content.Intent(
                            android.content.Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(android.content.Intent.EXTRA_SUBJECT, "FoldClock 自检");
                    i.putExtra(android.content.Intent.EXTRA_TEXT,
                            Diag.fullText(SettingsActivity.this, posture));
                    startActivity(android.content.Intent.createChooser(i, "分享诊断信息"));
                } catch (Throwable t) {
                    android.util.Log.w("FoldClock/Diag", "share failed", t);
                }
            }
        });
        row.addView(copy, weight());
        row.addView(share, weight());
        root.addView(row, matchWrap());
    }

    private void buildAutoSection() {
        root.addView(section(getString(R.string.sec_auto)));

        root.addView(switchRow(
                getString(R.string.item_auto_launch),
                getString(R.string.item_auto_launch_desc),
                Prefs.autoLaunch(this),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        Prefs.setAutoLaunch(SettingsActivity.this, checked);
                        if (checked) {
                            AutoLaunchService.start(SettingsActivity.this);
                            // 服务可能是刚被拉起来的（内部状态重置了），这里再声明一次
                            // "设置页在前台"，免得时钟立刻弹出来盖住当前页面。
                            AutoLaunchService.onSettingsShown(SettingsActivity.this);
                        } else {
                            AutoLaunchService.stop(SettingsActivity.this);
                        }
                    }
                }));

        root.addView(radioRow(
                getString(R.string.item_show_mode),
                new String[]{
                        getString(R.string.mode_auto),
                        getString(R.string.mode_overlay),
                        getString(R.string.mode_activity)
                },
                Prefs.showMode(this),
                new OnPick() {
                    @Override
                    public void onPick(int index) {
                        Prefs.setShowMode(SettingsActivity.this, index);
                    }
                }));

        root.addView(switchRow(
                getString(R.string.item_ignore_hinge),
                getString(R.string.item_ignore_hinge_desc),
                Prefs.ignoreHinge(this),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        Prefs.setIgnoreHinge(SettingsActivity.this, checked);
                    }
                }));
    }

    private void buildPermissionSection() {
        root.addView(section(getString(R.string.sec_perm)));

        vOverlayPerm = new TextView(this);
        addPermRow(getString(R.string.perm_overlay), getString(R.string.perm_overlay_desc),
                vOverlayPerm, getString(R.string.btn_grant), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Perms.requestOverlay(SettingsActivity.this);
                    }
                });

        vNotiPerm = new TextView(this);
        addPermRow(getString(R.string.perm_notify), getString(R.string.perm_notify_desc),
                vNotiPerm, getString(R.string.btn_grant), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Perms.requestNotifications(SettingsActivity.this);
                    }
                });

        vBattery = new TextView(this);
        addPermRow(getString(R.string.perm_battery), getString(R.string.perm_battery_desc),
                vBattery, getString(R.string.btn_settings), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Perms.requestBatteryUnrestricted(SettingsActivity.this);
                    }
                });

        if (Perms.isMiuiFamily()) {
            addPermRow(getString(R.string.perm_boot), getString(R.string.perm_boot_desc),
                    null, getString(R.string.btn_settings), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Perms.openAutoStart(SettingsActivity.this);
                        }
                    });
            addPermRow(getString(R.string.perm_bg_popup), getString(R.string.perm_bg_popup_desc),
                    null, getString(R.string.btn_settings), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Perms.openBgPopup(SettingsActivity.this);
                        }
                    });
        }

        addPermRow(getString(R.string.perm_dream), getString(R.string.perm_dream_desc),
                null, getString(R.string.btn_settings), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Perms.openDreamPicker(SettingsActivity.this);
                    }
                });
    }

    private void buildDisplaySection() {
        root.addView(section(getString(R.string.sec_display)));

        root.addView(sliderRow(getString(R.string.item_brightness),
                1, 100, Prefs.brightness(this), new OnValue() {
                    @Override
                    public void onValue(int value) {
                        Prefs.setBrightness(SettingsActivity.this, value);
                    }
                }));
    }

    private void buildBehaviorSection() {
        root.addView(section(getString(R.string.sec_behavior)));

        root.addView(switchRow(getString(R.string.item_exit_on_unfold), null,
                Prefs.exitOnUnfold(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        Prefs.setExitOnUnfold(SettingsActivity.this, checked);
                    }
                }));

        root.addView(switchRow(getString(R.string.item_exit_on_unplug), null,
                Prefs.exitOnUnplug(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        Prefs.setExitOnUnplug(SettingsActivity.this, checked);
                    }
                }));
    }

    // ---------------- 状态刷新 ----------------

    private void refreshStatus() {
        if (vDiag != null) {
            vDiag.setText(Diag.snapshot(this, posture));
        }
        if (vVerdict != null) {
            String v = Diag.verdict(this);
            boolean ok = Diag.OK.equals(v);
            vVerdict.setText(v + "  " + Diag.hint(this, v));
            vVerdict.setTextColor(ok ? C_OK : C_BAD);
        }
        if (vOverlayPerm != null) {
            boolean ok = Perms.canDrawOverlays(this);
            set(vOverlayPerm, getString(ok ? R.string.state_granted : R.string.state_denied),
                    ok ? C_OK : C_BAD);
        }
        if (vNotiPerm != null) {
            boolean ok = Perms.notificationsEnabled(this);
            set(vNotiPerm, getString(ok ? R.string.state_granted : R.string.state_denied),
                    ok ? C_OK : C_BAD);
        }
        if (vBattery != null) {
            boolean ok = Perms.ignoringBatteryOptimizations(this);
            set(vBattery, getString(ok ? R.string.state_granted : R.string.state_denied),
                    ok ? C_OK : C_BAD);
        }
    }

    private boolean readCharging() {
        android.content.Intent b = null;
        try {
            b = registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable ignored) {
        }
        if (b == null) return false;
        int plugged = b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
        return plugged != 0;
    }

    private void set(TextView v, CharSequence text, int color) {
        v.setText(text);
        v.setTextColor(color);
    }

    // ---------------- 小工具 ----------------

    private interface OnPick {
        void onPick(int index);
    }

    private interface OnValue {
        void onValue(int value);
    }

    private View section(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(C_ACCENT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(22), 0, dp(6));
        return t;
    }

    private View switchRow(String title, String summary, boolean checked,
                           CompoundButton.OnCheckedChangeListener l) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, dp(10), 0, dp(10));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(C_FG);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        texts.addView(t, matchWrap());
        if (!TextUtils.isEmpty(summary)) {
            TextView s = new TextView(this);
            s.setText(summary);
            s.setTextColor(C_DIM);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            texts.addView(s, matchWrap());
        }
        box.addView(texts, weight());

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        box.addView(sw);
        return box;
    }

    private View radioRow(String title, String[] options, int checked, final OnPick pick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(10));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(C_FG);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        box.addView(t, matchWrap());

        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.VERTICAL);
        for (int i = 0; i < options.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(options[i]);
            rb.setTextColor(C_DIM);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            rb.setId(1000 + i);
            g.addView(rb, matchWrap());
        }
        g.check(1000 + Math.max(0, Math.min(options.length - 1, checked)));
        g.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int id) {
                if (pick != null) pick.onPick(id - 1000);
            }
        });
        box.addView(g, matchWrap());
        return box;
    }

    private View sliderRow(String title, final int min, final int max, int value, final OnValue cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(10));

        final TextView t = new TextView(this);
        t.setTextColor(C_FG);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setText(title + "  " + value + "%");
        box.addView(t, matchWrap());

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, value - min)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = min + progress;
                t.setText(title + "  " + v + "%");
                if (fromUser && cb != null) cb.onValue(v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        box.addView(sb, matchWrap());
        return box;
    }

    private void addPermRow(String title, String summary, TextView stateOut,
                            String btnText, View.OnClickListener l) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, dp(10), 0, dp(10));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(C_FG);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        texts.addView(t, matchWrap());

        if (stateOut != null) {
            stateOut.setTextColor(C_DIM);
            stateOut.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            texts.addView(stateOut, matchWrap());
        }
        if (!TextUtils.isEmpty(summary)) {
            TextView s = new TextView(this);
            s.setText(summary);
            s.setTextColor(C_DIM);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            texts.addView(s, matchWrap());
        }
        box.addView(texts, weight());

        Button b = new Button(this);
        b.setText(btnText);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        box.addView(b);
        root.addView(box, matchWrap());
    }

    private View space(int h) {
        View v = new View(this);
        v.setLayoutParams(new ViewGroup.LayoutParams(1, h));
        return v;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
