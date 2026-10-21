package com.whale.pet;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** 配置主页：开关、权限引导、外观与行为设置 */
public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 0x11;
    private static final int REQ_NOTIF = 0x12;

    private Switch swMaster, swRoam, swBoot, swHome;
    private SeekBar sbSize, sbAlpha;
    private TextView tvState, tvOverlay, tvNotif, tvBattery, tvUsage, tvSize, tvAlpha;
    private Button btnOverlay, btnNotif, btnBattery, btnUsage, swTap;

    private boolean binding;          // 防止程序改控件时回调打转
    private boolean pendingStart;     // 授权回来后自动开启

    private int dpSize, dpAlpha;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        Prefs.migrate(this);
        bind();
        load();
        wire();
    }

    private void bind() {
        swMaster = findViewById(R.id.swMaster);
        swRoam = findViewById(R.id.swRoam);
        swTap = findViewById(R.id.swTap);
        swBoot = findViewById(R.id.swBoot);
        swHome = findViewById(R.id.swHome);
        sbSize = findViewById(R.id.sbSize);
        sbAlpha = findViewById(R.id.sbAlpha);
        tvState = findViewById(R.id.tvState);
        tvOverlay = findViewById(R.id.tvOverlay);
        tvNotif = findViewById(R.id.tvNotif);
        tvBattery = findViewById(R.id.tvBattery);
        tvUsage = findViewById(R.id.tvUsage);
        tvSize = findViewById(R.id.tvSize);
        tvAlpha = findViewById(R.id.tvAlpha);
        btnOverlay = findViewById(R.id.btnOverlay);
        btnNotif = findViewById(R.id.btnNotif);
        btnBattery = findViewById(R.id.btnBattery);
        btnUsage = findViewById(R.id.btnUsage);
    }

    private void load() {
        SharedPreferences p = Prefs.sp(this);
        binding = true;

        dpSize = p.getInt(Prefs.K_CHAR_DP, Prefs.DEF_CHAR_DP);
        dpAlpha = Math.round(p.getFloat(Prefs.K_ALPHA, Prefs.DEF_ALPHA) * 100);

        swRoam.setChecked(p.getBoolean(Prefs.K_ROAM, true));
        swBoot.setChecked(p.getBoolean(Prefs.K_BOOT, true));
        swHome.setChecked(p.getBoolean(Prefs.K_HOME_ONLY, true));

        sbSize.setProgress(clamp(dpSize - 60, 0, 240));
        sbAlpha.setProgress(clamp(dpAlpha - 30, 0, 70));
        tvSize.setText(dpSize + " dp");
        tvAlpha.setText(dpAlpha + "%");

        binding = false;
    }

    private void refreshTap() {
        String m = Prefs.tapMode(this);
        swTap.setText("none".equals(m) ? "不响应" : "reactions".equals(m) ? "互动反应" : "全部动作随机");
    }

    private void wire() {
        swMaster.setOnCheckedChangeListener((btn, on) -> {
            if (binding) return;
            if (on) startPetFlow(); else stopPet();
        });

        swRoam.setOnCheckedChangeListener((btn, on) -> {
            if (binding) return;
            Prefs.sp(this).edit().putBoolean(Prefs.K_ROAM, on).apply();
            PetService.applyPrefs(this);
        });

        swTap.setOnClickListener(v -> {
            String current = Prefs.tapMode(this);
            String next = "all".equals(current) ? "reactions" : "reactions".equals(current) ? "none" : "all";
            Prefs.sp(this).edit().putString(Prefs.K_TAP_MODE, next).apply();
            refreshTap();
            PetService.applyPrefs(this);
        });

        swBoot.setOnCheckedChangeListener((btn, on) -> {
            if (binding) return;
            Prefs.sp(this).edit().putBoolean(Prefs.K_BOOT, on).apply();
            PetService.applyPrefs(this);
        });

        sbSize.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                dpSize = v + 60;
                tvSize.setText(dpSize + " dp");
                if (!fromUser) return;
                Prefs.sp(MainActivity.this).edit().putInt(Prefs.K_CHAR_DP, dpSize).apply();
                PetService.applyPrefs(MainActivity.this);
            }
        });

        sbAlpha.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                dpAlpha = v + 30;
                tvAlpha.setText(dpAlpha + "%");
                if (!fromUser) return;
                Prefs.sp(MainActivity.this).edit().putFloat(Prefs.K_ALPHA, dpAlpha / 100f).apply();
                PetService.applyPrefs(MainActivity.this);
            }
        });

        swHome.setOnCheckedChangeListener((btn, on) -> {
            if (binding) return;
            Prefs.sp(this).edit().putBoolean(Prefs.K_HOME_ONLY, on).apply();
            PetService.applyPrefs(this);
            refreshPerms();
        });

        btnUsage.setOnClickListener(v -> requestUsage());
        btnOverlay.setOnClickListener(v -> requestOverlay());
        btnNotif.setOnClickListener(v -> requestNotif());
        btnBattery.setOnClickListener(v -> requestBattery());
    }

    // ------------------------------------------------------------- 权限与状态

    private void refreshPerms() {
        boolean ov = Settings.canDrawOverlays(this);
        tvOverlay.setText(ov ? "已授予" : "未授予 · 没有它鲸鱼浮不到桌面上");
        tvOverlay.setTextColor(getColor(ov ? R.color.ok : R.color.warn));
        btnOverlay.setText(ov ? "已开" : "去授权");
        btnOverlay.setEnabled(!ov);

        boolean nt = Build.VERSION.SDK_INT < 33
                || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
        tvNotif.setText(nt ? "已允许" : "未允许 · 通知栏没有一键开关");
        tvNotif.setTextColor(getColor(nt ? R.color.ok : R.color.dim));
        btnNotif.setEnabled(!nt);

        boolean ug = PetService.usageGranted(this);
        boolean homeOn = swHome.isChecked();
        if (!homeOn) {
            tvUsage.setText("未启用「只在桌面显示」，此权限用不到");
            tvUsage.setTextColor(getColor(R.color.dim));
            btnUsage.setEnabled(false);
        } else {
            tvUsage.setText(ug ? "已授予 · 只在桌面显示可用" : "未授予 · 暂时退化为「所有界面都显示」");
            tvUsage.setTextColor(getColor(ug ? R.color.ok : R.color.warn));
            btnUsage.setEnabled(!ug);
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean ig = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        tvBattery.setText(ig ? "已加入白名单" : "未设置 · 锁屏久了可能被系统清掉");
        tvBattery.setTextColor(getColor(ig ? R.color.ok : R.color.dim));
        btnBattery.setEnabled(!ig);
    }

    private void refreshState() {
        binding = true;
        boolean on = Prefs.enabled(this) && Settings.canDrawOverlays(this);
        swMaster.setChecked(on);
        binding = false;
        tvState.setText(on ? "正在桌面上运行 · 长按她可以换动作" : "已关闭");
        tvState.setTextColor(getColor(on ? R.color.accent : R.color.dim));
    }

    private void requestOverlay() {
        pendingStart = true;
        try {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
        } catch (Throwable t) {
            toast("这台手机没有该设置页，请到「设置 - 应用 - 特殊权限」里手动打开");
        }
    }

    private void requestNotif() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }
    }

    private void requestUsage() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Throwable t2) {
                toast("请手动到「设置 - 应用 - 特殊权限」里授予使用情况访问");
            }
        }
    }

    private void requestBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                toast("请手动到电池设置里把本应用设为不受限制");
            }
        }
    }

    private void startPetFlow() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlay();
            return;
        }
        PetService.start(this);
        tvState.setText("正在启动…");
        tvState.postDelayed(this::refreshState, 900);
    }

    private void stopPet() {
        Prefs.sp(this).edit().putBoolean(Prefs.K_ENABLED, false).apply();
        PetService.stop(this);
        refreshState();
    }

    // ------------------------------------------------------------- 生命周期

    @Override
    protected void onResume() {
        super.onResume();
        refreshTap();
        refreshPerms();
        if (Prefs.enabled(this) && Settings.canDrawOverlays(this)) PetService.start(this);
        refreshState();
        if (pendingStart && Settings.canDrawOverlays(this)) {
            pendingStart = false;
            startPetFlow();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        refreshPerms();
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** SeekBar 的默认实现要求重写两个空方法，这里收个口 */
    private static abstract class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar s) {}
        @Override public void onStopTrackingTouch(SeekBar s) {}
    }
}
