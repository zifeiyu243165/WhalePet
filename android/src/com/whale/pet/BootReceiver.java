package com.whale.pet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** 开机 / 应用更新后自动把鲸鱼放回桌面 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (c == null) return;
        if (!Prefs.bootStart(c)) return;
        if (!Prefs.enabled(c)) return;                 // 用户主动关过就不再自动拉起
        if (!Settings.canDrawOverlays(c)) return;
        try { PetService.start(c); } catch (Throwable ignored) {}
    }
}
