package com.ecut.campus;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** 二级页：关于。项目主页、检查更新、联系方式与赞赏。 */
public class AboutActivity extends AppCompatActivity {
    private TextView updateTrail;
    private TextView updateSub;
    private boolean checking;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        G.init(this);
        render();
        if (Updater.hasUpdateSource()) check(false);
    }

    private static String rowIndex(int n) {
        return String.format(java.util.Locale.US, "%02d", n);
    }

    private void render() {
        LinearLayout col = Ui.page(this, "设置", getString(R.string.app_name), "v" + BuildConfig.VERSION_NAME
                + " · 非官方校园课表工具");

        Ui.label(this, col, "PROJECT · 项目");
        LinearLayout c1 = Ui.card(this, col);
        int idx = 1;
        if (Updater.hasOfficialSite()) {
            Ui.row(this, c1, rowIndex(idx++), "官方网站", Updater.SITE_URL, "↗ WEB",
                    v -> Updater.open(this, Updater.SITE_URL));
        }
        if (Updater.hasUpdateSource()) {
            View upd = Ui.row(this, c1, rowIndex(idx++), "检查更新", "正在检查…", "…", v -> check(true));
            bindUpdateRow(upd);
        }
        if (Updater.hasReleaseRepository()) {
            Ui.row(this, c1, rowIndex(idx++), "GitHub 项目主页", Updater.REPO_URL, "↗ WEB",
                    v -> Updater.open(this, Updater.REPO_URL));
            Ui.row(this, c1, rowIndex(idx++), "全部版本与更新日志", "查看发布记录", "↗ WEB",
                    v -> Updater.open(this, Updater.RELEASES_URL));
        }
        if (Updater.hasUpdateSource()) {
            Ui.row(this, c1, rowIndex(idx++), "自动检查更新", "打开应用时检查新版本",
                    Prefs.updateAutoCheck(this) ? "ON" : "OFF", v -> {
                        Prefs.setUpdateAutoCheck(this, !Prefs.updateAutoCheck(this));
                        recreate();
                    });
        }
        Ui.row(this, c1, rowIndex(idx++), "使用指南", "同步课表和成绩", "?",
                v -> LoginGuide.show(this, null));

        Ui.label(this, col, "CONTACT · 联系与赞赏");
        LinearLayout c2 = Ui.card(this, col);
        Ui.row(this, c2, "01", "联系邮箱", Updater.FEEDBACK_EMAIL, "COPY", v -> copyEmail());
        Ui.row(this, c2, "02", "赞赏码", "微信扫码支持开发", "QR", v -> showDonateDialog());

        TextView note = Ui.text(this, col,
                "非官方工具，与学校及教务系统厂商无关。教务数据以学校系统为准，成绩计算仅供参考。交院自动登录由你开启，账号密码加密保存在本机，可在设置中删除。",
                12f, G.MUTE);
        note.setPadding(Ui.dp(this, 4), Ui.dp(this, 18), Ui.dp(this, 4), 0);

        TextView ver = Ui.text(this, col, "驴驴课表 v" + BuildConfig.VERSION_NAME + " · build " + BuildConfig.VERSION_CODE,
                11f, G.MUTE);
        ver.setTypeface(android.graphics.Typeface.MONOSPACE);
        ver.setPadding(Ui.dp(this, 4), Ui.dp(this, 18), Ui.dp(this, 4), Ui.dp(this, 8));
    }

    /** Ui.row 返回的行：[序号, 文字列, 右侧]；拿到副标题和右侧标记，用来显示检查结果。 */
    private void bindUpdateRow(View row) {
        LinearLayout r = (LinearLayout) row;
        LinearLayout texts = (LinearLayout) r.getChildAt(1);
        updateSub = texts.getChildCount() > 1 ? (TextView) texts.getChildAt(1) : null;
        updateTrail = (TextView) r.getChildAt(2);
    }

    private void setUpdate(String sub, String trail, int color) {
        if (updateSub != null) updateSub.setText(sub);
        if (updateTrail != null) {
            updateTrail.setText(trail);
            updateTrail.setTextColor(color);
        }
    }

    private void check(boolean userTap) {
        if (checking) return;
        checking = true;
        setUpdate("正在检查…", "…", G.MUTE);
        Updater.fetchLatest(r -> {
            checking = false;
            if (isFinishing()) return;
            if (r == null) {
                setUpdate(Updater.hasOfficialSite()
                        ? "检查更新失败，稍后再试或访问官网"
                        : "连不上更新源，稍后再试", "重试", G.MUTE);
                return;
            }
            Prefs.setUpdateCheckedAt(this, System.currentTimeMillis());
            if (Updater.isNewer(r)) {
                setUpdate("新版本 " + r.tag + " 可以下载", "● NEW", G.ACCENT);
                if (userTap) Updater.showDialog(this, r, false);
            } else {
                setUpdate("已是最新版本", "✓", G.MUTE);
                if (userTap) Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void copyEmail() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("email", Updater.FEEDBACK_EMAIL));
        }
        Toast.makeText(this, "已复制 " + Updater.FEEDBACK_EMAIL, Toast.LENGTH_SHORT).show();
    }

    private void showDonateDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 8));

        FrameLayout frame = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, G.CARD_RADIUS));
        bg.setColor(0xFFFFFFFF);
        bg.setStroke(Math.max(1, Ui.dp(this, 1)), G.LINE);
        frame.setBackground(bg);
        frame.setClipToOutline(true);

        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.donate_wechat_qr);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setMaxHeight(Ui.dp(this, 420));
        iv.setContentDescription("微信赞赏码");
        frame.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        box.addView(frame, fl);

        TextView tip = new TextView(this);
        tip.setText("微信扫一扫，或保存图片后在微信中识别");
        tip.setTextSize(12.5f);
        tip.setTextColor(G.SUB);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, Ui.dp(this, 12), 0, 0);
        box.addView(tip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("赞赏码")
                .setView(sv)
                .setNegativeButton("关闭", null)
                .setPositiveButton("保存到相册", (d, w) -> saveDonateQr())
                .show();
    }

    private void saveDonateQr() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            shareDonateQr();
            return;
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, "lvlv-donate-wechat.jpg");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/驴驴课表");
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        ContentResolver cr = getContentResolver();
        Uri uri = null;
        try {
            uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException("相册不可用");
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) throw new IllegalStateException("写入失败");
                writeDonateQr(os);
            }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            Toast.makeText(this, "已保存到相册 · Pictures/驴驴课表", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            if (uri != null) cr.delete(uri, null, null);
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void shareDonateQr() {
        try {
            File out = ShareFiles.exportFile(this, "lvlv-donate-wechat.jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                writeDonateQr(fos);
            }
            ShareFiles.send(this, out, "image/jpeg", null, "保存或分享赞赏码");
        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void writeDonateQr(OutputStream os) throws Exception {
        InputStream raw = null;
        try {
            raw = getResources().openRawResource(R.drawable.donate_wechat_qr);
        } catch (Exception ignored) {
            // 某些 ROM 下 drawable 资源不支持直接按流读取时，退回 Bitmap 编码
        }
        if (raw != null) {
            try (InputStream is = raw) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                return;
            }
        }
        Bitmap bmp = BitmapFactory.decodeResource(getResources(), R.drawable.donate_wechat_qr);
        if (bmp == null) throw new IllegalStateException("图片读取失败");
        try {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, os)) {
                throw new IllegalStateException("图片写入失败");
            }
        } finally {
            bmp.recycle();
        }
    }
}
