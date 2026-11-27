package com.ecut.campus;

import android.app.Activity;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.ecut.campus.data.Dates;
import com.ecut.campus.data.Timetable;
import com.ecut.campus.data.TimetableStore;
import com.google.android.material.snackbar.Snackbar;

/** 课块交互弹窗：长按停课 / 调课 / 写备注，点击已标记的课块可恢复原样或编辑。 */
final class CourseBlockActions {

    private CourseBlockActions() {}

    interface Editor {
        void open(Timetable.Course c, Timetable.Session s);
    }

    static void onLongPress(Activity a, Timetable.Course c, Timetable.Session s, boolean active, String label,
                            String adjustId, boolean extra, int week, Runnable refresh, Editor editor) {
        if (label != null) {
            onMarked(a, c, s, label, adjustId, extra, refresh, editor);
            return;
        }
        if (!active) {
            editor.open(c, s);
            return;
        }
        String head = c.name + " · 第" + week + "周" + Dates.cnDay(s.dayOfWeek) + " "
                + (s.hasCustomTime() ? s.timeStart + "-" + s.timeEnd : s.startSection + "-" + s.endSection + "节");
        boolean hasNote = c.note != null && !c.note.isEmpty();
        String noteItem = hasNote ? "备注：" + (c.note.length() > 16 ? c.note.substring(0, 16) + "…" : c.note) : "写备注…";
        int pendingTodos = CourseTodoStore.load(a).pendingForCourse(c.name).size();
        String todoItem = pendingTodos > 0 ? "作业与待办（" + pendingTodos + " 项未完成）" : "作业与待办…";
        new AlertDialog.Builder(a)
                .setTitle(head)
                .setItems(new String[]{"仅本周停课", "调到其他时间…", todoItem, noteItem, "编辑课程"}, (dlg, which) -> {
                    if (which == 0) cancelOnce(a, c, s, week, refresh);
                    else if (which == 1) showMoveDialog(a, c, s, week, refresh);
                    else if (which == 2) showTodoDialog(a, c.name, refresh);
                    else if (which == 3) showNoteDialog(a, c, refresh);
                    else editor.open(c, s);
                })
                .show();
    }

    static void onMarked(Activity a, Timetable.Course c, Timetable.Session s, String label, String adjustId,
                         boolean extra, Runnable refresh, Editor editor) {
        String time = s.hasCustomTime() ? s.timeStart + "-" + s.timeEnd : s.startSection + "-" + s.endSection + "节";
        String msg;
        switch (label) {
            case "放假": msg = "这天放假，课自动置灰。"; break;
            case "停课": msg = "这一周停一次，其它周照常。"; break;
            case "已调走": msg = "这次课调到别的时间了，那边会显示「调课」。"; break;
            case "补课": msg = "调休补课日，上的是被换掉那天的课。"; break;
            default: msg = "这次课是从别的时间调过来的。"; break;
        }
        msg = Dates.cnDay(s.dayOfWeek) + " " + time + (s.location.isEmpty() ? "" : " · " + s.location) + "\n\n" + msg;
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle(c.name + " · " + label).setMessage(msg);
        if (adjustId != null && !adjustId.isEmpty()) {
            boolean dayRule = "放假".equals(label) || "补课".equals(label);
            b.setPositiveButton(dayRule ? "取消这天的安排" : "恢复原样", (dlg, w) -> {
                Timetable before = TimetableStore.load(a);
                Timetable t = TimetableStore.load(a);
                t.adjusts.removeIf(x -> adjustId.equals(x.id));
                TimetableStore.save(a, t);
                if (refresh != null) refresh.run();
                undoBar(a, "已恢复", before, refresh);
            });
            b.setNegativeButton("关闭", null);
        } else {
            b.setPositiveButton("知道了", null);
        }
        if (!extra) b.setNeutralButton("编辑课程", (dlg, w) -> editor.open(c, s));
        b.show();
    }

    /** 打开课程作业与待办专页（支持截止提醒、备注与拍照/相册附图）。 */
    static void showTodoDialog(Activity a, String courseName, Runnable refresh) {
        if (a == null || a.isFinishing()) return;
        a.startActivity(CourseTodoActivity.intent(a, courseName));
    }

    /**
     * 点击重修/选课时间冲突叠卡簇时弹出完整对比卡片，清晰展示每门冲突课程的节次、周次、教室、教师与快捷操作。
     */
    static void onConflictClick(Activity a, java.util.List<TimetableView.ConflictItem> items,
                                Timetable.Course tapped, int week, Runnable refresh, Editor editor) {
        if (a == null || a.isFinishing() || items == null || items.isEmpty()) return;
        float d = a.getResources().getDisplayMetrics().density;
        int day = items.get(0).displaySession.dayOfWeek;
        com.ecut.campus.data.CourseTodo todoBook = CourseTodoStore.load(a);

        android.widget.ScrollView scroll = new android.widget.ScrollView(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        int padH = Math.round(18 * d);
        root.setPadding(padH, Math.round(6 * d), padH, Math.round(6 * d));

        TextView hint = new TextView(a);
        hint.setText("该时段共有 " + items.size() + " 门课程（如重修或跨班选课），点选可查看完整地点或单独调整本周安排：");
        hint.setTextSize(12.5f);
        hint.setTextColor(G.SUB);
        hint.setPadding(0, 0, 0, Math.round(10 * d));
        root.addView(hint);

        final AlertDialog[] holder = new AlertDialog[1];
        for (int i = 0; i < items.size(); i++) {
            TimetableView.ConflictItem ci = items.get(i);
            Timetable.Course c = ci.course;
            Timetable.Session ds = ci.displaySession;
            Timetable.Session ps = ci.primarySession != null ? ci.primarySession : ds;
            int colorIdx = Math.abs(c.colorIndex) % TimetableView.PALETTE_BG.length;
            int barColor = TimetableView.courseFgColor(colorIdx, G.NIGHT);

            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.HORIZONTAL);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius((G.GLASS ? 14f : 6f) * d);
            bg.setColor(G.CHIP);
            bg.setStroke(Math.max(1, Math.round(d)), G.CARD_STROKE);
            card.setBackground(bg);

            View colorBar = new View(a);
            android.graphics.drawable.GradientDrawable barBg = new android.graphics.drawable.GradientDrawable();
            float rLeft = (G.GLASS ? 14f : 6f) * d;
            barBg.setCornerRadii(new float[]{rLeft, rLeft, 0, 0, 0, 0, rLeft, rLeft});
            barBg.setColor(barColor);
            colorBar.setBackground(barBg);
            card.addView(colorBar, new LinearLayout.LayoutParams(Math.round(4 * d), -1));

            LinearLayout body = new LinearLayout(a);
            body.setOrientation(LinearLayout.VERTICAL);
            int cp = Math.round(12 * d);
            body.setPadding(cp, Math.round(10 * d), cp, Math.round(10 * d));

            LinearLayout titleRow = new LinearLayout(a);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView nameTv = new TextView(a);
            String prefix = ci.label != null ? "[" + ci.label + "] " : "";
            nameTv.setText(prefix + (c.name == null ? "" : c.name));
            nameTv.setTextSize(15f);
            nameTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            nameTv.setTextColor(G.INK);
            titleRow.addView(nameTv, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView secPill = new TextView(a);
            String secText = ds.hasCustomTime()
                    ? ds.timeStart + "–" + ds.timeEnd
                    : ds.startSection + "-" + ds.endSection + "节";
            secPill.setText(secText);
            secPill.setTextSize(11f);
            secPill.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD));
            secPill.setTextColor(barColor);
            secPill.setPadding(Math.round(8 * d), Math.round(3 * d), Math.round(8 * d), Math.round(3 * d));
            android.graphics.drawable.GradientDrawable spBg = new android.graphics.drawable.GradientDrawable();
            spBg.setCornerRadius((G.GLASS ? 10f : 4f) * d);
            spBg.setColor(G.alpha(barColor, G.NIGHT ? 0x28 : 0x16));
            secPill.setBackground(spBg);
            LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(-2, -2);
            spLp.setMarginStart(Math.round(8 * d));
            titleRow.addView(secPill, spLp);
            body.addView(titleRow);

            StringBuilder meta = new StringBuilder();
            String weeksExpr = com.ecut.campus.data.WeekRange.weekExpr(ps.weeks);
            if (!weeksExpr.isEmpty()) meta.append("第").append(weeksExpr).append("周");
            String teacher = ds.teacherFor(week, c.teacher).trim();
            if (!teacher.isEmpty()) {
                if (meta.length() > 0) meta.append("  ·  ");
                meta.append(teacher);
            }
            if (ds.location != null && !ds.location.trim().isEmpty()) {
                if (meta.length() > 0) meta.append("\n");
                meta.append(ds.location.trim());
            }
            int todos = c.name == null ? 0 : todoBook.pendingForCourse(c.name).size();
            if (todos > 0) {
                meta.append(meta.length() > 0 ? "  ·  " : "").append("待办 ").append(todos).append(" 项");
            }
            if (meta.length() > 0) {
                TextView metaTv = new TextView(a);
                metaTv.setText(meta.toString());
                metaTv.setTextSize(12.5f);
                metaTv.setTextColor(G.SUB);
                metaTv.setPadding(0, Math.round(4 * d), 0, 0);
                body.addView(metaTv);
            }

            LinearLayout actions = new LinearLayout(a);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            actions.setPadding(0, Math.round(8 * d), 0, 0);

            if (ci.label == null) {
                actions.addView(conflictActionChip(a, "本周停课", false, v -> {
                    if (holder[0] != null) holder[0].dismiss();
                    cancelOnce(a, c, ps, week, refresh);
                }));
            }
            LinearLayout.LayoutParams gapLp = new LinearLayout.LayoutParams(-2, -2);
            gapLp.setMarginStart(Math.round(8 * d));
            actions.addView(conflictActionChip(a, todos > 0 ? "待办 (" + todos + ")" : "待办", false, v -> {
                if (holder[0] != null) holder[0].dismiss();
                showTodoDialog(a, c.name, refresh);
            }), gapLp);

            LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(-2, -2);
            editLp.setMarginStart(Math.round(8 * d));
            actions.addView(conflictActionChip(a, ci.label != null ? "调整" : "查看 / 编辑", true, v -> {
                if (holder[0] != null) holder[0].dismiss();
                if (ci.label != null) onMarked(a, c, ps, ci.label, ci.adjustId, ci.extra, refresh, editor);
                else editor.open(c, ps);
            }), editLp);

            body.addView(actions);
            card.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                if (ci.label != null) onMarked(a, c, ps, ci.label, ci.adjustId, ci.extra, refresh, editor);
                else editor.open(c, ps);
            });
            card.addView(body, new LinearLayout.LayoutParams(0, -2, 1f));

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) cardLp.topMargin = Math.round(10 * d);
            root.addView(card, cardLp);
        }

        scroll.addView(root);
        holder[0] = new AlertDialog.Builder(a)
                .setTitle("第 " + week + " 周" + Dates.cnDay(day) + " · 冲突课程")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .create();
        holder[0].show();
    }

    private static TextView conflictActionChip(Activity a, String text, boolean primary, View.OnClickListener onClick) {
        float d = a.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                primary ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Math.round(11 * d), Math.round(6 * d), Math.round(11 * d), Math.round(6 * d));
        tv.setMinHeight(Math.round(32 * d));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius((G.GLASS ? 16f : 5f) * d);
        if (primary) {
            bg.setColor(G.INK);
            tv.setTextColor(G.ON_INK);
        } else {
            bg.setColor(G.CARD);
            bg.setStroke(Math.max(1, Math.round(d)), G.CARD_STROKE);
            tv.setTextColor(G.INK);
        }
        tv.setBackground(bg);
        tv.setOnClickListener(onClick);
        return tv;
    }

    /** 课程备注（作业、带什么、提醒）：同名课程共用一条，课块右上角显示小点。 */
    private static void showNoteDialog(Activity a, Timetable.Course c, Runnable refresh) {
        float d = a.getResources().getDisplayMetrics().density;
        EditText et = new EditText(a);
        et.setHint("比如：周三前交第三章作业");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(2);
        et.setTextSize(14f);
        et.setTextColor(G.INK);
        et.setHintTextColor(G.MUTE);
        et.setBackgroundResource(R.drawable.bg_gauge_input);
        et.setText(c.note == null ? "" : c.note);
        et.setSelection(et.getText().length());
        LinearLayout wrap = new LinearLayout(a);
        int pad = Math.round(20 * d);
        wrap.setPadding(pad, Math.round(8 * d), pad, 0);
        wrap.addView(et, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(c.name + " · 备注")
                .setView(wrap)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dlg, w) -> saveNote(a, c.name, et.getText().toString().trim(), refresh));
        if (c.note != null && !c.note.isEmpty()) {
            b.setNeutralButton("清空", (dlg, w) -> saveNote(a, c.name, "", refresh));
        }
        b.show();
    }

    private static void saveNote(Activity a, String name, String note, Runnable refresh) {
        Timetable t = TimetableStore.load(a);
        for (Timetable.Course x : t.courses) if (x.name.equals(name)) x.note = note;
        if (!TimetableStore.save(a, t)) {
            Toast.makeText(a, "保存失败", Toast.LENGTH_SHORT).show();
            return;
        }
        if (refresh != null) refresh.run();
    }

    private static Timetable.Adjust sourceOf(String type, Timetable.Course c, Timetable.Session s, int week) {
        Timetable.Adjust adj = new Timetable.Adjust();
        adj.type = type;
        adj.name = c.name;
        adj.week = week;
        adj.day = s.dayOfWeek;
        adj.start = s.startSection;
        adj.end = s.endSection;
        return adj;
    }

    private static boolean addAdjust(Timetable t, Timetable.Adjust a) {
        for (Timetable.Adjust e : t.adjusts) {
            if (!e.sameAs(a)) continue;
            boolean upd = false;
            if (!a.toLocation.isEmpty() && !a.toLocation.equals(e.toLocation)) {
                e.toLocation = a.toLocation;
                upd = true;
            }
            if (!a.note.isEmpty() && !a.note.equals(e.note)) {
                e.note = a.note;
                upd = true;
            }
            return upd;
        }
        if (a.id.isEmpty()) {
            a.id = "a" + Long.toString(System.nanoTime() & 0xFFFFFFFFL, 36) + t.adjusts.size();
        }
        t.adjusts.add(a);
        return true;
    }

    private static void cancelOnce(Activity a, Timetable.Course c, Timetable.Session s, int week, Runnable refresh) {
        Timetable before = TimetableStore.load(a);
        Timetable t = TimetableStore.load(a);
        addAdjust(t, sourceOf(Timetable.Adjust.CANCEL, c, s, week));
        TimetableStore.save(a, t);
        if (refresh != null) refresh.run();
        undoBar(a, "第" + week + "周" + Dates.cnDay(s.dayOfWeek) + "「" + c.name + "」停一次", before, refresh);
    }

    private static NumberPicker picker(Activity a, int min, int max, int value, String[] labels) {
        NumberPicker p = new NumberPicker(a);
        p.setMinValue(min);
        p.setMaxValue(max);
        if (labels != null) p.setDisplayedValues(labels);
        p.setValue(Math.max(min, Math.min(max, value)));
        p.setWrapSelectorWheel(false);
        p.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        return p;
    }

    private static TextView caption(Activity a, String s) {
        TextView tv = new TextView(a);
        tv.setText(s);
        tv.setTextColor(G.MUTE);
        tv.setTextSize(12f);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private static void showMoveDialog(Activity a, Timetable.Course c, Timetable.Session s, int week, Runnable refresh) {
        float d = a.getResources().getDisplayMetrics().density;
        int maxWeek = Math.max(week, Prefs.totalWeeks(a));
        String[] wl = new String[maxWeek];
        for (int i = 0; i < maxWeek; i++) wl[i] = "第" + (i + 1) + "周";
        String[] dl = new String[7];
        for (int i = 0; i < 7; i++) dl[i] = Dates.cnDay(i + 1);
        String[] sl = new String[ScheduleSettingsStore.load(a).sectionCount()];
        for (int i = 0; i < sl.length; i++) sl[i] = "第" + (i + 1) + "节";

        NumberPicker pw = picker(a, 1, maxWeek, week, wl);
        NumberPicker pd = picker(a, 1, 7, s.dayOfWeek, dl);
        NumberPicker ps = picker(a, 1, sl.length, s.startSection, sl);
        NumberPicker pe = picker(a, 1, sl.length, s.endSection, sl);
        ps.setOnValueChangedListener((p, o, n) -> { if (pe.getValue() < n) pe.setValue(n); });
        pe.setOnValueChangedListener((p, o, n) -> { if (ps.getValue() > n) ps.setValue(n); });

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        for (NumberPicker p : new NumberPicker[]{pw, pd, ps, pe}) row.addView(p, lp);
        LinearLayout caps = new LinearLayout(a);
        caps.setOrientation(LinearLayout.HORIZONTAL);
        for (String cap : new String[]{"周次", "星期", "从", "到"}) caps.addView(caption(a, cap), lp);

        EditText loc = new EditText(a);
        loc.setHint("新地点（不变就留空）");
        loc.setSingleLine(true);
        loc.setTextSize(14f);
        loc.setTextColor(G.INK);
        loc.setHintTextColor(G.MUTE);
        loc.setBackgroundResource(R.drawable.bg_gauge_input);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * d);
        box.setPadding(pad, Math.round(8 * d), pad, 0);
        box.addView(caps);
        box.addView(row);
        box.addView(loc);

        new AlertDialog.Builder(a)
                .setTitle("把这次「" + c.name + "」调到")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("调过去", (dlg, w) -> {
                    Timetable before = TimetableStore.load(a);
                    Timetable t = TimetableStore.load(a);
                    Timetable.Adjust adj = sourceOf(Timetable.Adjust.MOVE, c, s, week);
                    adj.toWeek = pw.getValue();
                    adj.toDay = pd.getValue();
                    adj.toStart = ps.getValue();
                    adj.toEnd = pe.getValue();
                    adj.toLocation = loc.getText().toString().trim();
                    if (adj.toWeek == week && adj.toDay == s.dayOfWeek && adj.toStart == s.startSection
                            && adj.toEnd == s.endSection && adj.toLocation.isEmpty()) return;
                    addAdjust(t, adj);
                    TimetableStore.save(a, t);
                    if (refresh != null) refresh.run();
                    undoBar(a, "已调到第" + adj.toWeek + "周" + Dates.cnDay(adj.toDay)
                            + " " + adj.toStart + "-" + adj.toEnd + "节", before, refresh);
                })
                .show();
    }

    private static void undoBar(Activity a, String msg, Timetable before, Runnable refresh) {
        View root = a.findViewById(android.R.id.content);
        if (root == null) return;
        Snackbar.make(root, msg, 6000)
                .setAction("撤销", v -> {
                    if (before != null) TimetableStore.save(a, before);
                    if (refresh != null) refresh.run();
                })
                .show();
    }
}
