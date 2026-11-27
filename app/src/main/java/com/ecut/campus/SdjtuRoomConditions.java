package com.ecut.campus;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.ecut.campus.data.SdjtuStudyFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 交院空教室查询条件弹窗：
 * 校区（必选，默认无影山校区）、教学楼（必选，默认无影山1号教学楼）、
 * 周次（必选，默认当前周次）、星期（必选，默认当前星期）、
 * 节次（可选）、教室（可选）。
 */
final class SdjtuRoomConditions {
    private final Context context;
    private final LinearLayout form;
    private final Spinner campus, building, week, day, period;
    private final EditText roomInput;
    private final View periodField;
    private final View roomField;
    private final AlertDialog dialog;
    private final Consumer<JSONObject> onCampusChange;
    private final int defaultWeek;
    private final int defaultDay;
    private String displayedCampus = "";
    private boolean loadingBuildings;
    private boolean hasBuildingOptions;

    SdjtuRoomConditions(Context context, int defaultWeek, int defaultDay,
                        Consumer<JSONObject> onCampusChange,
                        Consumer<JSONObject> onQuery, Runnable onCancel) {
        this.context = context;
        this.defaultWeek = defaultWeek;
        this.defaultDay = defaultDay;
        this.onCampusChange = onCampusChange;

        form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), dp(12));
        form.setFocusableInTouchMode(true);

        campus = addChoice("校区（必选）");
        building = addChoice("教学楼（必选）");
        week = addChoice("周次（必选）");
        day = addChoice("星期（必选）");
        period = addChoice("节次（可选）");
        periodField = (View) period.getParent();

        roomInput = addTextInput("教室（可选）", "留空查询整栋教学楼，如 1102");
        roomField = (View) roomInput.getParent();

        ScrollView scroll = new ScrollView(context);
        scroll.addView(form);
        dialog = new AlertDialog.Builder(context)
                .setTitle("查询空教室条件")
                .setView(scroll)
                .setNegativeButton("取消", (d, which) -> d.cancel())
                .setPositiveButton("查询", (d, which) -> onQuery.accept(request("query")))
                .setOnCancelListener(d -> onCancel.run())
                .create();
    }

    void show(JSONObject conditions, String validation) {
        if (validation != null && !validation.isEmpty()) {
            TextView error = new TextView(context);
            error.setText(validation);
            error.setTextSize(13f);
            error.setTextColor(G.alarm());
            error.setPadding(0, 0, 0, dp(8));
            form.addView(error, 0);
        }
        setChoices(conditions);
        dialog.show();

        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (parent == campus && !value(campus).equals(displayedCampus)) {
                    displayedCampus = value(campus);
                    loadingBuildings = !displayedCampus.isEmpty();
                    hasBuildingOptions = false;
                    bind(building, null, "", "", loadingBuildings ? "正在读取教学楼…" : "请先选择校区");
                    building.setEnabled(false);
                    campus.setEnabled(!loadingBuildings);
                    updateButton();
                    if (loadingBuildings) onCampusChange.accept(request("options"));
                } else {
                    updateButton();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { updateButton(); }
        };
        campus.setOnItemSelectedListener(listener);
        building.setOnItemSelectedListener(listener);
        week.setOnItemSelectedListener(listener);
        day.setOnItemSelectedListener(listener);
        period.setOnItemSelectedListener(listener);
        updateButton();
    }

    void setChoices(JSONObject conditions) {
        if (conditions == null) conditions = new JSONObject();
        String selectedWeek = loadingBuildings ? value(week) : conditions.optString("week");
        if (selectedWeek.isEmpty() && defaultWeek > 0) selectedWeek = String.valueOf(defaultWeek);

        String selectedDay = loadingBuildings ? value(day) : conditions.optString("day");
        if (selectedDay.isEmpty() && defaultDay >= 1 && defaultDay <= 7) selectedDay = String.valueOf(defaultDay);

        String selectedPeriod = loadingBuildings ? value(period) : conditions.optString("period");
        String selectedRoom = loadingBuildings ? roomText() : conditions.optString("room");

        displayedCampus = conditions.optString("campus");
        loadingBuildings = false;

        String campusHint = Prefs.effectiveStudyRoomCampusHint(context);
        bind(campus, conditions.optJSONArray("campuses"), displayedCampus,
                campusHint, "请选择校区");
        displayedCampus = value(campus);
        String displayedCampusLabel = label(campus);

        JSONArray bldArr = conditions.optJSONArray("buildings");
        hasBuildingOptions = bldArr != null && bldArr.length() > 0;
        String bldCurrent = conditions.optString("building");
        String savedCampusLabel = Prefs.studyRoomCampusLabel(context);
        boolean sameAsSavedCampus = !savedCampusLabel.isEmpty() && !displayedCampusLabel.isEmpty()
                && (displayedCampusLabel.contains(savedCampusLabel) || savedCampusLabel.contains(displayedCampusLabel));
        String bldHint = displayedCampus.isEmpty() ? ""
                : (sameAsSavedCampus ? Prefs.effectiveStudyRoomBuildingHint(context)
                        : SdjtuStudyFormat.defaultBuildingHintForCampus(displayedCampusLabel));
        bind(building, bldArr, bldCurrent, bldHint,
                hasBuildingOptions ? "请选择教学楼" : "当前校区暂无可选教学楼");
        if (hasBuildingOptions && value(building).isEmpty() && building.getCount() > 1) {
            building.setSelection(1, false);
        }

        bind(week, conditions.optJSONArray("weeks"), selectedWeek, "", "请选择周次");
        if (value(week).isEmpty() && week.getCount() > 1) {
            week.setSelection(1, false);
        }

        JSONArray dayArr = conditions.optJSONArray("days");
        if (dayArr == null || dayArr.length() == 0) {
            dayArr = defaultDayOptions();
        }
        bind(day, dayArr, selectedDay, "", "请选择星期");
        if (value(day).isEmpty() && day.getCount() > 1) {
            day.setSelection(1, false);
        }

        JSONArray periodArr = conditions.optJSONArray("periods");
        boolean hasPeriods = periodArr != null && periodArr.length() > 0;
        bind(period, periodArr, selectedPeriod, "", "全部节次（不限）");
        periodField.setVisibility(hasPeriods ? View.VISIBLE : View.GONE);

        if (!loadingBuildings && roomInput.getText().length() == 0 && !selectedRoom.isEmpty()) {
            roomInput.setText(selectedRoom);
        }
        roomField.setVisibility(conditions.optBoolean("hasRoomField", true) ? View.VISIBLE : View.GONE);

        campus.setEnabled(true);
        building.setEnabled(!displayedCampus.isEmpty() && hasBuildingOptions);
        updateButton();
    }

    JSONObject request(String action) {
        JSONObject selected = new JSONObject();
        SdjtuStudyActivity.put(selected, "campus", value(campus));
        SdjtuStudyActivity.put(selected, "building", value(building));
        SdjtuStudyActivity.put(selected, "week", value(week));
        SdjtuStudyActivity.put(selected, "day", value(day));
        SdjtuStudyActivity.put(selected, "period", value(period));
        SdjtuStudyActivity.put(selected, "room", roomText());
        SdjtuStudyActivity.put(selected, "requireBuilding", hasBuildingOptions);
        SdjtuStudyActivity.put(selected, "campusLabel", label(campus));
        SdjtuStudyActivity.put(selected, "buildingLabel", label(building));
        SdjtuStudyActivity.put(selected, "weekLabel", label(week));
        SdjtuStudyActivity.put(selected, "dayLabel", label(day));
        SdjtuStudyActivity.put(selected, "periodLabel", value(period).isEmpty() ? "" : label(period));

        JSONObject req = new JSONObject();
        SdjtuStudyActivity.put(req, "roomAction", action);
        SdjtuStudyActivity.put(req, "roomConditions", selected);

        JSONObject timeFilter = new JSONObject();
        String dVal = value(day);
        if (!dVal.isEmpty()) {
            try { SdjtuStudyActivity.put(timeFilter, "day", Integer.parseInt(dVal)); }
            catch (NumberFormatException ignored) {}
        }
        SdjtuStudyActivity.put(timeFilter, "period", "all");
        SdjtuStudyActivity.put(req, "query", timeFilter);
        return req;
    }

    void dismiss() { dialog.dismiss(); }

    private void updateButton() {
        if (!dialog.isShowing()) return;
        boolean ready = !loadingBuildings
                && !value(campus).isEmpty()
                && (!hasBuildingOptions || !value(building).isEmpty())
                && !value(week).isEmpty()
                && !value(day).isEmpty();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(ready);
    }

    private String roomText() {
        return roomInput.getText() == null ? "" : roomInput.getText().toString().trim();
    }

    private static String value(Spinner spinner) {
        Option selected = spinner == null ? null : (Option) spinner.getSelectedItem();
        return selected == null ? "" : selected.value;
    }

    private static String label(Spinner spinner) {
        Option selected = spinner == null ? null : (Option) spinner.getSelectedItem();
        return selected == null || selected.value.isEmpty() ? "" : selected.label;
    }

    private void bind(Spinner spinner, JSONArray choices, String current, String hintLabel, String placeholder) {
        List<Option> items = new ArrayList<>();
        items.add(new Option("", placeholder));
        int selection = 0;
        int hintSelection = 0;
        for (int i = 0; choices != null && i < choices.length(); i++) {
            JSONObject item = choices.optJSONObject(i);
            if (item == null || item.optString("value").isEmpty()) continue;
            String val = item.optString("value");
            String lbl = item.optString("label", val);
            if (val.equals(current)) selection = items.size();
            if (hintSelection == 0 && !hintLabel.isEmpty() && lbl.replaceAll("\\s+", "").contains(hintLabel)) {
                hintSelection = items.size();
            }
            items.add(new Option(val, lbl));
        }
        if (selection == 0 && hintSelection > 0) selection = hintSelection;
        ArrayAdapter<Option> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selection, false);
    }

    private static JSONArray defaultDayOptions() {
        JSONArray arr = new JSONArray();
        for (int d = 1; d <= 7; d++) {
            JSONObject o = new JSONObject();
            SdjtuStudyActivity.put(o, "value", String.valueOf(d));
            SdjtuStudyActivity.put(o, "label", SdjtuStudyFormat.weekdayLabel(d));
            arr.put(o);
        }
        return arr;
    }

    private Spinner addChoice(String label) {
        Spinner input = new Spinner(context, Spinner.MODE_DIALOG);
        input.setId(View.generateViewId());
        input.setPrompt(label);
        input.setMinimumHeight(dp(46));
        TextView caption = new TextView(context);
        caption.setText(label);
        caption.setTextSize(12f);
        caption.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        caption.setTextColor(G.SUB);
        caption.setPadding(0, dp(10), 0, dp(2));
        caption.setLabelFor(input.getId());

        LinearLayout field = new LinearLayout(context);
        field.setOrientation(LinearLayout.VERTICAL);
        field.addView(caption);
        field.addView(input);
        form.addView(field);
        return input;
    }

    private EditText addTextInput(String label, String hint) {
        EditText et = new EditText(context);
        et.setId(View.generateViewId());
        et.setSingleLine(true);
        et.setHint(hint);
        et.setTextSize(14f);
        et.setTextColor(G.INK);
        et.setHintTextColor(G.MUTE);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setBackgroundResource(R.drawable.bg_gauge_input);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        et.setMinHeight(dp(44));

        TextView caption = new TextView(context);
        caption.setText(label);
        caption.setTextSize(12f);
        caption.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        caption.setTextColor(G.SUB);
        caption.setPadding(0, dp(10), 0, dp(6));
        caption.setLabelFor(et.getId());

        LinearLayout field = new LinearLayout(context);
        field.setOrientation(LinearLayout.VERTICAL);
        field.addView(caption);
        field.addView(et, new LinearLayout.LayoutParams(-1, -2));
        form.addView(field);
        return et;
    }

    private int dp(int n) { return Math.round(n * context.getResources().getDisplayMetrics().density); }

    private static final class Option {
        final String value, label;
        Option(String value, String label) { this.value = value; this.label = label; }
        @Override public String toString() { return label; }
    }
}
