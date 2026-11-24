package com.ecut.campus;

import android.content.Context;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 校区和周次必须明确选择；教学楼选项来自该校区的学校表单。 */
final class SdjtuRoomConditions {
    private final Context context;
    private final LinearLayout form;
    private final Spinner campus, week, building;
    private final AlertDialog dialog;
    private final Consumer<JSONObject> onCampusChange;
    private Spinner day, period;
    private JSONObject timeScope;
    private String periodDay = "";
    private String displayedCampus = "";
    private boolean loadingBuildings;

    SdjtuRoomConditions(Context context, Consumer<JSONObject> onCampusChange,
                        Consumer<JSONObject> onQuery, Runnable onCancel) {
        this.context = context;
        this.onCampusChange = onCampusChange;
        form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(4), dp(24), dp(12));
        form.setFocusableInTouchMode(true);
        campus = addChoice("校区（必选）");
        week = addChoice("周次（必选）");
        building = addChoice("教学楼（选填）");
        ScrollView scroll = new ScrollView(context);
        scroll.addView(form);
        dialog = new AlertDialog.Builder(context).setTitle("教室查询条件").setView(scroll)
                .setNegativeButton("取消", (d, which) -> d.cancel())
                .setPositiveButton("查询", (d, which) -> onQuery.accept(request("query")))
                .setOnCancelListener(d -> onCancel.run()).create();
    }

    void show(JSONObject conditions, JSONObject timetable, JSONObject previous, String validation) {
        if (!validation.isEmpty()) {
            TextView error = new TextView(context);
            error.setText(validation);
            error.setTextColor(G.INK);
            form.addView(error, 0);
        }
        JSONObject slots = timetable == null ? null : timetable.optJSONObject("choices");
        timeScope = timetable == null ? null : timetable.optJSONObject("roomConditions");
        if (slots != null && timeScope != null) {
            JSONObject sourceTime = timetable.optJSONObject("query");
            periodDay = sourceTime == null ? "" : sourceTime.optString("day");
            if (previous == null || previous.length() == 0) previous = sourceTime;
            day = addTimeChoice(slots.optJSONArray("days"), true, previous);
            period = addTimeChoice(slots.optJSONArray("periods"), false, previous);
        }
        setChoices(conditions);
        dialog.show();
        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (parent == campus && !value(campus).equals(displayedCampus)) {
                    displayedCampus = value(campus);
                    loadingBuildings = !displayedCampus.isEmpty();
                    bind(building, null, "", loadingBuildings ? "正在读取教学楼…" : "全部教学楼");
                    building.setEnabled(false);
                    campus.setEnabled(!loadingBuildings);
                    updateButton();
                    if (loadingBuildings) onCampusChange.accept(request("options"));
                } else updateButton();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { updateButton(); }
        };
        campus.setOnItemSelectedListener(listener);
        week.setOnItemSelectedListener(listener);
        building.setOnItemSelectedListener(listener);
        if (day != null) day.setOnItemSelectedListener(listener);
        updateButton();
    }

    void setChoices(JSONObject conditions) {
        // The user can pick a week while the school is populating the building list.
        String selectedWeek = loadingBuildings ? value(week) : conditions.optString("week");
        displayedCampus = conditions.optString("campus");
        loadingBuildings = false;
        bind(campus, conditions.optJSONArray("campuses"), displayedCampus, "请选择校区");
        displayedCampus = value(campus);
        bind(week, conditions.optJSONArray("weeks"), selectedWeek, "请选择周次");
        bind(building, conditions.optJSONArray("buildings"), conditions.optString("building"), "全部教学楼");
        campus.setEnabled(true);
        building.setEnabled(!displayedCampus.isEmpty() && building.getCount() > 1);
        updateButton();
    }

    JSONObject request(String action) {
        JSONObject selected = new JSONObject();
        SdjtuStudyActivity.put(selected, "campus", value(campus));
        SdjtuStudyActivity.put(selected, "week", value(week));
        SdjtuStudyActivity.put(selected, "building", value(building));
        JSONObject request = new JSONObject();
        SdjtuStudyActivity.put(request, "roomAction", action);
        SdjtuStudyActivity.put(request, "roomConditions", selected);
        JSONObject timeFilter = new JSONObject();
        if (currentTimeScope()) {
            if (day != null) SdjtuStudyActivity.put(timeFilter, "day", Integer.parseInt(value(day)));
            if (period != null && value(day).equals(periodDay)) SdjtuStudyActivity.put(timeFilter, "period", value(period));
        }
        SdjtuStudyActivity.put(request, "query", timeFilter);
        return request;
    }

    void dismiss() { dialog.dismiss(); }

    private boolean currentTimeScope() {
        return timeScope != null && !value(campus).isEmpty() && !value(week).isEmpty() &&
                value(campus).equals(timeScope.optString("campus")) && value(week).equals(timeScope.optString("week")) &&
                value(building).equals(timeScope.optString("building"));
    }

    private void updateButton() {
        boolean current = currentTimeScope();
        if (day != null) ((View) day.getParent()).setVisibility(current ? View.VISIBLE : View.GONE);
        if (period != null) ((View) period.getParent()).setVisibility(current && value(day).equals(periodDay)
                ? View.VISIBLE : View.GONE);
        if (dialog.isShowing()) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(
                !loadingBuildings && !value(campus).isEmpty() && !value(week).isEmpty());
    }

    private static String value(Spinner spinner) {
        Option selected = spinner == null ? null : (Option) spinner.getSelectedItem();
        return selected == null ? "" : selected.value;
    }

    private void bind(Spinner spinner, JSONArray choices, String current, String placeholder) {
        List<Option> items = new ArrayList<>();
        items.add(new Option("", placeholder));
        int selection = 0;
        for (int i = 0; choices != null && i < choices.length(); i++) {
            JSONObject item = choices.optJSONObject(i);
            if (item == null || item.optString("value").isEmpty()) continue;
            String value = item.optString("value");
            if (value.equals(current)) selection = items.size();
            items.add(new Option(value, item.optString("label", value)));
        }
        setItems(spinner, items, selection);
    }

    private void setItems(Spinner spinner, List<Option> items, int selection) {
        ArrayAdapter<Option> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selection, false);
    }

    private Spinner addChoice(String label) {
        Spinner input = new Spinner(context, Spinner.MODE_DIALOG);
        input.setId(View.generateViewId());
        input.setPrompt(label);
        input.setMinimumHeight(dp(48));
        TextView caption = new TextView(context);
        caption.setText(label);
        caption.setTextColor(G.SUB);
        caption.setPadding(0, dp(8), 0, 0);
        caption.setLabelFor(input.getId());
        LinearLayout field = new LinearLayout(context);
        field.setOrientation(LinearLayout.VERTICAL);
        field.addView(caption);
        field.addView(input);
        form.addView(field);
        return input;
    }

    private Spinner addTimeChoice(JSONArray values, boolean weekday, JSONObject previous) {
        if (values == null || values.length() == 0) return null;
        List<Option> items = new ArrayList<>();
        if (!weekday) items.add(new Option("all", "当日全部所列节次"));
        String before = previous == null ? "" : previous.optString(weekday ? "day" : "period");
        int selection = 0;
        for (int i = 0; i < values.length(); i++) {
            String value = values.optString(i);
            int d = values.optInt(i);
            if (value.isEmpty() || weekday && (d < 1 || d > 7)) continue;
            if (value.equals(before)) selection = items.size();
            items.add(new Option(value, weekday ? "星期" + "一二三四五六日".charAt(d - 1) : value));
        }
        if (items.isEmpty()) return null;
        Spinner spinner = addChoice(weekday ? "查看星期" : "查看节次");
        setItems(spinner, items, selection);
        return spinner;
    }

    private int dp(int n) { return Math.round(n * context.getResources().getDisplayMetrics().density); }

    private static final class Option {
        final String value, label;
        Option(String value, String label) { this.value = value; this.label = label; }
        @Override public String toString() { return label; }
    }
}
