package com.ecut.campus.fun;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.ecut.campus.G;
import com.ecut.campus.R;
import com.ecut.campus.Ui;
import com.ecut.campus.data.GpaSimulator;
import com.ecut.campus.data.Grades;
import com.ecut.campus.data.GradesStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 小工具：GPA 目标模拟器（纸墨 Gauge 风格）。
 * - TARGET · 目标反推：输入目标 GPA 与剩余学分，实时算出所需均分与均绩。
 * - SIMULATE · 预估课程：添加多门预估成绩课程，实时查看总 GPA 变化。
 */
public class GpaSimulatorActivity extends AppCompatActivity {

    private GpaSimulator.Baseline baseline;
    private boolean customBaseline;
    private final List<GpaSimulator.PlannedCourse> planned = new ArrayList<>();

    private TextView tvBaseGpa, tvBaseMeta;
    private EditText etTargetGpa, etRemainCredits;
    private TextView tvTargetHeadline, tvTargetDetail;
    private EditText etCourseName, etCourseCredit, etCourseScore;
    private TextView tvProjGpa, tvProjMeta;
    private LinearLayout plannedListBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        G.init(this);
        Grades grades = GradesStore.load(this);
        grades.recomputeAll();
        baseline = GpaSimulator.Baseline.fromGrades(grades);

        LinearLayout col = Ui.page(this, "小工具", "GPA 目标模拟器", "反推目标均分 · 预估课程绩点变化");

        // ===== 顶部基准卡 =====
        LinearLayout hero = Ui.card(this, col);
        LinearLayout heroPad = paddedBox(18, 16, 18, 16);
        hero.addView(heroPad);

        LinearLayout heroTop = new LinearLayout(this);
        heroTop.setOrientation(LinearLayout.HORIZONTAL);
        heroTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView cap = monoText("CURRENT · 当前基准", 11f, G.MUTE, false);
        heroTop.addView(cap, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView editBase = monoText("自定义基准 ›", 11.5f, G.INK, true);
        editBase.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), 0, Ui.dp(this, 4));
        editBase.setOnClickListener(v -> showEditBaselineDialog());
        heroTop.addView(editBase);
        heroPad.addView(heroTop);

        tvBaseGpa = monoText("0.00", 42f, G.INK, true);
        tvBaseGpa.setPadding(0, Ui.dp(this, 4), 0, 0);
        heroPad.addView(tvBaseGpa);

        tvBaseMeta = Ui.text(this, heroPad, "", 12.5f, G.SUB);
        tvBaseMeta.setTypeface(Typeface.MONOSPACE);

        // ===== 01 目标反推 =====
        Ui.label(this, col, "TARGET · 目标反推");
        LinearLayout targetCard = Ui.card(this, col);
        LinearLayout targetPad = paddedBox(18, 16, 18, 16);
        targetCard.addView(targetPad);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        etTargetGpa = inputBox("目标 GPA", "如 3.20");
        etRemainCredits = inputBox("剩余学分", "如 20");
        etTargetGpa.setText(defaultTargetText());
        etRemainCredits.setText("20");

        LinearLayout leftCol = labeledInput("目标 GPA（满分 5.0）", etTargetGpa);
        LinearLayout rightCol = labeledInput("剩余 / 计划学分", etRemainCredits);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, -2, 1f);
        rLp.setMarginStart(Ui.dp(this, 10));
        inputRow.addView(leftCol, new LinearLayout.LayoutParams(0, -2, 1f));
        inputRow.addView(rightCol, rLp);
        targetPad.addView(inputRow);

        // 快捷目标胶囊
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 12));
        for (String val : new String[]{"3.00", "3.20", "3.50", "3.80", "4.00"}) {
            TextView chip = monoText(val, 11.5f, G.SUB, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
            GradientDrawable cbg = new GradientDrawable();
            cbg.setCornerRadius(Ui.dp(this, G.SMALL_RADIUS));
            cbg.setColor(G.CHIP);
            chip.setBackground(cbg);
            chip.setOnClickListener(v -> {
                etTargetGpa.setText(val);
                etTargetGpa.setSelection(val.length());
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (presetRow.getChildCount() > 0) clp.setMarginStart(Ui.dp(this, 6));
            presetRow.addView(chip, clp);
        }
        targetPad.addView(presetRow);

        // 结果框
        LinearLayout resultBox = paddedBox(14, 12, 14, 12);
        GradientDrawable rbg = new GradientDrawable();
        rbg.setCornerRadius(Ui.dp(this, G.SMALL_RADIUS));
        rbg.setColor(G.CHIP);
        resultBox.setBackground(rbg);
        tvTargetHeadline = monoText("—", 24f, G.INK, true);
        resultBox.addView(tvTargetHeadline);
        tvTargetDetail = Ui.text(this, resultBox, "", 12.5f, G.SUB);
        tvTargetDetail.setPadding(0, Ui.dp(this, 4), 0, 0);
        targetPad.addView(resultBox);

        TextWatcher recalc = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refreshTarget(); }
            @Override public void afterTextChanged(Editable s) {}
        };
        etTargetGpa.addTextChangedListener(recalc);
        etRemainCredits.addTextChangedListener(recalc);

        // ===== 02 预估课程 =====
        Ui.label(this, col, "SIMULATE · 预估课程成绩");
        LinearLayout simCard = Ui.card(this, col);
        LinearLayout simPad = paddedBox(18, 16, 18, 16);
        simCard.addView(simPad);

        LinearLayout projBar = new LinearLayout(this);
        projBar.setOrientation(LinearLayout.HORIZONTAL);
        projBar.setGravity(Gravity.BOTTOM);
        tvProjGpa = monoText("0.00", 30f, G.INK, true);
        projBar.addView(tvProjGpa);
        tvProjMeta = Ui.text(this, projBar, "", 12f, G.SUB);
        tvProjMeta.setTypeface(Typeface.MONOSPACE);
        tvProjMeta.setPadding(Ui.dp(this, 10), 0, 0, Ui.dp(this, 4));
        simPad.addView(projBar);

        etCourseName = new EditText(this);
        etCourseName.setHint("课程名（可选，如 高等数学）");
        etCourseName.setSingleLine(true);
        etCourseName.setTextSize(14f);
        etCourseName.setTextColor(G.INK);
        etCourseName.setHintTextColor(G.MUTE);
        etCourseName.setBackgroundResource(R.drawable.bg_gauge_input);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 44));
        nameLp.topMargin = Ui.dp(this, 12);
        simPad.addView(etCourseName, nameLp);

        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams addRowLp = new LinearLayout.LayoutParams(-1, -2);
        addRowLp.topMargin = Ui.dp(this, 8);
        etCourseCredit = inputBox("学分", "学分 如 3.0");
        etCourseScore = inputBox("预估分", "分数 如 85");
        TextView addBtn = Ui.button(this, "添加", true, v -> addPlannedCourse());
        addBtn.setMinHeight(Ui.dp(this, 44));
        addBtn.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);

        addRow.addView(etCourseCredit, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f);
        scLp.setMarginStart(Ui.dp(this, 8));
        addRow.addView(etCourseScore, scLp);
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 44));
        abLp.setMarginStart(Ui.dp(this, 8));
        addRow.addView(addBtn, abLp);
        simPad.addView(addRow, addRowLp);

        plannedListBox = new LinearLayout(this);
        plannedListBox.setOrientation(LinearLayout.VERTICAL);
        plannedListBox.setPadding(0, Ui.dp(this, 10), 0, 0);
        simPad.addView(plannedListBox);

        refreshAll();
    }

    private String defaultTargetText() {
        if (baseline.credits <= 0) return "3.50";
        double bumped = Math.min(4.8, Math.ceil((baseline.gpa + 0.15) * 10.0) / 10.0);
        return String.format(Locale.US, "%.2f", Math.max(3.0, bumped));
    }

    private void refreshAll() {
        tvBaseGpa.setText(baseline.credits > 0 ? String.format(Locale.US, "%.2f", baseline.gpa) : "—");
        if (baseline.credits <= 0) {
            tvBaseMeta.setText("本地暂无已同步成绩 · 点右上角可手动输入当前 GPA 与学分");
        } else {
            tvBaseMeta.setText(String.format(Locale.US, "已修 %.1f 学分%s",
                    baseline.credits, customBaseline ? " · 手动基准" : (" · " + baseline.courseCount + " 门计绩点课程")));
        }
        refreshTarget();
        refreshProjection();
    }

    private void refreshTarget() {
        double target = parseDouble(etTargetGpa.getText().toString(), -1);
        double remain = parseDouble(etRemainCredits.getText().toString(), -1);
        GpaSimulator.TargetResult res = GpaSimulator.computeTarget(baseline, target, remain);
        tvTargetHeadline.setText(res.headline);
        if (res.status == GpaSimulator.TargetResult.Status.IMPOSSIBLE) {
            tvTargetHeadline.setTextColor(G.alarm());
        } else if (res.status == GpaSimulator.TargetResult.Status.REACHED) {
            tvTargetHeadline.setTextColor(G.OK);
        } else {
            tvTargetHeadline.setTextColor(G.INK);
        }
        tvTargetDetail.setText(res.detail);
    }

    private void addPlannedCourse() {
        double credit = parseDouble(etCourseCredit.getText().toString(), -1);
        double score = parseDouble(etCourseScore.getText().toString(), -1);
        if (credit <= 0 || credit > 40) {
            Toast.makeText(this, "请填写有效学分（如 2.0 或 3.5）", Toast.LENGTH_SHORT).show();
            return;
        }
        if (score < 0 || score > 100) {
            Toast.makeText(this, "请填写 0–100 的预估分数", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = etCourseName.getText().toString().trim();
        if (name.isEmpty()) name = "预估课程 " + (planned.size() + 1);
        planned.add(new GpaSimulator.PlannedCourse(name, credit, score));
        etCourseName.setText("");
        etCourseScore.setText("");
        refreshProjection();
    }

    private void refreshProjection() {
        GpaSimulator.Projection p = GpaSimulator.project(baseline, planned);
        tvProjGpa.setText(p.totalCredits > 0 ? String.format(Locale.US, "%.2f", p.projectedGpa) : "—");
        if (planned.isEmpty()) {
            tvProjMeta.setText("添加下方课程即可实时测算");
            tvProjMeta.setTextColor(G.MUTE);
        } else {
            String sign = p.delta > 0 ? "+" : "";
            tvProjMeta.setText(String.format(Locale.US, "预计总 GPA（%s%.2f · +%s 学分）",
                    sign, p.delta, formatNum(p.addedCredits)));
            tvProjMeta.setTextColor(p.delta < 0 ? G.alarm() : G.SUB);
        }

        plannedListBox.removeAllViews();
        for (int i = 0; i < planned.size(); i++) {
            final int idx = i;
            GpaSimulator.PlannedCourse c = planned.get(i);
            View hair = new View(this);
            hair.setBackgroundColor(G.LINE);
            plannedListBox.addView(hair, new LinearLayout.LayoutParams(-1, 1));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            TextView title = Ui.text(this, left, c.name, 14f, G.INK);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            TextView sub = monoText(String.format(Locale.US, "%s 学分 · %.1f 分 → 绩点 %.2f",
                    formatNum(c.credit), c.score, c.gradePoint()), 11.5f, G.SUB, false);
            sub.setPadding(0, Ui.dp(this, 2), 0, 0);
            left.addView(sub);
            row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView del = monoText("×", 20f, G.MUTE, false);
            del.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
            del.setOnClickListener(v -> {
                planned.remove(idx);
                refreshProjection();
            });
            row.addView(del);
            plannedListBox.addView(row);
        }
    }

    private void showEditBaselineDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 22);
        box.setPadding(p, Ui.dp(this, 10), p, 0);

        EditText etGpa = inputBox("当前 GPA", "例如 3.10");
        EditText etCr = inputBox("已修学分", "例如 60");
        if (baseline.credits > 0) {
            etGpa.setText(String.format(Locale.US, "%.2f", baseline.gpa));
            etCr.setText(formatNum(baseline.credits));
        }
        box.addView(labeledInput("当前总 GPA", etGpa));
        LinearLayout crCol = labeledInput("已修总学分", etCr);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 10);
        box.addView(crCol, lp);

        new AlertDialog.Builder(this)
                .setTitle("自定义当前基准")
                .setMessage("仅用于本次模拟，不修改本地成绩档案。")
                .setView(box)
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复本地成绩", (d, w) -> {
                    Grades g = GradesStore.load(this);
                    g.recomputeAll();
                    baseline = GpaSimulator.Baseline.fromGrades(g);
                    customBaseline = false;
                    refreshAll();
                })
                .setPositiveButton("应用", (d, w) -> {
                    double gpa = parseDouble(etGpa.getText().toString(), -1);
                    double cr = parseDouble(etCr.getText().toString(), -1);
                    if (gpa < 0 || gpa > 5.0 || cr < 0) {
                        Toast.makeText(this, "请输入 0–5.0 的 GPA 与非负学分", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    baseline = GpaSimulator.Baseline.of(gpa, cr);
                    customBaseline = true;
                    refreshAll();
                })
                .show();
    }

    private LinearLayout paddedBox(int l, int t, int r, int b) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, l), Ui.dp(this, t), Ui.dp(this, r), Ui.dp(this, b));
        return box;
    }

    private LinearLayout labeledInput(String label, EditText et) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView tv = monoText(label, 11f, G.MUTE, false);
        tv.setPadding(0, 0, 0, Ui.dp(this, 5));
        col.addView(tv);
        col.addView(et, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
        return col;
    }

    private EditText inputBox(String desc, String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setContentDescription(desc);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setTypeface(Typeface.MONOSPACE);
        et.setTextSize(14.5f);
        et.setTextColor(G.INK);
        et.setHintTextColor(G.MUTE);
        et.setBackgroundResource(R.drawable.bg_gauge_input);
        return et;
    }

    private TextView monoText(String text, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL));
        return tv;
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        try { return Double.parseDouble(raw.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static String formatNum(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) return String.valueOf((long) Math.rint(v));
        return String.format(Locale.US, "%.1f", v);
    }
}
