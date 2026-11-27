package com.ecut.campus;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.List;

/** 二级页：同学。分享我的课表、添加同学、同学列表 → 共同空闲（三级）。 */
public class ClassmatesActivity extends AppCompatActivity {

    private static final int REQ_PICK_QR = 501;
    private static final int REQ_CAPTURE_QR = 502;
    private File pendingQrCapture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        G.init(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        LinearLayout col = Ui.page(this, "设置", "同学", "导入课表，查看共同空闲");

        Ui.label(this, col, "ME · 我的课表");
        LinearLayout me = Ui.card(this, col);
        String name = Prefs.shareName(this);
        Ui.row(this, me, "01", "分享我的课表", "生成二维码或分享码", "›",
                v -> startActivity(new Intent(this, ShareActivity.class)));
        Ui.row(this, me, "02", "分享时显示的名字", name.isEmpty() ? "未设置（默认显示「同学」）" : name, "改",
                v -> editName());

        Ui.label(this, col, "ADD · 添加同学");
        LinearLayout add = Ui.card(this, col);
        Ui.row(this, add, "01", "识别同学二维码", "拍照或从相册识别课表二维码", "QR", v -> scan());
        Ui.row(this, add, "02", "粘贴分享码", "粘贴整段消息自动识别", "PASTE", v -> paste());

        List<FriendStore.Friend> friends = FriendStore.load(this);
        Ui.label(this, col, "FRIENDS · 同学 " + friends.size());
        LinearLayout list = Ui.card(this, col);
        if (friends.isEmpty()) {
            Ui.row(this, list, "--", "还没有同学", "添加后可查看共同空闲", "", null);
        } else {
            if (friends.size() >= 2) {
                Ui.row(this, list, "ALL", "大家的共同空闲", (friends.size() + 1) + " 人共同空闲时段", "›",
                        v -> startActivity(new Intent(this, FreeTimeActivity.class)
                                .putExtra(FreeTimeActivity.EXTRA_ALL, true)));
            }
            int i = 1;
            for (FriendStore.Friend f : friends) {
                int n = f.shared.timetable.courses.size();
                android.view.View r = Ui.row(this, list, String.format("%02d", i++), f.name,
                        n + " 门课 · 点查空闲 · 长按管理", "›",
                        v -> startActivity(new Intent(this, FreeTimeActivity.class)
                                .putExtra(FreeTimeActivity.EXTRA_FRIEND, f.id)));
                r.setOnLongClickListener(v -> {
                    manage(f, v);
                    return true;
                });
            }
        }
    }

    private void editName() {
        EditText et = new EditText(this);
        et.setText(Prefs.shareName(this));
        et.setSingleLine(true);
        et.setHint("例如：小董");
        et.setBackgroundResource(R.drawable.bg_gauge_input);
        LinearLayout box = Ui.dialogBox(this, et, Ui.dp(this, 46));
        new AlertDialog.Builder(this).setTitle("分享时显示的名字").setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    Prefs.setShareName(this, et.getText().toString().trim());
                    render();
                }).show();
    }

    private void scan() {
        new AlertDialog.Builder(this)
                .setTitle("识别同学二维码")
                .setItems(new String[]{"拍照识别（调用系统相机）", "从相册选择二维码图片"}, (d, which) -> {
                    if (which == 0) captureQrPhoto();
                    else pickQrPhoto();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickQrPhoto() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "选择课表二维码图片"), REQ_PICK_QR);
    }

    private void captureQrPhoto() {
        try {
            File dir = new File(getCacheDir(), "qr_scan");
            if (!dir.exists()) dir.mkdirs();
            pendingQrCapture = new File(dir, "qr_" + System.currentTimeMillis() + ".jpg");
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", pendingQrCapture);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAPTURE_QR);
        } catch (Throwable e) {
            Toast.makeText(this, "无法打开相机，可改从相册选择二维码图片", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            if (requestCode == REQ_CAPTURE_QR && pendingQrCapture != null) {
                pendingQrCapture.delete();
                pendingQrCapture = null;
            }
            return;
        }
        Uri target = null;
        File toClean = null;
        if (requestCode == REQ_PICK_QR && data != null) {
            target = data.getData();
        } else if (requestCode == REQ_CAPTURE_QR && pendingQrCapture != null) {
            toClean = pendingQrCapture;
            pendingQrCapture = null;
            target = Uri.fromFile(toClean);
        }
        if (target == null) return;
        final Uri uri = target;
        final File cleanup = toClean;
        Toast.makeText(this, "正在识别二维码…", Toast.LENGTH_SHORT).show();
        Bg.io(() -> {
            try {
                return QrBitmap.decodeUri(getApplicationContext(), uri);
            } finally {
                if (cleanup != null) cleanup.delete();
            }
        }, decoded -> {
            if (isFinishing() || isDestroyed()) return;
            if (decoded == null || decoded.trim().isEmpty()) {
                Toast.makeText(this, "未识别出二维码，请确保画面清晰或直接粘贴分享码", Toast.LENGTH_LONG).show();
            } else {
                importText(decoded);
            }
        });
    }

    private void paste() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        String text = clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null
                ? clip.getItemAt(0).getText().toString() : "";
        if (text.contains(com.ecut.campus.data.TimetableShare.PREFIX)) {
            importText(text);
            return;
        }
        EditText et = new EditText(this);
        et.setHint("把同学发来的整段消息粘贴到这里");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(3);
        et.setBackgroundResource(R.drawable.bg_gauge_input);
        LinearLayout box = Ui.dialogBox(this, et, -2);
        new AlertDialog.Builder(this).setTitle("粘贴分享码").setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", (d, w) -> importText(et.getText().toString()))
                .show();
    }

    private void importText(String text) {
        FriendStore.Friend f = FriendStore.add(this, text, null);
        if (f == null) {
            Toast.makeText(this, "没认出课表分享码", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "已添加「" + f.name + "」的课表 · " + f.shared.timetable.courses.size() + " 门",
                Toast.LENGTH_SHORT).show();
        render();
        offerSchedule(f);
    }

    /** 同学的分享码带了作息、而且和我的不一样：问一句要不要直接用（外校同学一人配好、全班沿用）。 */
    private void offerSchedule(FriendStore.Friend f) {
        ScheduleSettings theirs = ScheduleSettings.fromShare(f.shared.schedule);
        if (theirs == null) return;
        ScheduleSettings mine = ScheduleSettingsStore.load(this);
        if (mine.sameTimesAs(theirs)) return;
        StringBuilder sb = new StringBuilder();
        int n = theirs.sectionCount();
        for (int i = 1; i <= n; i++) {
            sb.append("第").append(i).append("节  ").append(theirs.rangeLabel(i));
            if (i < n) sb.append('\n');
        }
        new AlertDialog.Builder(this)
                .setTitle("「" + f.name + "」的上课时间和你的不一样")
                .setMessage("要换成 TA 的吗？同校同学一般一样，换了以后课表、提醒都按这个时间。\n\n" + sb)
                .setNegativeButton("不用", null)
                .setPositiveButton("用 TA 的", (d, w) -> {
                    // 我的课表用到的节次比 TA 多时，补够节数，免得课被藏起来
                    theirs.ensureCovers(ScheduleSettings.maxSectionUsed(
                            com.ecut.campus.data.TimetableStore.load(this)));
                    if (ScheduleSettingsStore.save(this, theirs)) {
                        Toast.makeText(this, "已换成「" + f.name + "」的上课时间", Toast.LENGTH_SHORT).show();
                        ClassReminder.reschedule(this);
                        ClassWidget.updateAll(this);
                    }
                })
                .show();
    }

    private void manage(FriendStore.Friend f, android.view.View anchor) {
        new GaugeMenu(this).add("改名").divider().add("删除")
                .show(anchor, t -> {
                    if ("删除".equals(t)) {
                        new AlertDialog.Builder(this).setTitle("删除「" + f.name + "」？")
                                .setMessage("只会删掉你手机上的这份课表。")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("删除", (d, w) -> {
                                    FriendStore.remove(this, f.id);
                                    render();
                                }).show();
                    } else {
                        EditText et = new EditText(this);
                        et.setText(f.name);
                        et.setSingleLine(true);
                        et.setBackgroundResource(R.drawable.bg_gauge_input);
                        LinearLayout box = Ui.dialogBox(this, et, Ui.dp(this, 46));
                        new AlertDialog.Builder(this).setTitle("改名").setView(box)
                                .setNegativeButton("取消", null)
                                .setPositiveButton("保存", (d, w) -> {
                                    String n = et.getText().toString().trim();
                                    if (!n.isEmpty()) FriendStore.rename(this, f.id, n);
                                    render();
                                }).show();
                    }
                });
    }
}
