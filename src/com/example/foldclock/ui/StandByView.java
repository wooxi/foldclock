package com.example.foldclock.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.foldclock.Prefs;
import com.example.foldclock.SettingsActivity;

/**
 * 整块外屏画面：时钟 + 日历。
 *
 * **只有横屏一套排版**：左边时钟、右边日历。没有竖屏排版，也不做「屏幕竖就换一套」。
 *
 * 屏幕被前台 App 锁成竖屏怎么办（桌面就是竖屏锁定的，实测外屏永远是 1168x1712）？
 * 用「舞台」转过去：舞台按横屏尺寸（宽高对调 1712x1168）居中摆好，再整体转 90°/270°，
 * 转完正好铺满竖着的屏幕 —— 看到的仍然是横屏时钟，不需要第二套排版。
 * 往哪边转由宿主的 {@link #setDesiredRotation(int)}（手机真实姿态）决定，不知道就默认 90°。
 *
 * 同一套 View 被三种宿主复用：Activity、悬浮窗、DreamService。
 */
public class StandByView extends FrameLayout {

    public interface OnTapListener {
        void onTap();
    }

    private static final String TAG = "FoldClock/View";

    /** 防烧屏平移周期 */
    private static final long SHIFT_MS = 15000L;
    /** 像素偏移的过渡时长：让"每 15 秒挪 1 像素"平滑过去，而不是硬跳一下 */
    private static final long SHIFT_ANIM_MS = 400L;

    /**
     * 出现动画时长：整体淡入 + 舞台 0.97→1.0 轻微放大。
     * 参考 iPhone 息屏时钟的节奏：出现要"从容"，不能一闪就出来。
     */
    public static final long ENTER_MS = 400L;
    /** 退出动画时长：整体淡出 + 舞台 1.0→0.985 轻微缩小（原来 200ms 太急） */
    public static final long EXIT_MS = 450L;

    /** 可整体旋转的舞台，时钟 / 日历 / 状态行 / 齿轮都挂在它下面 */
    private final FrameLayout stage;
    private final LinearLayout content;
    private final TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final boolean burnIn;
    private final boolean showStatus;
    private View clock;
    private View calendar;

    /** 返回键 / 侧滑手势退出。悬浮窗不在 Activity 栈里，收不到 onBackPressed，只能自己听按键。 */
    public interface OnBackListener {
        void onBack();
    }

    private OnBackListener backListener;

    public void setOnBackListener(OnBackListener l) {
        backListener = l;
        setFocusableInTouchMode(true);
        requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event != null
                && event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK
                && event.getAction() == android.view.KeyEvent.ACTION_UP
                && backListener != null) {
            backListener.onBack();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** 期望的内容方向（Surface.ROTATION_*）；-1 = 跟随屏幕方向 */
    private int desiredRotation = -1;
    private int stageW = -1;
    private int stageH = -1;
    private int stageRot = -1;
    /** 正在播退出动画：期间锁住排版，避免转屏时内容重排闪一下 */
    private boolean leaving;
    /** 出现时不做透明渐入（设置页返回场景） */
    private boolean instantEnter;

    private int shiftStep;
    private int lastW = -1;
    private int lastH = -1;

    private final Runnable shifter = new Runnable() {
        @Override
        public void run() {
            applyShift();
            handler.postDelayed(this, SHIFT_MS);
        }
    };

    public StandByView(Context c, final OnTapListener tapListener) {
        this(c, tapListener, true);
    }

    /**
     * @param showSettings 右上角是否显示齿轮入口（系统屏保里不需要）
     */
    public StandByView(Context c, final OnTapListener tapListener, boolean showSettings) {
        super(c);
        burnIn = Prefs.burnIn(c);
        showStatus = Prefs.showStatusLine(c);

        setBackgroundColor(Color.BLACK);

        stage = new FrameLayout(c);
        addView(stage, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER);
        stage.addView(content, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        clock = Prefs.clockStyle(c) == Prefs.STYLE_DIGITAL
                ? new DigitalClockView(c)
                : new AnalogClockView(c);
        content.addView(clock, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        if (Prefs.showCalendar(c)) {
            calendar = new CalendarPanelView(c);
            content.addView(calendar, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }

        status = new TextView(c);
        status.setTextColor(Color.parseColor("#C08A8A8A"));
        status.setTextSize(11f);
        status.setTypeface(Typeface.MONOSPACE);
        LayoutParams slp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.BOTTOM | Gravity.START;
        slp.leftMargin = 24;
        slp.bottomMargin = 16;
        status.setLayoutParams(slp);
        status.setVisibility(showStatus ? VISIBLE : GONE);
        stage.addView(status);

        if (showSettings) {
            stage.addView(buildGear(c));
        }

        if (Prefs.hideOnTap(c) && tapListener != null) {
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    tapListener.onTap();
                }
            });
        }
    }

    /**
     * 手机真实的朝向（Surface.ROTATION_0/90/180/270），-1 = 未知 / 跟随屏幕。
     *
     * 手机横着放而屏幕被前台 App 锁成竖屏时，宿主传 ROTATION_90/270，
     * 这里会把内容整体转过去，保证看起来是正的。
     */
    public void setDesiredRotation(int rotation) {
        if (rotation == desiredRotation) return;
        desiredRotation = rotation;
        stageW = -1;
        stageH = -1;
        stageRot = -1;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        applyStageGeometry(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
        super.onMeasure(widthSpec, heightSpec);
    }

    /** 按「想要的朝向 - 屏幕实际朝向」摆放舞台。只在真的变了的时候动，避免反复 requestLayout。 */
    private void applyStageGeometry(int w, int h) {
        if (w <= 0 || h <= 0) return;
        // ★ 正在淡出：锁住排版不动。
        //   转竖屏那一下系统会先把屏幕转过去，如果我们这时跟着新尺寸重排，
        //   屏幕上就会闪一下"竖屏里塞着的横屏时钟"。退出期间保持原样，纯淡出。
        if (leaving) return;

        int displayRot = 0;
        Display d = getDisplay();
        if (d != null) {
            displayRot = d.getRotation();
        }

        // ★★ 这里踩过坑：Surface.ROTATION_0/90/180/270 是**枚举值 0/1/2/3**，不是度数！
        // 直接拿枚举值做角度减法，差值是 1，被当成「非 90 的倍数」归零 → 舞台不转 →
        // 屏幕上就画出一个竖屏时钟。必须先 ×90 换成度数。
        int displayDeg = displayRot * 90;
        int poseDeg = desiredRotation * 90;

        // 内容要额外转多少 = 手机姿态方向 - 屏幕当前方向（实机推过：两者一致时为 0）
        int delta = 0;
        if (desiredRotation >= 0) {
            delta = ((poseDeg - displayDeg) % 360 + 360) % 360;
        } else if (w <= h) {
            delta = 90;             // 姿态未知又是竖屏：默认转 90°
        }
        if (delta % 90 != 0) delta = 0;

        // 舞台永远是「横屏那一套」的尺寸：要转 90/270 就把宽高对调
        boolean swap = (delta == 90 || delta == 270);
        int sw = swap ? h : w;
        int sh = swap ? w : h;

        if (sw == stageW && sh == stageH && delta == stageRot) return;
        stageW = sw;
        stageH = sh;
        stageRot = delta;

        LayoutParams lp = (LayoutParams) stage.getLayoutParams();
        if (lp == null) {
            lp = new LayoutParams(sw, sh);
        }
        lp.width = sw;
        lp.height = sh;
        lp.gravity = Gravity.CENTER;
        stage.setLayoutParams(lp);
        stage.setRotation(delta);

        Log.i(TAG, "stage " + sw + "x" + sh + " rot=" + delta
                + " (desired=" + desiredRotation + " display=" + displayRot + ")");
    }

    /**
     * 右上角的设置入口。
     * 原版把齿轮贴在屏幕最边缘，正好落在外屏的挖孔/圆角区里，很难点到；
     * 这里往下往左让开一点，并给足点击热区。
     */
    private View buildGear(Context c) {
        TextView gear = new TextView(c);
        gear.setText("⚙");
        gear.setTextSize(20f);
        gear.setTextColor(Color.parseColor("#66FFFFFF"));
        gear.setGravity(Gravity.CENTER);
        int size = dp(c, 52);
        LayoutParams lp = new LayoutParams(size, size);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = dp(c, 20);
        lp.rightMargin = dp(c, 20);
        gear.setLayoutParams(lp);
        gear.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "齿轮被点击 → 收起时钟并打开设置页");
                Context ctx = v.getContext();
                // ★ 通知服务"设置页要来了"，但**不要**让时钟立刻消失：
                //   服务会继续用它遮罩 SETTINGS_COVER_MS，把系统那段"设置页滑入"动画盖在后面，
                //   等设置页落定再淡出 —— 否则滑动过程中会露出底下的系统页面。
                //   万一设置页没起来，服务也会在遮罩结束后把时钟收掉，不会留下死局。
                try {
                    com.example.foldclock.AutoLaunchService.sendCommand(
                            ctx, com.example.foldclock.AutoLaunchService.ACTION_UI_ON);
                } catch (Throwable ignored) {
                }
                Intent i = new Intent(ctx, SettingsActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    // 关键：用 makeCustomAnimation(ctx, 0, 0) 显式声明"没有转场动画"。
                    // 主题里的 activityOpen* 只覆盖同任务内的 Activity 转场；
                    // 从悬浮窗（服务）用 NEW_TASK 打开设置页走的是**任务级转场**，
                    // 那一层仍然会用系统默认的滑动动画，过程中会露出底部页面。
                    android.app.ActivityOptions ao =
                            android.app.ActivityOptions.makeCustomAnimation(ctx, 0, 0);
                    if (android.os.Build.VERSION.SDK_INT >= 34) {
                        // 同时显式把自己的"后台启动界面"特权委托出去（AOSP 认可的写法），
                        // 裸 startActivity 在部分 ROM 上会被静默拦掉。
                        ao.setPendingIntentBackgroundActivityStartMode(
                                android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                                ctx, 0x51, i,
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                        | android.app.PendingIntent.FLAG_IMMUTABLE);
                        pi.send(ctx, 0, null, null, null, null, ao.toBundle());
                    } else {
                        ctx.startActivity(i, ao.toBundle());
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "打开设置页失败", t);
                }
            }
        });
        return gear;
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 出现动画：整块淡入 + 舞台轻微放大。
     *
     * 淡的是**根视图**（`setAlpha`），所以连黑底一起淡 —— 只淡内容的话，会先「啪」地
     * 盖上一层黑，再看到时钟淡出来。缩放只给舞台（时钟/日历），黑底不缩，
     * 所以边缘不会露出后面的桌面。
     *
     * 例外：{@link #setInstantEnter(boolean)} 打开时直接就位、不做渐入。
     * 从设置页返回属于这种场景 —— 渐变过程中会先露出下面的系统页面，反而突兀。
     */
    public void playEnter() {
        leaving = false;
        stage.setAlpha(1f);
        if (instantEnter) {
            setAlpha(1f);
            stage.setScaleX(1f);
            stage.setScaleY(1f);
            return;
        }
        setAlpha(0f);
        stage.setScaleX(0.97f);
        stage.setScaleY(0.97f);
        animate().alpha(1f)
                .setDuration(ENTER_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        stage.animate().scaleX(1f).scaleY(1f)
                .setDuration(ENTER_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /** true = 出现时不做透明渐入，直接就位（设置页返回场景）。 */
    public void setInstantEnter(boolean instant) {
        instantEnter = instant;
    }

    /**
     * 退出动画：整块淡出（窗口是 TRANSLUCENT，所以是真的露出下面的系统界面），
     * 退出期间锁住排版，不让转屏把内容重排一次。
     */
    public void playExit() {
        leaving = true;
        animate().alpha(0f)
                .setDuration(EXIT_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
        stage.animate().scaleX(0.985f).scaleY(0.985f)
                .setDuration(EXIT_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
    }

    /** 状态行文本，宿主（服务/Activity）可以随时更新。 */
    public void setStatus(String text) {
        if (status == null) return;
        if (!showStatus) {
            status.setVisibility(GONE);
            return;
        }
        status.setText(text == null ? "" : text);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (getWidth() != lastW || getHeight() != lastH) {
            lastW = getWidth();
            lastH = getHeight();
            Log.i(TAG, "layout " + lastW + "x" + lastH
                    + " clock=" + (clock == null ? "-" : clock.getWidth() + "x" + clock.getHeight())
                    + " calendar=" + (calendar == null ? "未创建"
                    : calendar.getWidth() + "x" + calendar.getHeight()));
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // 悬浮窗要接返回键，必须在挂上窗口之后再要一次焦点
        if (backListener != null) {
            setFocusableInTouchMode(true);
            requestFocus();
        }
        handler.removeCallbacks(shifter);
        if (burnIn) {
            handler.postDelayed(shifter, SHIFT_MS);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(shifter);
        super.onDetachedFromWindow();
    }

    /** 每 15 秒在 2×2 像素里挪一格。 */
    private void applyShift() {
        shiftStep = (shiftStep + 1) % 4;
        float dx = (shiftStep == 1 || shiftStep == 2) ? 1f : 0f;
        float dy = (shiftStep >= 2) ? 1f : 0f;
        // 平移整个舞台（含右上角齿轮）：以前只挪 content/status，静止的齿轮字形
        // 反而成了长时间显示下唯一会烧屏的像素。
        // 同时平滑挪过去、不要硬跳 —— 1 像素的瞬时位移在画面边缘能看出"抖一下"。
        stage.animate().translationX(dx).translationY(dy)
                .setDuration(SHIFT_ANIM_MS).start();
    }
}
