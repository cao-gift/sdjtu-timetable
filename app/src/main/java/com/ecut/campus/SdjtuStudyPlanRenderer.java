package com.ecut.campus;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.ecut.campus.data.SdjtuStudyFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 培养方案页面的统计卡、学期刻度条与分类课程卡片渲染（从 SdjtuStudyActivity 拆出）。
 */
final class SdjtuStudyPlanRenderer {

    static final class CourseEntry {
        final String name, code, term, credit, hours, nature, kind, status;

        CourseEntry(String name, String code, String term, String credit,
                    String hours, String nature, String kind, String status) {
            this.name = name;
            this.code = code;
            this.term = term;
            this.credit = credit;
            this.hours = hours;
            this.nature = nature;
            this.kind = kind;
            this.status = status;
        }
    }

    static final class RowsResult {
        final List<SdjtuStudyActivity.Item> items;
        final String tallyText;

        RowsResult(List<SdjtuStudyActivity.Item> items, String tallyText) {
            this.items = items;
            this.tallyText = tallyText;
        }
    }

    private final Activity a;

    SdjtuStudyPlanRenderer(Activity a) {
        this.a = a;
    }

    void renderHeroCard(LinearLayout heroCard, @Nullable JSONObject data, @Nullable JSONObject choiceData,
                        long syncedAt, Runnable onSwitchPlan) {
        heroCard.removeAllViews();
        if (data == null) {
            heroCard.setVisibility(View.GONE);
            return;
        }
        heroCard.setVisibility(View.VISIBLE);
        heroCard.setClickable(false);
        heroCard.setOnClickListener(null);

        JSONArray allCourses = data.optJSONArray("courses");
        SdjtuStudyFormat.PlanStats stats = SdjtuStudyFormat.computePlanStats(allCourses);

        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView cap = text(11, G.MUTE);
        cap.setTypeface(Typeface.MONOSPACE);
        String syncLabel = syncedAt == 0 ? "尚未同步" : "同步于 "
                + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(syncedAt));
        cap.setText("CURRICULUM · " + syncLabel);
        top.addView(cap, new LinearLayout.LayoutParams(0, -2, 1f));

        JSONObject options = choiceData != null ? choiceData : data;
        JSONArray plans = options == null ? null : options.optJSONArray("planChoices");
        if (plans != null && plans.length() > 1) {
            TextView switchPlan = text(12, G.INK);
            switchPlan.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            switchPlan.setText("切换方案 ›");
            switchPlan.setOnClickListener(v -> onSwitchPlan.run());
            top.addView(switchPlan);
        }
        heroCard.addView(top);

        String planName = data.optString("planChoice").trim();
        if (!planName.isEmpty()) {
            TextView pn = text(13.5f, G.SUB);
            pn.setPadding(0, dp(4), 0, dp(2));
            pn.setText(planName);
            heroCard.addView(pn);
        }

        LinearLayout metrics = new LinearLayout(a);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.setPadding(0, dp(10), 0, dp(2));
        metrics.addView(metricCol(String.valueOf(stats.courseCount), "总课程（门）"),
                new LinearLayout.LayoutParams(0, -2, 1f));
        metrics.addView(metricCol(SdjtuStudyFormat.formatCredit(stats.totalCredit), "总学分"),
                new LinearLayout.LayoutParams(0, -2, 1f));
        metrics.addView(metricCol(String.valueOf(stats.requiredCount), "必修课（门）"),
                new LinearLayout.LayoutParams(0, -2, 1f));
        heroCard.addView(metrics);
    }

    private LinearLayout metricCol(String value, String caption) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView num = text(26, G.INK);
        num.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        num.setText(value);
        col.addView(num);
        TextView cap = text(11, G.MUTE);
        cap.setPadding(0, dp(2), 0, 0);
        cap.setText(caption);
        col.addView(cap);
        return col;
    }

    /** 渲染学期横向刻度条，返回校正后的 term。 */
    String renderTermBar(LinearLayout termBarBox, @Nullable JSONObject data, String currentTerm,
                         java.util.function.Consumer<String> onSelect) {
        termBarBox.removeAllViews();
        if (data == null) {
            termBarBox.setVisibility(View.GONE);
            return currentTerm;
        }
        JSONArray courses = data.optJSONArray("courses");
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (int i = 0; courses != null && i < courses.length(); i++) {
            JSONObject c = courses.optJSONObject(i);
            if (c != null && !c.optString("term").trim().isEmpty()) {
                terms.add(c.optString("term").trim());
            }
        }
        if (terms.isEmpty()) {
            termBarBox.setVisibility(View.GONE);
            return "";
        }
        String effective = (!currentTerm.isEmpty() && !terms.contains(currentTerm)) ? "" : currentTerm;
        termBarBox.setVisibility(View.VISIBLE);
        addTermTab(termBarBox, "", "全部学期", effective, onSelect);
        for (String t : terms) {
            addTermTab(termBarBox, t, termLabel(t), effective, onSelect);
        }
        return effective;
    }

    private void addTermTab(LinearLayout termBarBox, String termId, String label, String selectedTerm,
                            java.util.function.Consumer<String> onSelect) {
        boolean selected = termId.equals(selectedTerm);
        LinearLayout tab = new LinearLayout(a);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setPadding(0, 0, dp(18), 0);

        TextView tv = new TextView(a);
        tv.setText(label);
        tv.setTextSize(13f);
        tv.setTypeface(selected ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) : Typeface.MONOSPACE);
        tv.setTextColor(selected ? G.INK : G.MUTE);
        tv.setPadding(0, dp(4), 0, dp(8));
        tv.setSingleLine(true);
        tab.addView(tv);

        View line = new View(a);
        line.setBackgroundColor(selected ? G.INK : 0x00000000);
        tab.addView(line, new LinearLayout.LayoutParams(-1, dp(2)));

        tab.setOnClickListener(v -> onSelect.accept(termId));
        termBarBox.addView(tab);
    }

    static String termLabel(String value) {
        return value.matches("[0-9]{1,2}") ? "第" + value + "学期" : value;
    }

    RowsResult buildRows(@Nullable JSONObject data, String needle, String term) {
        List<SdjtuStudyActivity.Item> items = new ArrayList<>();
        JSONArray records = data == null ? null : data.optJSONArray("courses");
        Map<String, List<CourseEntry>> groups = new LinkedHashMap<>();
        Map<String, Double> groupCredits = new LinkedHashMap<>();
        int count = 0;
        double filteredCredit = 0.0;

        for (int i = 0; records != null && i < records.length(); i++) {
            JSONObject c = records.optJSONObject(i);
            if (c == null) continue;
            String name = c.optString("name").trim();
            String code = c.optString("code").trim();
            if (!needle.isEmpty() && !(name + " " + code).toLowerCase(Locale.ROOT).contains(needle)) continue;
            String sem = c.optString("term").trim();
            if (!term.isEmpty() && !term.equals(sem)) continue;

            String section = c.optString("system").trim();
            if (section.isEmpty()) section = c.optString("nature").trim();
            if (section.isEmpty()) section = "培养方案课程";

            double cr = SdjtuStudyFormat.parseCredit(c.optString("credit"));
            filteredCredit += cr;
            count++;

            CourseEntry ce = new CourseEntry(
                    name, code, sem.isEmpty() ? "" : termLabel(sem),
                    c.optString("credit").trim(),
                    c.optString("hours").trim(),
                    c.optString("nature").trim(),
                    c.optString("kind").trim(),
                    c.optString("status").trim());
            groups.computeIfAbsent(section, k -> new ArrayList<>()).add(ce);
            groupCredits.put(section, groupCredits.getOrDefault(section, 0.0) + cr);
        }

        for (Map.Entry<String, List<CourseEntry>> g : groups.entrySet()) {
            double gCredit = groupCredits.getOrDefault(g.getKey(), 0.0);
            items.add(SdjtuStudyActivity.Item.group(g.getKey(),
                    g.getValue().size() + " 门 · " + SdjtuStudyFormat.formatCredit(gCredit) + " 学分"));
            List<CourseEntry> list = g.getValue();
            for (int idx = 0; idx < list.size(); idx++) {
                items.add(SdjtuStudyActivity.Item.course(list.get(idx), idx == 0, idx == list.size() - 1));
            }
        }

        String countText;
        if (data == null) {
            countText = "";
        } else if (count == 0) {
            boolean any = records != null && records.length() > 0;
            countText = any ? "没有符合筛选条件的课程" : "此方案未列出计划课程";
        } else {
            countText = count + " 门课程 · 共 " + SdjtuStudyFormat.formatCredit(filteredCredit) + " 学分";
        }
        return new RowsResult(items, countText);
    }

    View bindGroupRow(SdjtuStudyActivity.Item item, @Nullable View recycled) {
        LinearLayout row = (LinearLayout) recycled;
        if (row == null) {
            row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(2), dp(16), dp(2), dp(8));
            TextView left = text(13.5f, G.INK);
            left.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView right = text(11.5f, G.MUTE);
            right.setTypeface(Typeface.MONOSPACE);
            row.addView(right);
        }
        ((TextView) row.getChildAt(0)).setText(item.title);
        ((TextView) row.getChildAt(1)).setText(item.subtitle);
        return row;
    }

    View bindCourseCard(SdjtuStudyActivity.Item item) {
        CourseEntry c = item.course;
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, 0, 0, item.lastInCard ? dp(6) : 0);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        float rad = dp(G.CARD_RADIUS);
        float topR = item.firstInCard ? rad : 0f;
        float botR = item.lastInCard ? rad : 0f;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(G.CARD);
        bg.setCornerRadii(new float[]{topR, topR, topR, topR, botR, botR, botR, botR});
        bg.setStroke(Math.max(1, dp(1)), G.CARD_STROKE);
        box.setBackground(bg);

        if (!item.firstInCard) {
            View hair = new View(a);
            hair.setBackgroundColor(G.LINE);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, 1);
            hlp.setMargins(dp(16), 0, dp(16), 0);
            box.addView(hair, hlp);
        }

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));

        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);

        TextView titleTv = text(15, G.INK);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setText(c.name);
        left.addView(titleTv);

        List<String> metaParts = new ArrayList<>();
        if (!c.code.isEmpty()) metaParts.add(c.code);
        if (!c.term.isEmpty()) metaParts.add(c.term);
        if (!c.hours.isEmpty()) metaParts.add(c.hours + " 学时");
        if (!metaParts.isEmpty()) {
            TextView metaTv = text(12, G.SUB);
            metaTv.setTypeface(Typeface.MONOSPACE);
            metaTv.setPadding(0, dp(3), 0, 0);
            metaTv.setText(android.text.TextUtils.join(" · ", metaParts));
            left.addView(metaTv);
        }

        List<String> badges = new ArrayList<>();
        if (!c.nature.isEmpty()) badges.add(c.nature);
        if (!c.kind.isEmpty() && !c.kind.equals(c.nature)) badges.add(c.kind);
        if (!c.status.isEmpty()) badges.add(c.status);
        if (!badges.isEmpty()) {
            LinearLayout tagRow = new LinearLayout(a);
            tagRow.setOrientation(LinearLayout.HORIZONTAL);
            tagRow.setPadding(0, dp(6), 0, 0);
            for (String b : badges) {
                boolean done = b.contains("已修") || b.contains("通过");
                TextView pill = text(10.5f, done ? SdjtuStudyRoomsRenderer.freeChipFg() : G.SUB);
                pill.setTypeface(Typeface.DEFAULT_BOLD);
                pill.setPadding(dp(7), dp(2), dp(7), dp(2));
                GradientDrawable pbg = new GradientDrawable();
                pbg.setCornerRadius(dp(4));
                pbg.setColor(done ? SdjtuStudyRoomsRenderer.freeChipBg() : G.CHIP);
                pill.setBackground(pbg);
                pill.setText(b);
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
                plp.setMarginEnd(dp(6));
                tagRow.addView(pill, plp);
            }
            left.addView(tagRow);
        }

        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        if (!c.credit.isEmpty()) {
            LinearLayout right = new LinearLayout(a);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setGravity(Gravity.END);
            right.setPadding(dp(12), 0, 0, 0);
            TextView crNum = text(18, G.INK);
            crNum.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            crNum.setText(c.credit);
            right.addView(crNum);
            TextView crCap = text(10.5f, G.MUTE);
            crCap.setText("学分");
            right.addView(crCap);
            row.addView(right);
        }

        box.addView(row);
        wrap.addView(box);
        return wrap;
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(a);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(float n) { return Math.round(n * a.getResources().getDisplayMetrics().density); }
}
