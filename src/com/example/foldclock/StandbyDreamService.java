package com.example.foldclock;

import android.service.dreams.DreamService;

import com.example.foldclock.ui.StandByView;

/**
 * 系统屏保。这是最「名正言顺」的一条路：Dream 是系统自己拉起来的，
 * 完全不需要后台启动、悬浮窗这些特权。
 * 用户在 设置 → 显示 → 屏保 里选中「外屏时钟」即可。
 */
public class StandbyDreamService extends DreamService {

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        StandByView v = new StandByView(this, new StandByView.OnTapListener() {
            @Override
            public void onTap() {
                finish();
            }
        }, false);
        setContentView(v);
    }
}
