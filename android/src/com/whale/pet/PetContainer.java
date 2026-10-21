package com.whale.pet;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * 悬浮窗容器。负责区分三种手势：
 *   轻点 → 放行给 WebView（由页面里的 JS 处理"点她"）
 *   拖动 → 中途夺权，移动整个窗口
 *   长按 → 回调 Host 弹出全屏菜单
 */
public class PetContainer extends FrameLayout {

    public interface Host {
        void dragBy(float dx, float dy);
        void dragEnd();
        void longPress();
        boolean menuOpen();
    }

    private static final int LONG_PRESS_MS = 460;

    private Host host;
    private final int slop;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float downRawX, downRawY;
    private boolean dragging, longPressed, tracking;

    private final Runnable longPressRun = new Runnable() {
        @Override public void run() {
            if (tracking && !dragging && host != null && !host.menuOpen()) {
                longPressed = true;
                host.longPress();
            }
        }
    };

    public PetContainer(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setClipChildren(false);
        setClipToPadding(false);
    }

    public void setHost(Host h) { this.host = h; }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (longPressed && e.getActionMasked() != MotionEvent.ACTION_DOWN) {
            MotionEvent cancel = MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            handler.removeCallbacks(longPressRun);
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                tracking = false;
                longPressed = false;
            }
            return true;
        }
        return super.dispatchTouchEvent(e);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                dragging = false;
                longPressed = false;
                tracking = true;
                handler.removeCallbacks(longPressRun);
                if (host != null && !host.menuOpen()) {
                    handler.postDelayed(longPressRun, LONG_PRESS_MS);
                }
                return false;                       // 先不拦，让 WebView 拿到 DOWN

            case MotionEvent.ACTION_MOVE: {
                if (!tracking || longPressed || host == null || host.menuOpen()) return false;
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                    dragging = true;
                    handler.removeCallbacks(longPressRun);
                    host.dragBy(dx, dy);            // 补上这一次位移，避免起步掉帧
                    return true;                    // 夺权（WebView 会收到 CANCEL）
                }
                return false;
            }

            default:
                handler.removeCallbacks(longPressRun);
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (dragging && host != null) {
                    host.dragBy(e.getRawX() - downRawX, e.getRawY() - downRawY);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRun);
                if (dragging && host != null) {
                    dragging = false;
                    host.dragEnd();
                }
                tracking = false;
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(longPressRun);
        super.onDetachedFromWindow();
    }
}
