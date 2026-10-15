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

    private TextView vService;
    private TextView vCharging;
    private TextView vAngle;
    private TextView vOverlay;
    private TextView vDisplays;
    private TextView vTrigger;
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
        buildStatusSection();
        buildTip();
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

    @Override
    protected void onDestroy() {
        if (posture != null) posture.stop();
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

        root.addView(radioRow(
                getString(R.string.item_clock_style),
                new String[]{getString(R.string.style_analog), getString(R.string.style_digital)},
                Prefs.clockStyle(this),
                new OnPick() {
                    @Override
                    public void onPick(int index) {
                        Prefs.setClockStyle(SettingsActivity.this, index);
                    }
                }));

        root.addView(sliderRow(getString(R.string.item_brightness),
                1, 100, Prefs.brightness(this), new OnValue() {
                    @Override
                    public void onValue(int value) {
                        Prefs.setBrightness(SettingsActivity.this, value);
                    }
                }));

        root.addView(switchRow(getString(R.string.item_show_seconds), null,
                Prefs.showSeconds(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        Prefs.setShowSeconds(SettingsActivity.this, checked);
                    }
                }));

        root.addView(switchRow(getString(R.string.item_use_24h), null,
                Prefs.use24h(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        Prefs.setUse24h(SettingsActivity.this, checked);
                    }
                }));

        root.addView(switchRow(getString(R.string.item_show_lunar), null,
                Prefs.showLunar(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        Prefs.setShowLunar(SettingsActivity.this, checked);
                    }
                }));

        root.addView(switchRow(getString(R.string.item_show_calendar), null,
                Prefs.showCalendar(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        Prefs.setShowCalendar(SettingsActivity.this, checked);
                    }
                }));

        root.addView(switchRow(getString(R.string.item_show_status), null,
                Prefs.showStatusLine(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        Prefs.setShowStatusLine(SettingsActivity.this, checked);
                    }
                }));

        root.addView(switchRow(getString(R.string.item_burn_in), getString(R.string.item_burn_in_desc),
                Prefs.burnIn(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        Prefs.setBurnIn(SettingsActivity.this, checked);
                    }
                }));

        root.addView(switchRow(getString(R.string.item_keep_on), null,
                Prefs.keepScreenOn(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        Prefs.setKeepScreenOn(SettingsActivity.this, checked);
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

        root.addView(switchRow(getString(R.string.item_hide_on_tap), null,
                Prefs.hideOnTap(this), new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        Prefs.setHideOnTap(SettingsActivity.this, checked);
                    }
                }));
    }

    private void buildStatusSection() {
        root.addView(section(getString(R.string.sec_status)));

        vService = addStatusRow(getString(R.string.status_service));
        vCharging = addStatusRow(getString(R.string.status_charging));
        vAngle = addStatusRow(getString(R.string.status_angle));
        vOverlay = addStatusRow(getString(R.string.status_overlay));
        vDisplays = addStatusRow("显示器");
        vTrigger = addStatusRow(getString(R.string.status_trigger));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);

        Button test = new Button(this);
        test.setText(R.string.btn_test_overlay);
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AutoLaunchService.sendCommand(SettingsActivity.this, AutoLaunchService.ACTION_SHOW);
            }
        });
        Button hide = new Button(this);
        hide.setText(R.string.btn_hide_overlay);
        hide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AutoLaunchService.sendCommand(SettingsActivity.this, AutoLaunchService.ACTION_HIDE);
            }
        });
        row.addView(test, weight());
        row.addView(hide, weight());
        root.addView(row, matchWrap());

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button start = new Button(this);
        start.setText(R.string.btn_start_service);
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AutoLaunchService.start(SettingsActivity.this);
            }
        });
        Button stop = new Button(this);
        stop.setText(R.string.btn_stop_service);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AutoLaunchService.stop(SettingsActivity.this);
            }
        });
        row2.addView(start, weight());
        row2.addView(stop, weight());
        root.addView(row2, matchWrap());
    }

    private void buildTip() {
        TextView t = new TextView(this);
        t.setText(R.string.tip_body);
        t.setTextColor(C_DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(0, dp(20), 0, 0);
        t.setLineSpacing(dp(4), 1f);
        root.addView(t, matchWrap());
    }

    // ---------------- 状态刷新 ----------------

    private void refreshStatus() {
        if (vService != null) {
            boolean running = AutoLaunchService.isRunning;
            set(vService, getString(running ? R.string.status_service_on : R.string.status_service_off),
                    running ? C_OK : C_BAD);
        }
        if (vCharging != null) {
            boolean charging = readCharging();
            set(vCharging, getString(charging ? R.string.status_charging_on : R.string.status_charging_off),
                    charging ? C_OK : C_DIM);
        }
        if (vAngle != null) {
            if (posture == null || !posture.available()) {
                set(vAngle, getString(R.string.status_angle_none), C_DIM);
            } else {
                int r = posture.lastRotation();
                if (r < 0) {
                    set(vAngle, getString(R.string.status_angle_reading), C_DIM);
                } else {
                    boolean landscape = (r == Surface.ROTATION_90 || r == Surface.ROTATION_270);
                    set(vAngle, getString(landscape ? R.string.status_landscape : R.string.status_portrait),
                            landscape ? C_ACCENT : C_DIM);
                }
            }
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
        if (vOverlay != null) {
            if (AutoLaunchService.overlayShowing) {
                set(vOverlay, getString(R.string.status_overlay_showing, AutoLaunchService.overlayDisplay), C_OK);
            } else {
                set(vOverlay, getString(R.string.status_overlay_hidden), C_DIM);
            }
        }
        if (vDisplays != null) {
            set(vDisplays, OverlayClock.describeDisplays(this), C_DIM);
        }
        if (vTrigger != null) {
            long t = Prefs.lastTrigger(this);
            if (t <= 0) {
                set(vTrigger, getString(R.string.status_trigger_never), C_DIM);
            } else {
                String when = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(t));
                String why = Prefs.lastTriggerReason(this);
                set(vTrigger, TextUtils.isEmpty(why) ? when : when + "  (" + why + ")", C_DIM);
            }
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

    private TextView addStatusRow(String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(0, dp(6), 0, dp(6));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(C_DIM);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        box.addView(l, weight());

        TextView v = new TextView(this);
        v.setTextColor(C_DIM);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setGravity(Gravity.END);
        box.addView(v, weight());
        root.addView(box, matchWrap());
        return v;
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
