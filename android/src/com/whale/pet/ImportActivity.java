package com.whale.pet;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 只为"导入一个动画"而存在的透明壳 Activity。
 * <p>
 * Service 没法 startActivityForResult，所以拉起系统文件选择器这件事放到这里做：
 * 选完把文件拷进 files/clips/，登记到 Prefs，再把新列表推给悬浮窗里的页面。
 */
public class ImportActivity extends Activity {

    private static final int REQ_PICK = 0x51;
    private static final long MAX_BYTES = 40L * 1024 * 1024;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "video/webm", "video/x-matroska", "video/mp4", "application/octet-stream"
        });
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            tip("这台设备没有可用的文件选择器");
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }
        final Uri uri = data.getData();
        new Thread(() -> {
            String err = null;
            String id = null;
            try {
                id = importOne(uri);
            } catch (Throwable t) {
                err = t.getMessage();
            }
            final String fErr = err, fId = id;
            runOnUiThread(() -> {
                if (fId != null) {
                    PetService.pushClips();
                    tip("导入成功，长按桌宠 → 动作 → 我的");
                } else {
                    tip("导入失败：" + (fErr == null ? "未知错误" : fErr));
                }
                finish();
            });
        }).start();
    }

    /** 拷文件 + 登记，返回新 id；失败抛异常 */
    private String importOne(Uri uri) throws Exception {
        String display = null;
        long size = -1;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) display = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        }
        if (size > MAX_BYTES) throw new Exception("文件太大（上限 40MB）");

        String ext = "webm";
        if (display != null) {
            int dot = display.lastIndexOf('.');
            if (dot > 0) ext = display.substring(dot + 1).toLowerCase();
        }
        if (!ext.equals("webm") && !ext.equals("mp4") && !ext.equals("mkv")) {
            throw new Exception("只支持 WebM（带透明）/ MP4 / MKV");
        }

        String id = "my_" + System.currentTimeMillis();
        String fileName = id + "." + ext;
        File dir = Prefs.clipsDir(this);
        File out = new File(dir, fileName);

        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fo = new FileOutputStream(out)) {
            if (in == null) throw new Exception("读不到这个文件");
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                fo.write(buf, 0, n);
                total += n;
                if (total > MAX_BYTES) throw new Exception("文件太大（上限 40MB）");
            }
            fo.flush();
            if (total < 1024) throw new Exception("文件是空的");
        }

        String name = display != null ? display : id;
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        if (name.length() > 14) name = name.substring(0, 14);

        Prefs.addClip(this, id, name, fileName);
        return id;
    }

    private void tip(String s) {
        try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
