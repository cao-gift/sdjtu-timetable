package com.ecut.campus;

import android.Manifest;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.ecut.campus.data.CourseTodo;
import com.ecut.campus.data.Dates;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 课程作业与备注详情页（纸墨风格）：
 * 支持设定截止日期（自动排闹钟并在小组件提示）、写备注、拍照或从相册附加图片并点击大图预览。
 */
public final class CourseTodoActivity extends AppCompatActivity {

    static final String EXTRA_COURSE = "course_name";
    private static final int REQ_PICK_PHOTO = 301;
    private static final int REQ_TAKE_PHOTO = 302;
    private static final int REQ_CAMERA_PERM = 303;

    static Intent intent(Context c, String courseName) {
        return new Intent(c, CourseTodoActivity.class).putExtra(EXTRA_COURSE, courseName);
    }

    private String courseName = "";
    private CourseTodo book;
    private EditText titleInput, noteInput;
    private TextView dueLabelView;
    private LinearLayout draftPhotoRow, listCard;
    private String draftDue = "";
    private final List<String> draftPhotos = new ArrayList<>();
    /** 非空表示正在为某条已有待办追加图片；为 null 表示给新待办草稿加图。 */
    private String photoTargetItemId;
    private File pendingCaptureFile;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        G.init(this);
        courseName = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_COURSE);
        if (courseName == null) courseName = "";
        book = CourseTodoStore.load(this);

        LinearLayout col = Ui.page(this, "课表", courseName.isEmpty() ? "作业与待办" : courseName,
                "作业截止提醒 · 支持拍照与相册附图");

        Ui.label(this, col, "NEW · 新建作业或待办");
        LinearLayout form = Ui.card(this, col);
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        form.addView(pad, new LinearLayout.LayoutParams(-1, -2));

        titleInput = new EditText(this);
        titleInput.setHint("作业内容，如：交第 3 章习题");
        titleInput.setSingleLine(true);
        titleInput.setTextSize(15f);
        titleInput.setTextColor(G.INK);
        titleInput.setHintTextColor(G.MUTE);
        titleInput.setBackgroundResource(R.drawable.bg_gauge_input);
        pad.addView(titleInput, new LinearLayout.LayoutParams(-1, -2));

        noteInput = new EditText(this);
        noteInput.setHint("备注说明（可选，如题号、提交方式）");
        noteInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        noteInput.setMinLines(1);
        noteInput.setMaxLines(3);
        noteInput.setTextSize(13.5f);
        noteInput.setTextColor(G.INK);
        noteInput.setHintTextColor(G.MUTE);
        noteInput.setBackgroundResource(R.drawable.bg_gauge_input);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(-1, -2);
        nLp.topMargin = Ui.dp(this, 10);
        pad.addView(noteInput, nLp);

        // 截止日期快捷选择
        LinearLayout dueRow = new LinearLayout(this);
        dueRow.setOrientation(LinearLayout.HORIZONTAL);
        dueRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-1, -2);
        dLp.topMargin = Ui.dp(this, 12);
        pad.addView(dueRow, dLp);

        dueLabelView = new TextView(this);
        dueLabelView.setTypeface(Typeface.MONOSPACE);
        dueLabelView.setTextSize(12.5f);
        dueLabelView.setTextColor(G.SUB);
        dueRow.addView(dueLabelView, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView pickDueBtn = Ui.button(this, "设截止日", false, v -> showDuePicker());
        pickDueBtn.setMinHeight(Ui.dp(this, 40));
        pickDueBtn.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        pickDueBtn.setTextSize(13f);
        dueRow.addView(pickDueBtn);

        // 草稿图片预览条
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        draftPhotoRow = new LinearLayout(this);
        draftPhotoRow.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(draftPhotoRow);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(-1, -2);
        hLp.topMargin = Ui.dp(this, 10);
        pad.addView(hsv, hLp);

        // 拍照 / 相册 / 添加按钮行
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(-1, -2);
        aLp.topMargin = Ui.dp(this, 12);
        pad.addView(actions, aLp);

        TextView camBtn = Ui.button(this, "拍照", false, v -> {
            photoTargetItemId = null;
            startTakePhoto();
        });
        TextView galBtn = Ui.button(this, "相册", false, v -> {
            photoTargetItemId = null;
            startPickPhoto();
        });
        TextView addBtn = Ui.button(this, "添加", true, v -> addDraftTodo());

        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, -2, 1f);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, -2, 1f);
        b2.setMarginStart(Ui.dp(this, 8));
        LinearLayout.LayoutParams b3 = new LinearLayout.LayoutParams(0, -2, 1.2f);
        b3.setMarginStart(Ui.dp(this, 8));
        actions.addView(camBtn, b1);
        actions.addView(galBtn, b2);
        actions.addView(addBtn, b3);

        Ui.label(this, col, "LIST · 待办与图片");
        listCard = Ui.card(this, col);

        renderDraft();
        renderList();
    }

    private void showDuePicker() {
        String today = ClassPlans.ymd(System.currentTimeMillis());
        String tomorrow = Dates.addDays(today, 1);
        String after2 = Dates.addDays(today, 2);
        String nextWeek = Dates.addDays(today, 7);
        String[] opts = {
                "不设截止日期",
                "今天（" + Dates.md(today) + "）",
                "明天（" + Dates.md(tomorrow) + "）",
                "后天（" + Dates.md(after2) + "）",
                "一周后（" + Dates.md(nextWeek) + "）",
                "自定义日期…"
        };
        new AlertDialog.Builder(this)
                .setTitle("选择截止日期")
                .setItems(opts, (d, which) -> {
                    if (which == 0) { draftDue = ""; renderDraft(); }
                    else if (which == 1) { draftDue = today; renderDraft(); }
                    else if (which == 2) { draftDue = tomorrow; renderDraft(); }
                    else if (which == 3) { draftDue = after2; renderDraft(); }
                    else if (which == 4) { draftDue = nextWeek; renderDraft(); }
                    else openCalendarPicker();
                })
                .show();
    }

    private void openCalendarPicker() {
        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(this, (view, y, m, d) -> {
            draftDue = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d);
            renderDraft();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void renderDraft() {
        if (draftDue.isEmpty()) {
            dueLabelView.setText("截止日期：未设置");
            dueLabelView.setTextColor(G.MUTE);
        } else {
            dueLabelView.setText("截止：" + draftDue + "（自动提醒）");
            dueLabelView.setTextColor(G.INK);
        }
        draftPhotoRow.removeAllViews();
        draftPhotoRow.setVisibility(draftPhotos.isEmpty() ? View.GONE : View.VISIBLE);
        for (String name : new ArrayList<>(draftPhotos)) {
            draftPhotoRow.addView(thumbView(name, () -> {
                draftPhotos.remove(name);
                TodoPhotoStore.delete(this, name);
                renderDraft();
            }));
        }
    }

    private void addDraftTodo() {
        String title = titleInput.getText().toString().trim();
        if (title.isEmpty()) {
            Toast.makeText(this, "请先填写作业或待办内容", Toast.LENGTH_SHORT).show();
            return;
        }
        String note = noteInput.getText().toString().trim();
        book.add(courseName, title, draftDue, note, draftPhotos);
        CourseTodoStore.save(this, book);
        if (!draftDue.isEmpty()) Notify.requestPermissionIfNeeded(this);
        rescheduleAll();
        titleInput.setText("");
        noteInput.setText("");
        draftDue = "";
        draftPhotos.clear();
        renderDraft();
        renderList();
        Toast.makeText(this, "已添加", Toast.LENGTH_SHORT).show();
    }

    private void renderList() {
        listCard.removeAllViews();
        List<CourseTodo.Item> items = book.forCourse(courseName);
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无作业或待办。设好截止日期后，会在截止前一天晚上与当天早晨提醒，并在桌面小组件提示。");
            empty.setTextSize(13f);
            empty.setTextColor(G.MUTE);
            empty.setPadding(Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20));
            listCard.addView(empty);
            return;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                View hair = new View(this);
                hair.setBackgroundColor(G.LINE);
                listCard.addView(hair, new LinearLayout.LayoutParams(-1, 1));
            }
            listCard.addView(buildItemRow(items.get(i), now));
        }
    }

    private View buildItemRow(CourseTodo.Item item, long now) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView check = new TextView(this);
        check.setText(item.done ? "■ " : "□ ");
        check.setTypeface(Typeface.MONOSPACE);
        check.setTextColor(item.done ? G.MUTE : G.alarm());
        check.setTextSize(16f);
        check.setPadding(0, 0, Ui.dp(this, 4), 0);
        top.addView(check);

        TextView titleTv = new TextView(this);
        titleTv.setText(item.title);
        titleTv.setTextSize(15f);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setTextColor(item.done ? G.MUTE : G.INK);
        if (item.done) titleTv.setPaintFlags(titleTv.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        top.addView(titleTv, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView addPic = new TextView(this);
        addPic.setText("＋图");
        addPic.setTypeface(Typeface.MONOSPACE);
        addPic.setTextSize(11.5f);
        addPic.setTextColor(G.SUB);
        addPic.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        addPic.setBackgroundResource(Ui.selectable(this, false));
        addPic.setOnClickListener(v -> showAttachDialog(item.id));
        top.addView(addPic);

        TextView del = new TextView(this);
        del.setText("×");
        del.setTextSize(20f);
        del.setTextColor(G.MUTE);
        del.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        del.setOnClickListener(v -> {
            for (String ph : item.photos) TodoPhotoStore.delete(this, ph);
            book.remove(item.id);
            CourseTodoStore.save(this, book);
            rescheduleAll();
            renderList();
        });
        top.addView(del);

        top.setOnClickListener(v -> {
            book.toggle(item.id);
            CourseTodoStore.save(this, book);
            rescheduleAll();
            renderList();
        });
        box.addView(top);

        String dueText = CourseTodo.dueLabel(item, now);
        if (!dueText.isEmpty()) {
            TextView dueTv = new TextView(this);
            dueTv.setText(dueText);
            dueTv.setTypeface(Typeface.MONOSPACE);
            dueTv.setTextSize(11.5f);
            int days = CourseTodo.daysUntilDue(item, now);
            boolean urgent = !item.done && days <= 1 && days != Integer.MAX_VALUE;
            dueTv.setTextColor(item.done ? G.MUTE : (urgent ? G.alarm() : G.SUB));
            dueTv.setPadding(Ui.dp(this, 24), Ui.dp(this, 4), 0, 0);
            box.addView(dueTv);
        }

        if (!item.note.isEmpty()) {
            TextView noteTv = new TextView(this);
            noteTv.setText(item.note);
            noteTv.setTextSize(13f);
            noteTv.setTextColor(item.done ? G.MUTE : G.SUB);
            noteTv.setPadding(Ui.dp(this, 24), Ui.dp(this, 4), 0, 0);
            box.addView(noteTv);
        }

        if (!item.photos.isEmpty()) {
            HorizontalScrollView hsv = new HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            LinearLayout strip = new LinearLayout(this);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), 0, 0);
            for (String ph : new ArrayList<>(item.photos)) {
                strip.addView(thumbView(ph, () -> {
                    book.removePhoto(item.id, ph);
                    TodoPhotoStore.delete(this, ph);
                    CourseTodoStore.save(this, book);
                    renderList();
                }));
            }
            hsv.addView(strip);
            box.addView(hsv, new LinearLayout.LayoutParams(-1, -2));
        }

        return box;
    }

    private View thumbView(String filename, Runnable onDelete) {
        int size = Ui.dp(this, 68);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, G.SMALL_RADIUS));
        bg.setColor(G.CHIP);
        bg.setStroke(1, G.LINE);
        iv.setBackground(bg);
        iv.setClipToOutline(true);
        Bitmap bmp = TodoPhotoStore.decodeThumb(this, filename, size * 2);
        if (bmp != null) iv.setImageBitmap(bmp);
        iv.setContentDescription("作业图片附件，点击放大查看");
        iv.setOnClickListener(v -> showPhotoViewer(filename, onDelete));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginEnd(Ui.dp(this, 8));
        iv.setLayoutParams(lp);
        return iv;
    }

    private void showPhotoViewer(String filename, Runnable onDelete) {
        Bitmap bmp = TodoPhotoStore.decodeThumb(this, filename, 1280);
        if (bmp == null) {
            Toast.makeText(this, "图片文件已不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 4));
        new AlertDialog.Builder(this)
                .setView(iv)
                .setPositiveButton("关闭", null)
                .setNegativeButton("删除图片", (d, w) -> onDelete.run())
                .show();
    }

    private void showAttachDialog(String itemId) {
        photoTargetItemId = itemId;
        new AlertDialog.Builder(this)
                .setTitle("添加图片")
                .setItems(new String[]{"拍照", "从相册选择"}, (d, which) -> {
                    if (which == 0) startTakePhoto();
                    else startPickPhoto();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void startPickPhoto() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "选择作业图片"), REQ_PICK_PHOTO);
    }

    private void startTakePhoto() {
        if (Build.VERSION.SDK_INT >= 23 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERM);
            return;
        }
        try {
            pendingCaptureFile = new File(TodoPhotoStore.dir(this), TodoPhotoStore.newFilename());
            Uri uri = TodoPhotoStore.uriForCapture(this, pendingCaptureFile);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_TAKE_PHOTO);
        } catch (Throwable e) {
            Toast.makeText(this, "无法打开相机：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_PERM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startTakePhoto();
            } else {
                Toast.makeText(this, "未授予相机权限，可改从相册选择图片", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            if (requestCode == REQ_TAKE_PHOTO && pendingCaptureFile != null) {
                pendingCaptureFile.delete();
                pendingCaptureFile = null;
            }
            return;
        }
        if (requestCode == REQ_PICK_PHOTO && data != null && data.getData() != null) {
            String saved = TodoPhotoStore.importFromUri(this, data.getData());
            if (saved == null) {
                Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show();
                return;
            }
            attachSavedPhoto(saved);
        } else if (requestCode == REQ_TAKE_PHOTO && pendingCaptureFile != null) {
            File f = pendingCaptureFile;
            pendingCaptureFile = null;
            if (!TodoPhotoStore.compressInPlace(f)) {
                f.delete();
                Toast.makeText(this, "拍照保存失败", Toast.LENGTH_SHORT).show();
                return;
            }
            attachSavedPhoto(f.getName());
        }
    }

    private void attachSavedPhoto(String filename) {
        if (photoTargetItemId != null) {
            book.addPhoto(photoTargetItemId, filename);
            CourseTodoStore.save(this, book);
            photoTargetItemId = null;
            renderList();
        } else {
            draftPhotos.add(filename);
            renderDraft();
        }
    }

    private void rescheduleAll() {
        ClassReminder.reschedule(this);
        TodoReminder.reschedule(this);
        ClassWidget.updateAll(getApplicationContext());
    }
}
