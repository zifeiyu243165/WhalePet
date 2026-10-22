package com.whale.pet;

import android.animation.ValueAnimator;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * 桌宠前台服务：常驻通知 + 系统级悬浮窗（WebView 渲染 95 个透明动作）。
 */
public class PetService extends Service implements PetContainer.Host {

    public static final String ACTION_START  = "com.whale.pet.START";
    public static final String ACTION_STOP   = "com.whale.pet.STOP";
    public static final String ACTION_TOGGLE = "com.whale.pet.TOGGLE";
    public static final String ACTION_HIDE   = "com.whale.pet.HIDE";
    public static final String ACTION_APPLY  = "com.whale.pet.APPLY";

    private static PetService cur;

    public static void start(Context c) {
        Intent i = new Intent(c, PetService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        try {
            c.startService(new Intent(c, PetService.class).setAction(ACTION_STOP));
        } catch (Throwable ignored) {}
    }

    /** 配置改了，让正在运行的服务立刻生效（服务没在跑时什么也不做） */
    public static void applyPrefs(Context c) {
        PetService s = cur;
        if (s != null) s.applyFromPrefs();
    }

    private static final String CH_ID = "pet";
    private static final int NOTIF_ID = 0x5711;

    /** 角色包围盒宽高比（640x360 视频里角色实际占的竖长区域） */
    private static final float BODY_RATIO = 1.1783f;

    private WindowManager wm;
    private PetContainer root;
    private WebView web;
    private WindowManager.LayoutParams lp;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int charW, charH, charDp;
    private boolean expanded;          // 菜单展开中（窗口临时全屏）
    private boolean attached;
    private boolean menuClosing;
    private int savedX, savedY;

    private BroadcastReceiver screenRx;
    private boolean pageReady;
    private boolean interceptMode;   // 是否已切到 pet.local 备用通道
    private boolean petVisible = true;   // 当前是否被「只在桌面显示」隐藏
    private final Set<String> launchers = new HashSet<>();
    /** 最近一次确认过的前台包名。查不到新事件时沿用它——否则会被当成"未知"而重新显示出来 */
    private String lastFgPkg;
    /** 本次漫游的位移时长，先告诉 JS 让它按同样时长播走路动作 */
    private int roamDur;
    /** 等 JS 回报"动作已上屏"的兜底计时 */
    private Runnable roamKick;
    private Runnable roamGuard;

    // ---------------------------------------------------------------- 生命周期

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        cur = this;
        Prefs.migrate(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent == null ? ACTION_START : (intent.getAction() == null ? ACTION_START : intent.getAction());

        if (ACTION_APPLY.equals(act)) {
            applyFromPrefs();
            return START_STICKY;
        }

        if (ACTION_STOP.equals(act)) {
            Prefs.sp(this).edit().putBoolean(Prefs.K_ENABLED, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(act)) {
            if (attached) hidePet(); else showPet();
            return START_STICKY;
        }
        if (ACTION_HIDE.equals(act)) {
            Prefs.sp(this).edit().putBoolean(Prefs.K_ENABLED, false).apply();
            hidePet();
            stopSelf();
            return START_NOT_STICKY;
        }

        startInForeground();

        if (!Settings.canDrawOverlays(this)) {
            toast("还没有「显示在其他应用上层」权限");
            Prefs.sp(this).edit().putBoolean(Prefs.K_ENABLED, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }

        showPet();
        return START_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "桌宠常驻", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setDescription("让大肥鱼一直浮在桌面上");
            nm.createNotificationChannel(ch);
        }
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent toggle = PendingIntent.getService(this, 2,
                new Intent(this, PetService.class).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent stop = PendingIntent.getService(this, 3,
                new Intent(this, PetService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        b.setContentTitle("大肥鱼在桌面上")
                .setContentText("长按她可以切换动作或设置 · 点这里进入配置")
                .setSmallIcon(R.drawable.ic_stat_whale)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_LOW);
        b.addAction(new Notification.Action.Builder(null, attached ? "收起" : "显示", toggle).build());
        b.addAction(new Notification.Action.Builder(null, "关闭", stop).build());
        return b.build();
    }

    private void refreshNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification());
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- 悬浮窗

    private void showPet() {
        if (attached) return;
        charDp = Prefs.charDp(this);
        computeSize();

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        root = new PetContainer(this);
        root.setHost(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        // 这里原本有一句 root.setAlpha(Prefs.alpha(this))。View 层 alpha 一旦 <1，
        // 整个子树就得先渲进离屏 RenderNode 再整体混色 —— 而视频纹理在这条路上
        // 常被当成不透明源，角色身后于是浮出一块矩形底。透明度改由页面 CSS 的
        // --op 承担，它只作用在 canvas（叶子节点）上，不牵连任何祖先层。

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        // 注意：不要 setLayerType(HARDWARE)。强制离屏 RenderNode 后，部分 ROM 在合成
        // VP9 alpha 视频时会退化成不透明纹理，鲸鱼身后就出现一块淡底。窗口本身带
        // FLAG_HARDWARE_ACCELERATED，渲染依然是硬件加速的。
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // 关键：允许自动播放
        s.setLoadsImagesAutomatically(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(false);

        web.setWebViewClient(new WebViewClient() {
            /**
             * 有些 ROM 在页面加载完之后会把 WebView 的背景重置回默认白。
             * 白底会从视频的透明区域透出来，看着就像角色身后多了一块方框。
             * 加载完成后再刷一遍背景，并把 html/body 的底色钉死成透明。
             */
            @Override
            public void onPageFinished(WebView v, String url) {
                try {
                    v.setBackgroundColor(Color.TRANSPARENT);
                    v.setScrollbarFadingEnabled(true);
                    v.evaluateJavascript(
                            "(function(){var d=document.documentElement,b=document.body;" +
                            "d.style.background='transparent';d.style.backgroundColor='transparent';" +
                            "                    b.style.background='transparent';b.style.backgroundColor='transparent';})()", null);
                } catch (Throwable ignored) {}

            }

            /**
             * 备用加载通道：把 https://pet.local/... 映射到 assets。
             * 个别 ROM 的 WebView 不允许 file:// 下的 <video>，页面检测到播不出来就会
             * 让原生改用这个地址重新加载。
             */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                android.net.Uri u = req.getUrl();
                if (u == null || !"pet.local".equals(u.getHost())) return null;
                String path = u.getPath();
                if (path == null || path.isEmpty() || "/".equals(path)) path = "/pet.html";

                // 导入的动画：https://pet.local/my/xxx.webm → files/clips/xxx.webm
                // 放在私有目录里，页面用 https 通道取，取不到时页面会退回 file:// 绝对路径。
                if (path.startsWith("/my/")) {
                    String name = path.substring(4);
                    if (name.indexOf('/') >= 0 || name.contains("..")) return null;
                    try {
                        java.io.File f = new java.io.File(Prefs.clipsDir(PetService.this), name);
                        if (!f.exists() || f.length() <= 0) return null;
                        String mime = name.endsWith(".mp4") ? "video/mp4"
                                : name.endsWith(".mkv") ? "video/x-matroska" : "video/webm";
                        return new WebResourceResponse(mime, null, new java.io.FileInputStream(f));
                    } catch (Throwable t) {
                        return null;
                    }
                }

                String asset = path.startsWith("/") ? path.substring(1) : path;
                try {
                    java.io.InputStream is = getAssets().open(asset);
                    String mime;
                    if (asset.endsWith(".html")) mime = "text/html";
                    else if (asset.endsWith(".js")) mime = "application/javascript";
                    else if (asset.endsWith(".json")) mime = "application/json";
                    else if (asset.endsWith(".webm")) mime = "video/webm";
                    else if (asset.endsWith(".png")) mime = "image/png";
                    else mime = "application/octet-stream";
                    java.util.Map<String, String> h = new java.util.HashMap<>();
                    h.put("Access-Control-Allow-Origin", "*");
                    h.put("Cache-Control", "public, max-age=31536000");
                    WebResourceResponse r = new WebResourceResponse(mime, "UTF-8", is);
                    r.setResponseHeaders(h);
                    return r;
                } catch (Throwable e) {
                    return null;
                }
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");

        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        applyLayerMode();

        lp = new WindowManager.LayoutParams();
        lp.type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.width = charW;
        lp.height = charH;

        restorePos();
        lp.x = savedX;
        lp.y = savedY;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        web.loadUrl("file:///android_asset/pet.html");

        try {
            wm.addView(root, lp);
            attached = true;
            expanded = false;       // 新窗口一定是小窗状态
            menuClosing = false;
            pageReady = false;
            root.setVisibility(View.INVISIBLE);      // 等页面打开视频再显示，避免白闪
            ui.postDelayed(() -> {                   // 兜底：页面万一没回 ready 也要看得见
                if (root != null && petVisible && root.getVisibility() != View.VISIBLE) {
                    root.setVisibility(View.VISIBLE);
                }
            }, 5000);
        } catch (Throwable t) {
            attached = false;
            toast("悬浮窗创建失败：" + t.getMessage());
            stopSelf();
            return;
        }

        registerScreenReceiver();
        startWatch();
        Prefs.sp(this).edit().putBoolean(Prefs.K_ENABLED, true).apply();
        refreshNotification();
    }

    private void hidePet() {
        expanded = false;
        menuClosing = false;      // 收窗中途被关掉的话，别把状态卡在"正在收"上
        dragging = false;
        stopGlide();              // 滑行中的动画器盯着 lp/root，先停掉再拆窗口
        stopWatch();
        if (web != null) {
            try { web.evaluateJavascript("petPause()", null); } catch (Throwable ignored) {}
        }
        if (root != null && attached) {
            try { wm.removeView(root); } catch (Throwable ignored) {}
        }
        attached = false;
        destroyWeb();
        unregisterScreenReceiver();
        refreshNotification();
    }

    private void destroyWeb() {
        if (web != null) {
            try {
                ((FrameLayout) web.getParent()).removeView(web);
            } catch (Throwable ignored) {}
            try {
                web.removeJavascriptInterface("Android");
                web.loadUrl("about:blank");
                web.destroy();
            } catch (Throwable ignored) {}
            web = null;
        }
        root = null;
    }

    private void computeSize() {
        float d = getResources().getDisplayMetrics().density;
        charW = Math.round(charDp * d);
        charH = Math.round(charW * BODY_RATIO);
    }

    /** 还原上次位置；首次启动放在右下角偏上 */
    private void restorePos() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        SharedPreferences p = Prefs.sp(this);
        int x = p.getInt(Prefs.K_X, Integer.MIN_VALUE);
        int y = p.getInt(Prefs.K_Y, Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE) {
            x = dm.widthPixels - charW - (int) (16 * dm.density);
            y = (int) (dm.heightPixels * 0.56f);
        }
        savedX = clamp(x, -charW / 4, dm.widthPixels - charW * 3 / 4);
        savedY = clamp(y, 0, dm.heightPixels - charH * 3 / 5);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---------------------------------------------------------------- 手势 Host

    private boolean dragging;          // 手指还按着
    private ValueAnimator glide;       // 松手后的惯性滑行
    private long rateT;                // 倍速上报的时间/位置基线
    private int rateX, rateY;
    private float rateSent = 1f;

    @Override
    public void touchDown() {
        if (!attached || glide == null) return;
        // 抓住正在滑行的她：立刻停住并按落位处理，别让窗口在手底下继续跑
        stopGlide();
        savedX = lp.x;
        savedY = lp.y;
        Prefs.sp(this).edit().putInt(Prefs.K_X, savedX).putInt(Prefs.K_Y, savedY).apply();
        js("petDragEnd()");
    }

    @Override
    public void dragStart() {
        if (!attached || expanded) return;
        stopGlide();
        dragging = true;
        rateT = 0;
        rateSent = 1f;
        rateX = lp.x;
        rateY = lp.y;
        js("petDragStart()");          // 页面立刻切到"被拎起来"的悬空动作
    }

    @Override
    public void dragBy(float dx, float dy) {
        if (!attached || expanded) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int nx = clamp(Math.round(savedX + dx), -charW / 3, dm.widthPixels - charW * 2 / 3);
        int ny = clamp(Math.round(savedY + dy), 0, dm.heightPixels - charH * 2 / 3);
        moveTo(nx, ny);
        reportRate(nx, ny);
    }

    /**
     * 松手。慢慢放下就原地回到常驻动作；甩得够快就让惯性滑一段 ——
     * 这一步是"贴纸手感"和"活物手感"的分水岭，少了它前面再怎么调都死板。
     */
    @Override
    public void dragEnd(float vx, float vy) {
        if (!attached || lp == null) return;
        dragging = false;
        savedX = lp.x;
        savedY = lp.y;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float speed = (float) Math.hypot(vx, vy);
        if (speed < 260f * dm.density) {          // 约 260dp/s 以下：直接放下
            settleAfterDrag();
            return;
        }
        fling(vx, vy);
    }

    @Override
    public void longPress() {
        if (!attached || expanded) return;
        expandForMenu();
    }

    @Override
    public boolean menuOpen() {
        return expanded;
    }

    private void moveTo(int x, int y) {
        if (lp == null || root == null) return;
        if (lp.x == x && lp.y == y) return;
        lp.x = x;
        lp.y = y;
        pushLayout();
    }

    /**
     * 把移动速度换算成播放倍速发给页面：慢慢挪是慵懒的悬空，一把甩出去她就跟着急起来。
     * 用窗口位移算而不是手指位移，甩出去之后的滑行也能顺着减速。
     */
    private void reportRate(int x, int y) {
        long now = android.os.SystemClock.uptimeMillis();
        if (rateT == 0) { rateT = now; rateX = x; rateY = y; return; }
        float dt = (now - rateT) / 1000f;
        if (dt < 0.05f) return;                   // 太密了算不准，攒够 50ms 再看
        float dp = (float) Math.hypot(x - rateX, y - rateY) / getResources().getDisplayMetrics().density;
        rateT = now;
        rateX = x;
        rateY = y;
        float rate = clampf(0.8f + dp / dt / 900f, 0.8f, 1.7f);
        if (Math.abs(rate - rateSent) < 0.08f) return;
        rateSent = rate;
        js("petDragRate(" + rate + ")");
    }

    /**
     * 惯性滑行。
     *
     * 速度按指数阻尼衰减 v(t)=v0·e^-kt，总距离就是 v0/k；这里用 DecelerateInterpolator(3)
     * 逼近同一个形状，并把时长取 3/k，于是**起步速度正好等于松手速度**，看不出接缝。
     * 5.5/s 的阻尼大概滑 0.5 秒，甩一下飞 200~500px，手感最接近真把东西扔出去。
     */
    private void fling(float vx, float vy) {
        final float K = 5.5f;                     // 阻尼系数 1/s
        DisplayMetrics dm = getResources().getDisplayMetrics();
        final int loX = -charW / 3, hiX = dm.widthPixels - charW * 2 / 3;
        final int loY = 0, hiY = dm.heightPixels - charH * 2 / 3;
        final int x0 = lp.x, y0 = lp.y;
        final float runX = vx / K, runY = vy / K;
        final int dur = Math.round(clampf(3000f / K, 320f, 620f));   // ≈ 545ms
        rateT = 0;
        rateX = x0;
        rateY = y0;

        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(dur);
        a.setInterpolator(new android.view.animation.DecelerateInterpolator(3f));
        a.addUpdateListener(anim -> {
            if (!attached || expanded) return;
            float f = (float) anim.getAnimatedValue();
            int nx = clamp(Math.round(x0 + runX * f), loX, hiX);
            int ny = clamp(Math.round(y0 + runY * f), loY, hiY);
            moveTo(nx, ny);
            reportRate(nx, ny);                   // 滑行中倍速跟着一起降下来
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (glide != animation) return;    // 被新动作打断，收尾交给打断者
                glide = null;
                settleAfterDrag();
            }
        });
        glide = a;
        try { a.start(); } catch (Throwable t) { glide = null; settleAfterDrag(); }
    }

    private void stopGlide() {
        ValueAnimator a = glide;
        glide = null;                              // 先摘掉引用，动画结束回调就不会再收尾
        if (a != null) { try { a.cancel(); } catch (Throwable ignored) {} }
    }

    /** 拖动真正结束：落位、记住位置、回到常驻动作 */
    private void settleAfterDrag() {
        if (lp != null) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            savedX = clamp(lp.x, -charW / 4, dm.widthPixels - charW * 3 / 4);
            savedY = clamp(lp.y, 0, dm.heightPixels - charH * 3 / 5);
            lp.x = savedX;
            lp.y = savedY;
            Prefs.sp(this).edit().putInt(Prefs.K_X, savedX).putInt(Prefs.K_Y, savedY).apply();
        }
        js("petDragEnd()");
    }

    private static float clampf(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---------------------------------------------------------------- 菜单展开/收起

    private void expandForMenu() {
        if (expanded || !attached) return;
        stopGlide();                       // 菜单坐标要按当前窗口位置算，先让滑行停稳
        expanded = true;
        if (rover != null) rover.cancel();
        roamKick = null;
        if (roamGuard != null) ui.removeCallbacks(roamGuard);
        if (!petVisible) { petVisible = true; if (root != null) root.setVisibility(View.VISIBLE); }
        savedX = lp.x;
        savedY = lp.y;
        // 先让页面固定角色坐标，确认执行后才能改变窗口几何。
        final float density = getResources().getDisplayMetrics().density;
        web.evaluateJavascript("petPrepareMenu(" + (savedX / density) + "," + (savedY / density) + ")", value -> {
            if (!attached || !expanded) return;
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            lp.x = 0;
            lp.y = 0;
            pushLayout();
            web.postOnAnimation(() -> {
                if (attached && expanded) js("petOpenMenu()");
            });
        });
    }

    /** fromJs=true 表示 JS 已经关过菜单，只需收缩窗口 */
    private void collapseMenu(boolean fromJs) {
        if (!expanded || !attached || menuClosing) return;
        menuClosing = true;
        if (!fromJs) js("petCloseMenu()");
        // 弹层收起动画 320ms。窗口要是立刻缩回 96dp，整块面板会被压进小窗里"糊掉"。
        ui.postDelayed(this::beginShrink, 360);
    }

    /**
     * 收窗第一步：让页面把角色藏起来。
     *
     * 窗口从全屏缩回 96dp 时，页面那几帧还按「大窗口 + 屏幕坐标」排版。以前靠一段
     * @media 把角色的 left/top 临时改成 0/0 顶着，结果角色先出现在窗口左上角、
     * 下一帧再跳回原位 —— 就是"从角落闪回来"那道劣质动画。
     * 现在改成：先藏（visibility，不用 opacity，否则透明底的老问题会回来）→
     * 页面回 android.shrinkNow() 确认这一帧已经上屏 → 才改窗口几何。
     */
    private void beginShrink() {
        if (!attached || web == null) {
            menuClosing = false;
            expanded = false;
            return;
        }
        try {
            web.evaluateJavascript("petPreShrink()", null);
        } catch (Throwable t) {
            finishShrink();
            return;
        }
        // 页面两帧后主动回话；rAF 万一没跑（个别 ROM 会暂停），这里兜底收窗
        ui.postDelayed(this::finishShrink, 220);
    }

    /** 收窗第二步：把窗口缩回角色大小。可重复调用，只有第一次生效。 */
    private void finishShrink() {
        if (!menuClosing) return;
        menuClosing = false;
        if (!attached || root == null || lp == null) return;
        expanded = false;
        lp.width = charW;
        lp.height = charH;
        lp.x = savedX;
        lp.y = savedY;
        pushLayout();
        // 恢复小窗口后解除页面的屏幕坐标固定。页面会自己核对视口宽度是否真的变窄，
        // 没到位就下一帧再试 —— 宁可晚一两帧显示，也不要她先从窗外蹦进来。
        js("petMenuClosed()");
        // 菜单期间是强制显示的，收起后立刻按当前前台重新判定
        ui.postDelayed(() -> { if (!expanded) checkVisibleNow(); }, 260);
    }

    // ---------------------------------------------------------------- 漫游

    private ValueAnimator rover;

    /**
     * 请求漫游。
     *
     * 以前这里直接开动画，结果她在播「舞狮头」这类十秒长镜头时会被漫游强行打断 ——
     * 走到一半切成走路动作，原动作就看不完整了。现在先问页面一句：手上有没有
     * 只播一次的动作没播完？忙就跳过这一轮，等下次。
     */
    private void roamStep() {
        if (!attached || expanded || !petVisible || dragging || glide != null) return;
        js("petRoamReady()");
    }

    /** 页面确认空闲后真正开走 */
    private void doRoam() {
        if (!attached || expanded || !petVisible || dragging || glide != null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int margin = (int) (8 * dm.density);
        int lo = margin - charW / 6;
        int hi = dm.widthPixels - charW + charW / 6 - margin;
        if (hi <= lo) return;

        int target = lo + (int) (Math.random() * (hi - lo));
        final int from = lp.x;
        int dist = Math.abs(target - from);
        if (dist < charW / 2) return;                        // 太近就别走，免得原地抽搐

        // 约 150dp/s，太短太长都不自然
        int dur = clamp(Math.round(dist / (150f * dm.density) * 1000f), 800, 2600);
        roamDur = dur;
        final int dir = target > from ? 1 : -1;

        if (rover != null) rover.cancel();
        rover = ValueAnimator.ofInt(from, target);
        rover.setDuration(dur);
        rover.setInterpolator(new android.view.animation.LinearInterpolator());
        rover.addUpdateListener(a -> {
            if (!attached || expanded) return;
            lp.x = (int) a.getAnimatedValue();
            pushLayout();
        });

        // 先起动画，等它真正上屏再平移
        js("petWalk(" + dur + "," + dir + ")");

        final boolean[] kicked = {false};
        Runnable kick = () -> {
            if (kicked[0]) return;
            kicked[0] = true;
            roamKick = null;
            ui.removeCallbacks(roamGuard);
            if (rover != null && !rover.isStarted() && !expanded) {
                try { rover.start(); } catch (Throwable ignored) {}
            }
        };
        roamKick = kick;
        roamGuard = kick;
        ui.postDelayed(kick, 420);      // JS 迟迟没回报也照常走，不能站着不动
    }

    // ---------------------------------------------------------------- 屏幕开关

    private void registerScreenReceiver() {
        if (screenRx != null) return;
        screenRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    if (web != null) { web.onPause(); js("petPause()"); }
                } else if (Intent.ACTION_SCREEN_ON.equals(a) || Intent.ACTION_USER_PRESENT.equals(a)) {
                    if (web != null) { web.onResume(); js("petResume()"); }
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        try { registerReceiver(screenRx, f); } catch (Throwable ignored) {}
    }

    private void unregisterScreenReceiver() {
        if (screenRx != null) {
            try { unregisterReceiver(screenRx); } catch (Throwable ignored) {}
            screenRx = null;
        }
    }

    // ---------------------------------------------------------------- 只在桌面显示
    // 悬浮窗会浮在所有应用之上，所以必须自己判断前台是不是桌面：
    // 从 UsageStats 里取最近一次 MOVE_TO_FOREGROUND 的包名，
    // 不是桌面（也不是本应用）就把窗口藏起来，回到桌面再放出来。

    private static final long WATCH_MS = 650;

    private final Runnable watcher = new Runnable() {
        @Override public void run() {
            if (!attached) return;
            checkVisibleNow();
            ui.postDelayed(this, WATCH_MS);
        }
    };

    /** 立刻按当前前台应用判定一次显隐 */
    private void checkVisibleNow() {
        if (!attached || expanded || root == null) return;
        boolean want;
        if (!Prefs.homeOnly(this)) {
            want = true;
        } else {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return;      // 熄屏时保持现状
            try { want = shouldShow(); } catch (Throwable t) { want = petVisible; }
        }
        setPetVisible(want);

        // 状态变量对了不代表窗口真的看得见：有的 ROM 会把 GONE 过的悬浮窗卡在
        // "不可见"里，切回桌面也不再重绘 —— 表现就是"回桌面了但鲸鱼没出来"。
        // 所以每轮都拿实际可见性跟期望值对一遍，不一致就强制拉回来重绘。
        int v = want ? View.VISIBLE : View.GONE;
        if (root.getVisibility() != v) {
            root.setVisibility(v);
            if (want) reviveWindow();
        }
    }

    /** 把被藏起来/被系统冻结过的悬浮窗重新唤醒（恢复渲染 + 重绘 + 重启画面） */
    private void reviveWindow() {
        if (root == null) return;
        if (web != null) {
            try { web.onResume(); } catch (Throwable ignored) {}
            try { web.invalidate(); } catch (Throwable ignored) {}
        }
        try { root.invalidate(); } catch (Throwable ignored) {}
        js("petResume()");
        final PetContainer r = root;
        ui.postDelayed(() -> {                 // 再补一帧，个别 ROM 第一帧会丢
            if (r == null || !petVisible || r.getVisibility() != View.VISIBLE) return;
            r.invalidate();
        }, 90);
    }

    private void startWatch() {
        ui.removeCallbacks(watcher);
        loadLaunchers();
        if (Prefs.homeOnly(this)) ui.postDelayed(watcher, 500);
        else setPetVisible(true);
    }

    private void stopWatch() {
        ui.removeCallbacks(watcher);
    }

    /** 「使用情况访问」权限是否已授予（没它就分不清当前前台是谁） */
    public static boolean usageGranted(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void loadLaunchers() {
        launchers.clear();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo != null) launchers.add(ri.activityInfo.packageName);
            }
        } catch (Throwable ignored) {}
        launchers.add("com.android.systemui");   // 下拉通知栏 / 最近任务也算桌面态
    }

    private boolean shouldShow() {
        if (!usageGranted(this)) return true;    // 没授权就退化成常显，不能干脆不显示
        String pkg = foregroundPkg();
        if (pkg == null) return petVisible;      // 真判定不出来就维持现状，别突然冒出来
        if (pkg.equals(getPackageName())) return true;
        return launchers.contains(pkg);
    }

    /**
     * 当前前台包名。
     *
     * 三次踩坑的结论：
     *  1) 只查最近 6 秒的事件 → 在某个 App 里待久了就没有新事件，返回 null，
     *     调用方当成"不知道"而显示出来，于是切走几秒后又自己冒出来。
     *  2) 拿 120 秒的窗口 + 无条件沿用缓存 → 缓存一旦停在那个 App 上就再也不更新，
     *     按 Home 回桌面时明明换了前台，判定还停在旧 App，表现为"回到桌面鲸鱼不出来"。
     * 现在：事件窗口取 90 秒（够覆盖一切切换），拿不到就退回"最近使用时间"排名，
     * 后者会跟着启动器一起刷新，正好能正确识别"回到桌面"。
     */
    private String foregroundPkg() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService("usagestats");
            if (usm == null) return lastFgPkg;
            long now = System.currentTimeMillis();

            String p = latestFgEvent(usm, now - 90_000L, now);
            if (p != null) { lastFgPkg = p; return p; }

            String q = latestByStats(usm, now);   // 最近使用时间：回桌面时会刷新成启动器
            if (q != null) { lastFgPkg = q; return q; }
        } catch (Throwable ignored) {}
        return lastFgPkg;
    }

    private String latestFgEvent(UsageStatsManager usm, long from, long to) {
        try {
            UsageEvents ev = usm.queryEvents(from, to);
            if (ev == null) return null;
            UsageEvents.Event e = new UsageEvents.Event();
            String pkg = null;
            long best = 0;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                // MOVE_TO_FOREGROUND(1) 与 API 29 的 ACTIVITY_RESUMED(1) 同值
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    String n = e.getPackageName();
                    if (n != null && e.getTimeStamp() >= best) { best = e.getTimeStamp(); pkg = n; }
                }
            }
            return pkg;
        } catch (Throwable t) {
            return null;
        }
    }

    private String latestByStats(UsageStatsManager usm, long now) {
        try {
            android.app.usage.UsageStats best = null;
            java.util.List<android.app.usage.UsageStats> list =
                    usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 24 * 3600_000L, now);
            if (list == null) return null;
            for (android.app.usage.UsageStats u : list) {
                if (u == null) continue;
                if (best == null || u.getLastTimeUsed() > best.getLastTimeUsed()) best = u;
            }
            return best == null ? null : best.getPackageName();
        } catch (Throwable t) {
            return null;
        }
    }

    private void setPetVisible(boolean v) {
        if (expanded) return;                    // 动作库打开时绝不隐藏
        if (petVisible == v) return;
        petVisible = v;
        if (root == null) return;
        if (v) {
            root.setVisibility(View.VISIBLE);
            reviveWindow();
        } else {
            js("petPause()");                    // 藏起来就停掉解码，别白耗电
            root.setVisibility(View.GONE);
            final WebView w = web;
            final PetContainer r = root;
            // 隔一拍再挂起 WebView：直接 onPause 会让上面这句 petPause() 排不上队
            ui.postDelayed(() -> {
                if (petVisible || w == null) return;
                try { w.onPause(); } catch (Throwable ignored) {}
            }, 400);
            ui.postDelayed(() -> {               // 再确认一次：个别 ROM 会在这里把它复活
                if (!petVisible && r != null && r.getVisibility() != View.GONE) {
                    r.setVisibility(View.GONE);
                }
            }, 320);
        }
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 更新窗口布局。
     * 部分 ROM（MIUI / EMUI 等）在 updateViewLayout 之后会把已经 GONE 的窗口重新显示，
     * 这正好能解释"拖动或走位之后鱼又冒出来了"。所以每次布局之后都把应有的可见性再压一遍。
     */
    private void pushLayout() {
        if (root == null || lp == null || wm == null) return;
        try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) {}
        if (root == null) return;
        int want = petVisible ? View.VISIBLE : View.GONE;
        if (root.getVisibility() != want) root.setVisibility(want);
    }

    private void js(final String code) {
        if (web == null) return;
        ui.post(() -> {
            if (web == null) return;
            try { web.evaluateJavascript(code, null); } catch (Throwable ignored) {}
        });
    }

    private void applyLayerMode() {
        if (web == null) return;
        web.setLayerType(View.LAYER_TYPE_NONE, null);
        web.setBackgroundColor(Color.TRANSPARENT);
        if (root != null) root.setBackgroundColor(Color.TRANSPARENT);
    }

    private void toast(String m) {
        try { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }

    @Override
    public void onDestroy() {
        hidePet();
        unregisterScreenReceiver();
        cur = null;
        try { stopForeground(true); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    /** 把最新的 SharedPreferences 应用到已显示的悬浮窗 */
    private void applyFromPrefs() {
        ui.post(() -> {
            charDp = Prefs.charDp(this);
            computeSize();
            // 不再对 root 设 alpha：View 层 alpha<1 会让整个子树进离屏 RenderNode，
            // 视频纹理在这条路上常被当成不透明源。透明度交给页面 CSS 的 --op。
            applyLayerMode();
            // 拖动 / 惯性滑行期间不要按偏好重排窗口，否则会跟手指抢位置
            if (attached && !expanded && !dragging && glide == null) {
                lp.width = charW;
                lp.height = charH;
                restorePos();
                lp.x = savedX;
                lp.y = savedY;
                try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) {}
            }
            try {
                js("petApply(" + JSONObject.quote(Prefs.toJson(this)) + ")");
            } catch (Throwable ignored) {}
            if (attached) startWatch();
        });
    }

    // ---------------------------------------------------------------- JS 桥

    public class Bridge {

        @JavascriptInterface
        public String getConfig() {
            return Prefs.toJson(PetService.this);
        }

        @JavascriptInterface
        public void saveConfig(String json) {
            try {
                JSONObject o = new JSONObject(json);
                SharedPreferences.Editor e = Prefs.sp(PetService.this).edit();
                if (o.has("w")) e.putInt(Prefs.K_CHAR_DP, o.optInt("w", Prefs.DEF_CHAR_DP));
                if (o.has("op")) e.putFloat(Prefs.K_ALPHA, o.optInt("op", 100) / 100f);
                if (o.has("roam")) e.putBoolean(Prefs.K_ROAM, o.optBoolean("roam", true));
                if (o.has("tapMode")) e.putString(Prefs.K_TAP_MODE, o.optString("tapMode", "all"));
                if (o.has("variant")) e.putString(Prefs.K_VARIANT, o.optString("variant", Prefs.DEF_VARIANT));
                if (o.has("idle")) e.putString(Prefs.K_IDLE, o.optString("idle", "breathing"));
                e.apply();
            } catch (Throwable ignored) {}
        }

        /** 页面报告素材播不出来：切到 WebViewAssetLoader 式的拦截通道重试一次 */
        @JavascriptInterface
        public void assetFail() {
            ui.post(() -> {
                if (web == null || interceptMode) return;
                interceptMode = true;
                try {
                    web.loadUrl("https://pet.local/pet.html");
                } catch (Throwable ignored) {}
            });
        }

        @JavascriptInterface
        public void ready() {
            ui.post(() -> {
                pageReady = true;
                if (root != null) root.setVisibility(petVisible ? View.VISIBLE : View.GONE);
            });
        }

        @JavascriptInterface
        public void closeMenu() {
            ui.post(() -> collapseMenu(true));
        }

        /** 页面确认"角色已隐藏且这一帧已经上屏"，这时才敢改窗口几何 */
        @JavascriptInterface
        public void shrinkNow() {
            ui.post(PetService.this::finishShrink);
        }

        @JavascriptInterface
        public void openMenu() {
            ui.post(PetService.this::expandForMenu);
        }

        @JavascriptInterface
        public void requestConfig() {
            ui.post(() -> {
                try {
                    Intent i = new Intent(PetService.this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Throwable ignored) {}
            });
        }

        @JavascriptInterface
        public void quit() {
            ui.post(() -> {
                Prefs.sp(PetService.this).edit().putBoolean(Prefs.K_ENABLED, false).apply();
                stopSelf();
            });
        }

        @JavascriptInterface
        public void roam() {
            ui.post(PetService.this::roamStep);
        }

        /** 页面报告"走路动作已经上屏"，这时才真正开始平移窗口 */
        @JavascriptInterface
        public void walkStart() {
            ui.post(() -> {
                Runnable k = roamKick;
                if (k != null) k.run();
            });
        }

        /** 页面确认当前不在播一次性动作，可以开始这次漫游 */
        @JavascriptInterface
        public void roamNow() {
            ui.post(PetService.this::doRoam);
        }

        @JavascriptInterface
        public void setSize(int dp) {
            ui.post(() -> {
                charDp = dp;
                computeSize();
                if (attached && !expanded && !dragging && glide == null) {
                    lp.width = charW;
                    lp.height = charH;
                    restorePos();
                    lp.x = savedX;
                    lp.y = savedY;
                    try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) {}
                }
            });
        }

        @JavascriptInterface
        public void setAlpha(float a) {
            ui.post(() -> {
                // 见 showPet() 的注释：不再动 View 的 alpha，交给页面 CSS
                float v = Math.max(0.15f, Math.min(1f, a));
                js("petSetVar('--op','" + v + "')");
            });
        }

        @JavascriptInterface
        public void vibrate() {
            ui.post(() -> {
                try {
                    android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                    if (v == null) return;
                    if (Build.VERSION.SDK_INT >= 26) {
                        v.vibrate(android.os.VibrationEffect.createOneShot(14, 90));
                    } else {
                        v.vibrate(14);
                    }
                } catch (Throwable ignored) {}
            });
        }

        /** 打开系统文件选择器导入一个动画 */
        @JavascriptInterface
        public void importClip() {
            ui.post(() -> {
                try {
                    Intent i = new Intent(PetService.this, ImportActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Throwable t) {
                    toast("打不开文件选择器");
                }
            });
        }

        @JavascriptInterface
        public void removeClip(String id) {
            if (id == null || id.length() == 0) return;
            ui.post(() -> {
                Prefs.removeClip(PetService.this, id);
                pushClips();
                toast("已删除");
            });
        }
    }

    /** 导入页 / 配置页改完「我的动作」后，把新列表推给页面 */
    public static void pushClips() {
        PetService s = cur;
        if (s == null || s.web == null) return;
        final String json = Prefs.clipsJson(s);
        s.js("petClips(" + org.json.JSONObject.quote(json) + ")");
    }
}
