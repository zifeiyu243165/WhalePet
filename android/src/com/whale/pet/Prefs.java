package com.whale.pet;

import android.content.Context;
import android.content.SharedPreferences;

/** 配置集中管理（JS 与原生共用同一份） */
public class Prefs {
    private static final String FILE = "whale";

    public static final String K_CHAR_DP   = "charDp";    // 角色宽度 dp
    public static final String K_ALPHA     = "alpha";     // 0.3 ~ 1.0
    public static final String K_ROAM      = "roam";      // 自动漫游
    public static final String K_TAP_MODE  = "tapMode";
    public static final String K_IDLE      = "idle";      // 常驻动作 id
    public static final String K_VARIANT   = "variant";   // 动作变体 auto/normal/mirror/slow/mirrorSlow
    public static final String K_ENABLED   = "enabled";   // 桌宠总开关
    public static final String K_HOME_ONLY = "homeOnly";  // 只在桌面（启动器）上显示
    public static final String K_X         = "posX";
    public static final String K_Y         = "posY";
    public static final String K_BOOT      = "bootStart";
    public static final String K_HINTED    = "hinted";
    public static final String K_CLIPS     = "myClips";   // 导入的动画（JSON 数组）

    /** 96dp ≈ 2.9cm，约为早期版本的 1/2，画面不再放大发虚 */
    public static final int DEF_CHAR_DP = 96;
    public static final float DEF_ALPHA = 1f;
    public static final String DEF_VARIANT = "auto";

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static int charDp(Context c) {
        SharedPreferences p = sp(c);
        // v1.1 把默认尺寸减半：老用户一次性迁移过来，免得还是那只占满屏的大鱼
        if (!p.getBoolean("sizeRev2", false)) {
            p.edit().putInt(K_CHAR_DP, DEF_CHAR_DP).putBoolean("sizeRev2", true).apply();
            return DEF_CHAR_DP;
        }
        return p.getInt(K_CHAR_DP, DEF_CHAR_DP);
    }
    public static float alpha(Context c) {
        return sp(c).getFloat(K_ALPHA, DEF_ALPHA);
    }
    public static boolean enabled(Context c) {
        return sp(c).getBoolean(K_ENABLED, false);
    }
    public static boolean bootStart(Context c) {
        return sp(c).getBoolean(K_BOOT, true);
    }
    public static boolean homeOnly(Context c) {
        return sp(c).getBoolean(K_HOME_ONLY, true);
    }

    public static String tapMode(Context c) {
        String mode = sp(c).getString(K_TAP_MODE, "all");
        return "reactions".equals(mode) || "none".equals(mode) ? mode : "all";
    }

    public static void migrate(Context c) {
        SharedPreferences p = sp(c);
        if (p.getBoolean("cleanSettingsV18", false)) return;
        p.edit().remove("render").remove("render2").remove("diag").remove("diagAt")
                .remove("tapRandom").putBoolean("cleanSettingsV18", true).apply();
        new java.io.File(c.getFilesDir(), "diag.png").delete();
        new java.io.File(c.getFilesDir(), "diag.txt").delete();
    }

    // ---------------------------------------------------------------- 导入的动画

    public static java.io.File clipsDir(Context c) {
        java.io.File d = new java.io.File(c.getFilesDir(), "clips");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static org.json.JSONArray clips(Context c) {
        try { return new org.json.JSONArray(sp(c).getString(K_CLIPS, "[]")); }
        catch (Throwable t) { return new org.json.JSONArray(); }
    }

    public static void saveClips(Context c, org.json.JSONArray a) {
        sp(c).edit().putString(K_CLIPS, a.toString()).apply();
    }

    public static void addClip(Context c, String id, String name, String file) {
        org.json.JSONArray a = clips(c);
        try {
            a.put(new org.json.JSONObject().put("id", id).put("name", name).put("file", file));
        } catch (Throwable ignored) {}
        saveClips(c, a);
    }

    public static String fileOfClip(Context c, String id) {
        org.json.JSONArray a = clips(c);
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONObject o = a.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return o.optString("file");
        }
        return null;
    }

    public static void removeClip(Context c, String id) {
        String f = fileOfClip(c, id);
        org.json.JSONArray a = clips(c);
        org.json.JSONArray out = new org.json.JSONArray();
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONObject o = a.optJSONObject(i);
            if (o == null || id.equals(o.optString("id"))) continue;
            out.put(o);
        }
        saveClips(c, out);
        if (f != null) { try { new java.io.File(clipsDir(c), f).delete(); } catch (Throwable ignored) {} }
    }

    /** 给页面的「我的」列表：src 走 pet.local 拦截通道，file 是 file:// 兜底 */
    public static String clipsJson(Context c) {
        org.json.JSONArray a = clips(c);
        org.json.JSONArray out = new org.json.JSONArray();
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String f = o.optString("file");
            java.io.File file = new java.io.File(clipsDir(c), f);
            if (!file.exists()) continue;
            try {
                out.put(new org.json.JSONObject()
                        .put("id", o.optString("id"))
                        .put("name", o.optString("name"))
                        .put("src", "https://pet.local/my/" + f)
                        .put("file", "file://" + file.getAbsolutePath()));
            } catch (Throwable ignored) {}
        }
        return out.toString();
    }

    /** 给 JS 的 JSON（键名与 pet.html 中 cfg 对齐） */
    public static String toJson(Context c) {
        SharedPreferences p = sp(c);
        return "{"
                + "\"w\":" + p.getInt(K_CHAR_DP, DEF_CHAR_DP) + ","
                + "\"op\":" + Math.round(p.getFloat(K_ALPHA, DEF_ALPHA) * 100) + ","
                + "\"roam\":" + p.getBoolean(K_ROAM, true) + ","
                + "\"tapMode\":\"" + tapMode(c) + "\","
                + "\"variant\":\"" + p.getString(K_VARIANT, DEF_VARIANT) + "\","
                + "\"idle\":\"" + p.getString(K_IDLE, "breathing") + "\","
                + "\"clips\":" + clipsJson(c)
                + "}";
    }
}
