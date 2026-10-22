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
 *
 * 拖动这块只做两件事：把手指位移换算成窗口位移，以及**记录释放瞬间的速度**。
 * 有了速度原生才算得出甩出去该滑多远，否则松手即停，怎么调都是"贴纸"手感。
 */
public class PetContainer extends FrameLayout {

    public interface Host {
        /** 手指刚按到窗口上（还没到拖动阈值）：用来打断正在进行的惯性滑行 */
        void touchDown();
        /** 手指越过阈值、真正开始拖了 */
        void dragStart();
        /** 相对按下点的累计位移（px） */
        void dragBy(float dx, float dy);
        /** 松手。vx/vy 是手指速度（px/s），取最近一段采样 */
        void dragEnd(float vx, float vy);
        void longPress();
        boolean menuOpen();
    }

    private static final int LONG_PRESS_MS = 460;
    /** 速度采样点数。太多会被很久以前的位移拖慢，太少则一抖就飞 */
    private static final int VS = 6;

    private Host host;
    private final int slop;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float downRawX, downRawY;
    private boolean dragging, longPressed, tracking;

    private final long[] vt = new long[VS];
    private final float[] vx = new float[VS], vy = new float[VS];
    private int vn;

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
                vn = 0;
                sample(e);
                handler.removeCallbacks(longPressRun);
                if (host != null) {
                    host.touchDown();               // 她正滑着的时候按下去，先把滑行停住
                    if (!host.menuOpen()) handler.postDelayed(longPressRun, LONG_PRESS_MS);
                }
                return false;                       // 先不拦，让 WebView 拿到 DOWN

            case MotionEvent.ACTION_MOVE: {
                if (!tracking || longPressed || host == null || host.menuOpen()) return false;
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                    dragging = true;
                    handler.removeCallbacks(longPressRun);
                    // 以前这里直接把 dx/dy 补上去（注释写的是"避免起步掉帧"），
                    // 结果是越过阈值那一下窗口瞬移一个 slop —— 96dp 的小窗上就是
                    // 肉眼可见的一"跳"，手感从第一下就毁了。
                    // 改成把起点前移到"刚好越过阈值"的位置：后续位移从那里算起，
                    // 手指和角色最多差一个 slop，但全程没有跳变。
                    downRawX += clampf(dx, -slop, slop);
                    downRawY += clampf(dy, -slop, slop);
                    host.dragStart();
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
                    sample(e);
                    host.dragBy(e.getRawX() - downRawX, e.getRawY() - downRawY);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                handler.removeCallbacks(longPressRun);
                if (dragging && host != null) {
                    sample(e);
                    dragging = false;
                    float[] v = velocity();
                    host.dragEnd(v == null ? 0f : v[0], v == null ? 0f : v[1]);
                }
                tracking = false;
                return true;
            }
            default:
                return true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(longPressRun);
        super.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------- 速度采样

    /** 存最近 VS 个点，最新的在 [0] */
    private void sample(MotionEvent e) {
        for (int i = Math.min(vn, VS - 1); i > 0; i--) {
            vt[i] = vt[i - 1];
            vx[i] = vx[i - 1];
            vy[i] = vy[i - 1];
        }
        vt[0] = e.getEventTime();
        vx[0] = e.getRawX();
        vy[0] = e.getRawY();
        if (vn < VS) vn++;
    }

    /**
     * 最新点与最老点的平均速度。
     * 区间太短（<8ms，同一批事件）算不出来；太旧（>160ms，手指其实早停了）说明
     * 是"停住再松手"，应当当作没有速度 —— 否则她会自己飘出去。
     */
    private float[] velocity() {
        if (vn < 2) return null;
        long dt = vt[0] - vt[vn - 1];
        if (dt < 8 || dt > 160) return null;
        return new float[]{(vx[0] - vx[vn - 1]) * 1000f / dt, (vy[0] - vy[vn - 1]) * 1000f / dt};
    }

    private static float clampf(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
