package com.ecut.campus;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;

import com.ecut.campus.data.ClassroomLabel;
import com.ecut.campus.data.SemesterArchive;
import com.ecut.campus.data.Timetable;
import com.ecut.campus.data.TimetableStore;
import com.ecut.campus.view.RulerDialView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 课表设置：学期日期与周次校准、显示偏好、地点简化与更多入口。 */
public class TimetableSettingsActivity extends AppCompatActivity {

    private static final List<String> FALLBACK_SUGGESTIONS = Arrays.asList(
            "无影山1号教学楼", "无影山", "长清校区", "教学楼");

    private TextView tvStartValue;
    private TextView tvPreview;
    private TextView tvFirstCourse;
    private TextView btnSeven;
    private TextView btnFive;

    private TextView tvPhraseCount;
    private TextView tvLocationPreview;
    private View boxActivePhrasesWrap;
    private FrameLayout containerActivePhrases;
    private View boxSuggestPhrasesWrap;
    private TextView tvSuggestPhrasesLabel;
    private FrameLayout containerSuggestPhrases;

    private String startDate;
    private int displayDays;
    private Timetable cachedTimetable;
    private List<String> timetableLocations = Collections.emptyList();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        G.init(this);
        setContentView(R.layout.activity_timetable_settings);
        EdgeToEdge.applyContent(this, true);

        String storedStart = Prefs.semesterStartDate(this);
        startDate = savedInstanceState == null ? WeekMath.normalizeToMonday(storedStart)
                : savedInstanceState.getString("startDate", WeekMath.normalizeToMonday(storedStart));
        displayDays = Prefs.displayDays(this);

        cachedTimetable = TimetableStore.load(this);
        timetableLocations = collectLocations(cachedTimetable);

        tvStartValue = findViewById(R.id.tv_semester_start_value);
        tvPreview = findViewById(R.id.tv_current_week_preview);
        tvFirstCourse = findViewById(R.id.tv_first_course_preview);
        btnSeven = findViewById(R.id.btn_days_seven);
        btnFive = findViewById(R.id.btn_days_five);

        tvPhraseCount = findViewById(R.id.tv_location_phrase_count);
        tvLocationPreview = findViewById(R.id.tv_location_preview);
        boxActivePhrasesWrap = findViewById(R.id.box_active_phrases_wrap);
        containerActivePhrases = findViewById(R.id.container_active_phrases);
        boxSuggestPhrasesWrap = findViewById(R.id.box_suggest_phrases_wrap);
        tvSuggestPhrasesLabel = findViewById(R.id.tv_suggest_phrases_label);
        containerSuggestPhrases = findViewById(R.id.container_suggest_phrases);

        View previewBox = findViewById(R.id.box_location_preview);
        if (previewBox != null) {
            GradientDrawable pbg = new GradientDrawable();
            pbg.setCornerRadius(dp(G.SMALL_RADIUS));
            pbg.setColor(G.CHIP);
            previewBox.setBackground(pbg);
        }

        findViewById(R.id.btn_timetable_settings_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_timetable_settings_save).setOnClickListener(v -> save());
        findViewById(R.id.row_semester_start).setOnClickListener(v -> showDatePicker());
        findViewById(R.id.btn_calibrate_week).setOnClickListener(v -> showWeekCalibration());
        btnSeven.setOnClickListener(v -> selectDays(7));
        btnFive.setOnClickListener(v -> selectDays(5));

        findViewById(R.id.btn_hidden_location_phrases).setOnClickListener(v ->
                new LocationPhrasesDialog().show(getSupportFragmentManager(), "location_phrases"));
        findViewById(R.id.btn_clear_location_phrases).setOnClickListener(v -> {
            Prefs.setHiddenLocationPhrases(this, "");
            refreshLocationSection();
            ClassWidget.updateAll(getApplicationContext());
            Toast.makeText(this, "已清空地点隐藏词组", Toast.LENGTH_SHORT).show();
        });

        buildDisplayToggles();
        buildMoreSection();
        renderStartDate();
        selectDays(displayDays);
        refreshPreview();
        refreshLocationSection();
    }

    private void buildDisplayToggles() {
        LinearLayout toggles = findViewById(R.id.tt_display_toggles);
        if (toggles == null) return;
        toggles.removeAllViews();

        addToggleRow(toggles, "01", "显示非本周课程", "关闭后仅显示本周有课的课程",
                Prefs.showInactiveCourses(this), on -> Prefs.setShowInactiveCourses(this, on));

        addToggleRow(toggles, "02", "显示课程号", "在课表课块中展示课程编号",
                Prefs.showCourseCode(this), on -> Prefs.setShowCourseCode(this, on));

        addToggleRow(toggles, "03", "一屏显示全部节次", "压缩课块高度，无需上下滚动即可看全天",
                Prefs.fitAllSections(this), on -> Prefs.setFitAllSections(this, on));

        addToggleRow(toggles, "04", "课块下雨提示",
                RainWeather.currentCampus(this).name + " · 上课时段可能降雨时浮现雨雾",
                Prefs.rainHint(this), on -> {
                    Prefs.setRainHint(this, on);
                    if (on) {
                        RainWeather.refreshIfNeeded(this, false, null);
                    }
                    ClassWidget.updateAll(getApplicationContext());
                });

        addToggleRow(toggles, "05", "课表显示考试安排", "将已同步的考试卡片叠加显示到对应时间槽",
                Prefs.examInTimetable(this), on -> Prefs.setExamInTimetable(this, on));
    }

    private interface ToggleCallback {
        void onToggle(boolean newState);
    }

    private void addToggleRow(LinearLayout parent, String index, String title, String sub,
                              boolean initialOn, ToggleCallback callback) {
        if (parent.getChildCount() > 0) {
            View hair = new View(this);
            hair.setBackgroundColor(G.LINE);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            hp.leftMargin = dp(50);
            hp.rightMargin = dp(18);
            parent.addView(hair, hp);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(18), dp(12), dp(18), dp(12));
        row.setBackgroundResource(Ui.selectable(this, false));

        TextView idx = new TextView(this);
        idx.setText(index);
        idx.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        idx.setLetterSpacing(0.04f);
        idx.setTextSize(11.5f);
        idx.setTextColor(G.MUTE);
        row.addView(idx, new LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(G.INK);
        texts.addView(t);

        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(12f);
        s.setTextColor(G.SUB);
        s.setPadding(0, dp(2), 0, 0);
        texts.addView(s);

        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.rightMargin = dp(12);
        row.addView(texts, textLp);

        TextView pill = new TextView(this);
        pill.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pill.setTextSize(11f);
        pill.setGravity(Gravity.CENTER);
        pill.setMinWidth(dp(44));
        pill.setPadding(dp(10), dp(5), dp(10), dp(5));
        styleTogglePill(pill, initialOn);
        row.addView(pill);

        final boolean[] state = {initialOn};
        row.setOnClickListener(v -> {
            state[0] = !state[0];
            styleTogglePill(pill, state[0]);
            callback.onToggle(state[0]);
        });
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void styleTogglePill(TextView pill, boolean on) {
        pill.setText(on ? "ON" : "OFF");
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(G.GLASS ? 12 : 4));
        bg.setColor(on ? G.INK : G.CHIP);
        pill.setBackground(bg);
        pill.setTextColor(on ? G.ON_INK : G.MUTE);
    }

    private void buildMoreSection() {
        LinearLayout body = findViewById(R.id.tt_settings_body);
        if (body == null) return;
        Ui.label(this, body, "MORE · 更多");
        LinearLayout card = Ui.card(this, body);
        Ui.row(this, card, "01", "上课时间", "设置每节课的上下课时间与节数", "›",
                v -> startActivity(new android.content.Intent(this, ScheduleSettingsActivity.class)));
        Ui.row(this, card, "02", "同步历史学期", "拉取并归档往期学期课表", "›",
                v -> MainActivity.launchAction(this, "sync", "history"));
    }

    /** 刷新「地点隐藏词组」计数、实时预览、已启用标签与智能建议芯片。 */
    public void refreshLocationSection() {
        List<String> active = ClassroomLabel.parsePhrases(Prefs.hiddenLocationPhrases(this));

        if (tvPhraseCount != null) {
            tvPhraseCount.setText(active.isEmpty()
                    ? "未启用 · 课表卡片显示完整地点"
                    : "已启用 " + active.size() + " 个隐藏词组");
        }

        if (tvLocationPreview != null) {
            String sample = ClassroomLabel.pickSampleLocation(timetableLocations, active);
            tvLocationPreview.setText(buildPreviewSpan(sample, active));
        }

        if (boxActivePhrasesWrap != null && containerActivePhrases != null) {
            containerActivePhrases.removeAllViews();
            if (active.isEmpty()) {
                boxActivePhrasesWrap.setVisibility(View.GONE);
            } else {
                boxActivePhrasesWrap.setVisibility(View.VISIBLE);
                ChipWrapLayout wrap = new ChipWrapLayout(this, dp(8), dp(8));
                for (String phrase : active) {
                    wrap.addView(makePhraseChip(phrase, true, v -> {
                        List<String> next = ClassroomLabel.removePhrase(
                                ClassroomLabel.parsePhrases(Prefs.hiddenLocationPhrases(this)), phrase);
                        Prefs.setHiddenLocationPhrases(this, ClassroomLabel.formatPhrases(next));
                        refreshLocationSection();
                        ClassWidget.updateAll(getApplicationContext());
                    }));
                }
                containerActivePhrases.addView(wrap, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }

        if (boxSuggestPhrasesWrap != null && containerSuggestPhrases != null) {
            containerSuggestPhrases.removeAllViews();
            List<String> extracted = ClassroomLabel.suggestPhrases(timetableLocations, 6);
            boolean fromTimetable = !extracted.isEmpty();
            List<String> pool = fromTimetable ? extracted : FALLBACK_SUGGESTIONS;
            Set<String> activeSet = new LinkedHashSet<>(active);
            List<String> remaining = new ArrayList<>();
            for (String candidate : pool) {
                if (!activeSet.contains(candidate)) remaining.add(candidate);
            }
            if (remaining.isEmpty()) {
                boxSuggestPhrasesWrap.setVisibility(View.GONE);
            } else {
                boxSuggestPhrasesWrap.setVisibility(View.VISIBLE);
                if (tvSuggestPhrasesLabel != null) {
                    tvSuggestPhrasesLabel.setText(fromTimetable
                            ? "快捷添加 · 来自当前课表"
                            : "常用推荐 · 轻点启用");
                }
                ChipWrapLayout wrap = new ChipWrapLayout(this, dp(8), dp(8));
                for (String suggestion : remaining) {
                    wrap.addView(makePhraseChip(suggestion, false, v -> {
                        List<String> next = ClassroomLabel.addPhrases(
                                ClassroomLabel.parsePhrases(Prefs.hiddenLocationPhrases(this)), suggestion);
                        Prefs.setHiddenLocationPhrases(this, ClassroomLabel.formatPhrases(next));
                        refreshLocationSection();
                        ClassWidget.updateAll(getApplicationContext());
                    }));
                }
                containerSuggestPhrases.addView(wrap, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
    }

    private TextView makePhraseChip(String phrase, boolean active, View.OnClickListener click) {
        TextView chip = new TextView(this);
        chip.setText(active ? (phrase + "  ×") : ("+ " + phrase));
        chip.setTextSize(12.5f);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setMinHeight(dp(36));
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(G.GLASS ? 18 : 6));
        if (active) {
            bg.setColor(G.INK);
            chip.setTextColor(G.ON_INK);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            bg.setColor(G.CHIP);
            bg.setStroke(Math.max(1, dp(1)), G.STROKE);
            chip.setTextColor(G.INK);
        }
        chip.setBackground(bg);
        chip.setForeground(getDrawable(Ui.selectable(this, false)));
        chip.setOnClickListener(click);
        return chip;
    }

    private static CharSequence buildPreviewSpan(String sample, List<String> phrases) {
        String compacted = ClassroomLabel.compact(sample, phrases);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (phrases == null || phrases.isEmpty() || compacted.equals(sample)) {
            int start = sb.length();
            sb.append("原样显示  ");
            sb.setSpan(new ForegroundColorSpan(G.MUTE), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int valStart = sb.length();
            sb.append(sample);
            sb.setSpan(new ForegroundColorSpan(G.SUB), valStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return sb;
        }
        int origStart = sb.length();
        sb.append(sample);
        sb.setSpan(new ForegroundColorSpan(G.MUTE), origStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int arrowStart = sb.length();
        sb.append("  →  ");
        sb.setSpan(new ForegroundColorSpan(G.MUTE), arrowStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int resStart = sb.length();
        sb.append(compacted.isEmpty() ? "（隐藏全部）" : compacted);
        sb.setSpan(new ForegroundColorSpan(G.INK), resStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), resStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static List<String> collectLocations(@Nullable Timetable timetable) {
        if (timetable == null || timetable.courses == null || timetable.courses.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (Timetable.Course c : timetable.courses) {
            if (c == null || c.sessions == null) continue;
            for (Timetable.Session s : c.sessions) {
                if (s != null && s.location != null && !s.location.trim().isEmpty()) {
                    out.add(s.location.trim());
                }
            }
        }
        return out;
    }

    public static class LocationPhrasesDialog extends DialogFragment {
        @NonNull
        @Override
        public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            Context context = requireContext();
            float d = getResources().getDisplayMetrics().density;
            int padH = Math.round(22 * d);
            int padV = Math.round(10 * d);

            Timetable tt = TimetableStore.load(context);
            List<String> locs = collectLocations(tt);

            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(padH, padV, padH, Math.round(4 * d));

            TextView hint = new TextView(context);
            hint.setText("填入需隐藏的校区或楼宇前缀，多个词组用换行、逗号或顿号分隔；留空保存则恢复完整地点。");
            hint.setTextSize(12.5f);
            hint.setTextColor(G.SUB);
            hint.setLineSpacing(Math.round(2 * d), 1f);
            root.addView(hint);

            LinearLayout previewBox = new LinearLayout(context);
            previewBox.setOrientation(LinearLayout.VERTICAL);
            previewBox.setPadding(Math.round(12 * d), Math.round(8 * d), Math.round(12 * d), Math.round(8 * d));
            GradientDrawable pbg = new GradientDrawable();
            pbg.setCornerRadius(G.SMALL_RADIUS * d);
            pbg.setColor(G.CHIP);
            previewBox.setBackground(pbg);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.topMargin = Math.round(12 * d);
            root.addView(previewBox, plp);

            TextView previewLabel = new TextView(context);
            previewLabel.setText("实时效果预览");
            previewLabel.setTypeface(Typeface.MONOSPACE);
            previewLabel.setTextSize(10.5f);
            previewLabel.setTextColor(G.MUTE);
            previewBox.addView(previewLabel);

            TextView previewValue = new TextView(context);
            previewValue.setTextSize(13f);
            previewValue.setPadding(0, Math.round(3 * d), 0, 0);
            previewBox.addView(previewValue);

            EditText input = new EditText(context);
            input.setId(android.R.id.edit);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setGravity(Gravity.TOP | Gravity.START);
            input.setMinLines(3);
            input.setMaxLines(6);
            input.setTextSize(14f);
            input.setTextColor(G.INK);
            input.setHintTextColor(G.MUTE);
            input.setHint("无影山1号教学楼\n长清校区");
            input.setPadding(Math.round(12 * d), Math.round(10 * d), Math.round(12 * d), Math.round(10 * d));
            GradientDrawable ibg = new GradientDrawable();
            ibg.setCornerRadius(G.SMALL_RADIUS * d);
            ibg.setColor(G.CARD);
            ibg.setStroke(Math.max(1, Math.round(d)), G.STROKE);
            input.setBackground(ibg);
            input.setText(Prefs.hiddenLocationPhrases(context));
            input.setSelection(input.getText().length());

            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.topMargin = Math.round(12 * d);
            root.addView(input, ilp);

            Runnable updatePreview = () -> {
                List<String> phrases = ClassroomLabel.parsePhrases(input.getText().toString());
                String sample = ClassroomLabel.pickSampleLocation(locs, phrases);
                previewValue.setText(buildPreviewSpan(sample, phrases));
            };
            updatePreview.run();
            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) { updatePreview.run(); }
            });

            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle("编辑地点隐藏词组")
                    .setView(root)
                    .setNeutralButton("清空", null)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("保存", (d1, which) -> {
                        String text = ClassroomLabel.formatPhrases(
                                ClassroomLabel.parsePhrases(input.getText().toString()));
                        Prefs.setHiddenLocationPhrases(context, text);
                        ClassWidget.updateAll(context.getApplicationContext());
                        if (getActivity() instanceof TimetableSettingsActivity) {
                            ((TimetableSettingsActivity) getActivity()).refreshLocationSection();
                        }
                        Toast.makeText(context, "地点隐藏词组已保存", Toast.LENGTH_SHORT).show();
                    })
                    .create();
            dialog.setOnShowListener(d1 -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(v -> input.setText("")));
            return dialog;
        }
    }

    /** 轻量流式换行布局，用于展示地点隐藏词组和快捷推荐标签。 */
    private static final class ChipWrapLayout extends ViewGroup {
        private final int hGap;
        private final int vGap;

        ChipWrapLayout(Context context, int hGap, int vGap) {
            super(context);
            this.hGap = hGap;
            this.vGap = vGap;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int maxWidth = MeasureSpec.getSize(widthMeasureSpec);
            int mode = MeasureSpec.getMode(widthMeasureSpec);
            int avail = Math.max(0, maxWidth - getPaddingLeft() - getPaddingRight());
            int x = 0;
            int y = 0;
            int lineHeight = 0;
            int maxLineWidth = 0;

            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int childWSpec = MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST);
                int childHSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                child.measure(childWSpec, childHSpec);
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                if (x > 0 && x + cw > avail) {
                    maxLineWidth = Math.max(maxLineWidth, x - hGap);
                    x = 0;
                    y += lineHeight + vGap;
                    lineHeight = 0;
                }
                x += cw + hGap;
                lineHeight = Math.max(lineHeight, ch);
            }
            if (x > 0) maxLineWidth = Math.max(maxLineWidth, x - hGap);
            int measuredW = mode == MeasureSpec.EXACTLY ? maxWidth
                    : Math.min(maxWidth, maxLineWidth + getPaddingLeft() + getPaddingRight());
            int measuredH = y + lineHeight + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(measuredW, resolveSize(measuredH, heightMeasureSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int avail = Math.max(0, (r - l) - getPaddingLeft() - getPaddingRight());
            int x = 0;
            int y = 0;
            int lineHeight = 0;
            int pl = getPaddingLeft();
            int pt = getPaddingTop();

            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                if (x > 0 && x + cw > avail) {
                    x = 0;
                    y += lineHeight + vGap;
                    lineHeight = 0;
                }
                child.layout(pl + x, pt + y, pl + x + cw, pt + y + ch);
                x += cw + hGap;
                lineHeight = Math.max(lineHeight, ch);
            }
        }
    }

    private void showDatePicker() {
        Calendar cal = WeekMath.parseDate(startDate);
        if (cal == null) cal = Calendar.getInstance();
        GaugePickers.date(this, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH), true,
                (year, month, dayOfMonth) -> {
                    String picked = String.format(Locale.US, "%04d-%02d-%02d",
                            year, month + 1, dayOfMonth);
                    startDate = WeekMath.normalizeToMonday(picked);
                    renderStartDate();
                    refreshPreview();
                    if (!picked.equals(startDate)) {
                        Toast.makeText(this, "已调整为当周周一：" + startDate, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("startDate", startDate);
    }

    private void showWeekCalibration() {
        RulerDialView dial = new RulerDialView(this);
        int max = WeekMath.OPEN_WEEKS_MAX;
        dial.configure(1, max, WeekMath.currentWeek(startDate, max), "周");
        new AlertDialog.Builder(this)
                .setTitle("本周是第几周")
                .setView(dial)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, which) -> {
                    startDate = WeekMath.startDateForWeek(WeekMath.todayDateLabel(), dial.value());
                    renderStartDate();
                    refreshPreview();
                })
                .show();
    }

    private void renderStartDate() {
        tvStartValue.setText(startDate == null || startDate.isEmpty()
                ? "点击设置"
                : startDate);
    }

    private void selectDays(int days) {
        displayDays = days == 5 ? 5 : 7;
        styleSegment(btnSeven, displayDays == 7);
        styleSegment(btnFive, displayDays == 5);
        Prefs.setDisplayDays(this, displayDays);
    }

    private void styleSegment(TextView tv, boolean selected) {
        if (tv.getParent() instanceof View) {
            ((View) tv.getParent()).setBackground(Ui.segmentBox(this));
        }
        Ui.segmentItem(tv, selected);
    }

    private void refreshPreview() {
        if (startDate == null || startDate.isEmpty()) {
            tvPreview.setText("未设置周次");
            if (tvFirstCourse != null) tvFirstCourse.setText("请先选择学期第 1 周周一日期");
            return;
        }
        int current = WeekMath.currentWeek(startDate, WeekMath.openWeekSpan(startDate));
        tvPreview.setText("第 " + current + " 周");
        String range = WeekMath.weekRangeLabel(startDate, current);
        String first = WeekMath.firstCourseLabel(startDate, cachedTimetable);
        if (tvFirstCourse != null) {
            tvFirstCourse.setText(range + " · 首节课 " + first);
        }
    }

    /** 归档字段原样保留，界面不再编辑它。 */
    private int parseTotalWeeks() {
        return Prefs.storedTotalWeeks(this);
    }

    private void save() {
        String normalizedStart = WeekMath.normalizeToMonday(startDate);
        if (normalizedStart.isEmpty()) {
            Toast.makeText(this, "请先设置开学周一日期", Toast.LENGTH_SHORT).show();
            return;
        }
        startDate = normalizedStart;
        int total = parseTotalWeeks();
        Prefs.setDisplayDays(this, displayDays);
        if (!SemesterArchive.updateActiveSemesterMeta(this, startDate, total)) {
            Toast.makeText(this, "保存失败：找不到当前学期，请先同步课表", Toast.LENGTH_LONG).show();
            return;
        }
        if (!SemesterArchive.ensureActiveMirror(this)) {
            Toast.makeText(this, "日期已写入主源，但本地镜像刷新失败，请重试", Toast.LENGTH_LONG).show();
            return;
        }
        Prefs.setCurrentWeek(this, WeekMath.currentWeek(startDate, WeekMath.openWeekSpan(startDate)));
        ClassReminder.reschedule(this);
        ClassWidget.updateAll(this);
        Toast.makeText(this, "课表设置已保存", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
