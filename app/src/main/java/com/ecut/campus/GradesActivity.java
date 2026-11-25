package com.ecut.campus;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.ecut.campus.data.Grades;
import com.ecut.campus.data.GradesStore;
import com.ecut.campus.fun.GradeFlip;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 独立成绩页，从设置首页进入。 */
public class GradesActivity extends AppCompatActivity {

    private Grades currentGrades = new Grades();
    private String selectedSemesterFilter = "ALL";
    private CampusSyncClient activeSync;
    private boolean awaitingLogin;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        G.init(this);
        setContentView(R.layout.activity_grades);

        findViewById(R.id.btn_grades_back).setOnClickListener(v -> finish());

        View syncBtn = findViewById(R.id.btn_sync_grades);
        if (syncBtn != null) syncBtn.setOnClickListener(v -> syncGrades());
        View emptySyncBtn = findViewById(R.id.btn_empty_sync_grades);
        if (emptySyncBtn != null) emptySyncBtn.setOnClickListener(v -> syncGrades());

        refresh();
    }

    /** 本次打开时已经翻开的新成绩。 */
    private final java.util.Set<String> revealed = new java.util.HashSet<>();
    /** 已经翻出批语、还没看分数的新成绩 → 那句批语。 */
    private final java.util.Map<String, String> verdicts = new java.util.HashMap<>();

    private void coverScore(TextView tv) {
        tv.setText("翻开");
        tv.setTextSize(11.5f);
        tv.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        tv.setTextColor(G.INK);
        // 纸张质感封签：暖纸底 + 发丝双线感描边，点击翻开
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(G.GLASS ? 10 : 3));
        bg.setColor(G.PAPER);
        bg.setStroke(Math.max(1, dp(1)), G.INK);
        tv.setBackground(bg);
        tv.setPadding(dp(11), dp(6), dp(11), dp(6));
    }

    /** 翻牌：带纸片抬升感（微缩放 + 3D 透视绕竖轴翻转），转到一半换真实分数，再回落并生长刻度条。 */
    private void flipReveal(View box, Runnable showScore, View bar, int barW) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            showScore.run();
            ViewGroup.LayoutParams l = bar.getLayoutParams();
            l.width = barW;
            bar.setLayoutParams(l);
            return;
        }
        box.setCameraDistance(9600 * getResources().getDisplayMetrics().density);
        box.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        box.animate()
                .rotationY(90f)
                .scaleX(1.04f)
                .scaleY(1.04f)
                .alpha(0.86f)
                .setDuration(155)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.3f))
                .withEndAction(() -> {
                    showScore.run();
                    box.setRotationY(-90f);
                    box.animate()
                            .rotationY(0f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(240)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                            .withEndAction(() -> {
                                android.animation.ValueAnimator grow = android.animation.ValueAnimator.ofInt(0, barW);
                                grow.setDuration(340);
                                grow.setInterpolator(new android.view.animation.DecelerateInterpolator());
                                grow.addUpdateListener(a -> {
                                    ViewGroup.LayoutParams l = bar.getLayoutParams();
                                    l.width = (int) a.getAnimatedValue();
                                    bar.setLayoutParams(l);
                                });
                                grow.start();
                            }).start();
                }).start();
    }

    /** 本次打开时的新成绩，离开页面后才清掉，方便用户看清是哪几门。 */
    private final java.util.Set<String> newIds = new java.util.HashSet<>();

    @Override
    protected void onResume() {
        super.onResume();
        newIds.clear();
        for (String k : Prefs.newGradeKeys(this)) {
            int bar = k.indexOf('|');
            newIds.add(bar >= 0 ? k.substring(bar + 1) : k);
        }
        refresh();
    }

    /**
     * 只把翻开过的那门从「新成绩」里去掉；没翻的下次进来还盖着、还带标记。
     * 全部看完才收掉通知。
     */
    private void markSeen(String cid) {
        java.util.List<String> keep = new java.util.ArrayList<>();
        for (String k : Prefs.newGradeKeys(this)) {
            int bar = k.indexOf('|');
            String id = bar >= 0 ? k.substring(bar + 1) : k;
            if (!id.equals(cid)) keep.add(k);
        }
        Prefs.setNewGradeKeys(this, keep);
        if (keep.isEmpty()) {
            Notify.cancelNewGrades(this);
        }
    }

    private void refresh() {
        currentGrades = GradesStore.load(this);
        currentGrades.recomputeAll();

        LinearLayout pillBox = findViewById(R.id.semester_pill_container);
        if (pillBox != null) {
            pillBox.removeAllViews();
            addSemesterPill(pillBox, "ALL", "全部学期");
            for (Grades.SemesterGrades sem : currentGrades.semesters) {
                addSemesterPill(pillBox, sem.id, formatShortSemester(sem.id));
            }
        }

        TextView tvGpa = findViewById(R.id.tv_gpa_value);
        TextView tvWeighted = findViewById(R.id.tv_weighted_value);
        TextView tvCreditSummary = findViewById(R.id.tv_grades_credit_summary);
        TextView tvScopeTag = findViewById(R.id.tv_grades_scope_tag);
        TextView tvSubtitle = findViewById(R.id.tv_grades_subtitle);

        if (tvSubtitle != null) {
            tvSubtitle.setText(Prefs.isGradesDemo(this)
                    ? "演示数据 · 仅用于预览界面，不代表真实成绩"
                    : currentGrades.totalCourseCount() + " 门课程 · "
                    + currentGrades.semesters.size() + " 个学期 · 本地秒开");
        }

        List<Grades.CourseGrade> displayCourses = new ArrayList<>();
        if ("ALL".equals(selectedSemesterFilter)) {
            for (Grades.SemesterGrades s : currentGrades.semesters) {
                displayCourses.addAll(s.courses);
            }
            if (tvGpa != null) {
                tvGpa.setText(String.format(Locale.US, "%.2f", currentGrades.summary.gpa));
            }
            if (tvWeighted != null) {
                tvWeighted.setText(String.format(Locale.US, "%.1f", currentGrades.summary.weightedScore));
            }
            if (tvCreditSummary != null) {
                tvCreditSummary.setText(String.format(Locale.US, "%.1f 学分 · %d 门通过",
                        currentGrades.summary.totalCredit, currentGrades.summary.passedCount));
            }
            if (tvScopeTag != null) tvScopeTag.setText("全部学期");
        } else {
            Grades.SemesterGrades matched = null;
            for (Grades.SemesterGrades s : currentGrades.semesters) {
                if (selectedSemesterFilter.equals(s.id)) {
                    matched = s;
                    break;
                }
            }
            if (matched != null) {
                matched.recompute();
                displayCourses.addAll(matched.courses);
                if (tvGpa != null) tvGpa.setText(String.format(Locale.US, "%.2f", matched.gpa));
                if (tvWeighted != null) {
                    tvWeighted.setText(String.format(Locale.US, "%.1f", matched.weightedScore));
                }
                if (tvCreditSummary != null) {
                    tvCreditSummary.setText(String.format(Locale.US, "%.1f 学分 · %d 门课程",
                            matched.totalCredit, matched.courses.size()));
                }
                if (tvScopeTag != null) tvScopeTag.setText(formatShortSemester(matched.id));
            } else {
                selectedSemesterFilter = "ALL";
            }
        }

        View emptyCard = findViewById(R.id.empty_grades_card);
        View skeleton = findViewById(R.id.grades_skeleton);
        boolean syncing = activeSync != null && activeSync.isRunning();
        boolean busy = syncing || awaitingLogin;
        TextView syncBtn = findViewById(R.id.btn_sync_grades);
        if (syncBtn != null) {
            syncBtn.setEnabled(!busy);
            syncBtn.setText(syncing ? "同步中…" : "↻ 同步");
        }
        TextView emptySyncBtn = findViewById(R.id.btn_empty_sync_grades);
        if (emptySyncBtn != null) {
            emptySyncBtn.setEnabled(!busy);
            emptySyncBtn.setText(syncing ? "同步中…" : "同步成绩");
        }
        if (skeleton != null) {
            skeleton.setVisibility(syncing && displayCourses.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (emptyCard != null) {
            emptyCard.setVisibility(displayCourses.isEmpty() && !syncing ? View.VISIBLE : View.GONE);
        }
        com.ecut.campus.view.InkEmptyView emptyArt = findViewById(R.id.empty_grades_illustration);
        if (emptyArt != null) emptyArt.setKind(com.ecut.campus.view.InkEmptyView.KIND_GRADES);

        com.ecut.campus.view.GpaGaugeChartView chart = findViewById(R.id.gpa_gauge_chart);
        if (chart != null) {
            List<com.ecut.campus.view.GpaGaugeChartView.Point> pts = new ArrayList<>();
            // 学期列表是倒序的（新学期在前），趋势图按时间从左到右正序展示
            for (int i = currentGrades.semesters.size() - 1; i >= 0; i--) {
                Grades.SemesterGrades s = currentGrades.semesters.get(i);
                s.recompute();
                if (s.courses.isEmpty()) continue;
                pts.add(new com.ecut.campus.view.GpaGaugeChartView.Point(
                        s.id, formatShortSemester(s.id), s.gpa));
            }
            chart.setData(pts, selectedSemesterFilter);
            chart.setOnPointSelectListener(semId -> {
                selectedSemesterFilter = semId.equals(selectedSemesterFilter) ? "ALL" : semId;
                refresh();
            });
        }

        LinearLayout listBox = findViewById(R.id.grades_list_container);
        if (listBox == null) return;
        listBox.removeAllViews();

        LayoutInflater inf = LayoutInflater.from(this);
        int idx = 0;
        for (Grades.CourseGrade cg : displayCourses) {
            idx++;
            View row = inf.inflate(R.layout.item_grade_card, listBox, false);
            View bar = row.findViewById(R.id.grade_color_bar);
            TextView tvIdx = row.findViewById(R.id.tv_grade_index);
            TextView tvName = row.findViewById(R.id.tv_grade_course_name);
            TextView tvMeta = row.findViewById(R.id.tv_grade_course_meta);
            TextView tvScore = row.findViewById(R.id.tv_grade_score_badge);

            tvIdx.setText(String.format(Locale.US, "%02d", idx));
            String cid = cg.code == null || cg.code.isEmpty() ? cg.name : cg.code;
            if (newIds.contains(cid)) {
                android.text.SpannableString sp = new android.text.SpannableString("● " + cg.name);
                sp.setSpan(new android.text.style.ForegroundColorSpan(G.ACCENT), 0, 1, 0);
                tvName.setText(sp);
            } else {
                tvName.setText(cg.name);
            }
            String gpText = cg.gradePoint != null ? String.format(Locale.US, "%.2f", cg.gradePoint) : "—";
            String nature = (cg.nature != null && !cg.nature.isEmpty()) ? cg.nature : "必修";
            tvMeta.setText(nature + " · " + cg.credit + " 学分 · 绩点 " + gpText);

            double v = cg.scoreValue != null ? cg.scoreValue : (cg.isPassed ? 85.0 : 50.0);
            boolean failed = !cg.isPassed || v < 60.0;
            int ink = failed ? G.alarm() : G.INK;
            // 非数字成绩（优秀/合格）字号收小，保持右侧刻度对齐
            boolean numeric = cg.score != null && cg.score.matches("[0-9.]+");
            int barW = Math.round(dp(56) * (float) Math.max(0.0, Math.min(100.0, v)) / 100f);
            Runnable showScore = () -> {
                tvScore.setText(cg.score);
                tvScore.setTextColor(ink);
                tvScore.setTextSize(numeric ? 22f : 14f);
                tvScore.setBackground(null);
                tvScore.setPadding(0, 0, 0, 0);
                bar.setBackgroundColor(ink);
            };
            ViewGroup.LayoutParams lp = bar.getLayoutParams();
            if (newIds.contains(cid) && !revealed.contains(cid)) {
                // 新成绩先盖着：点一下翻开
                coverScore(tvScore);
                lp.width = 0;
                bar.setLayoutParams(lp);
                if (verdicts.containsKey(cid)) GradeFlip.applyVerdict(tvMeta, tvScore, verdicts.get(cid));
                row.setOnClickListener(rv -> {
                    if (revealed.contains(cid)) return;
                    if (!verdicts.containsKey(cid)) {
                        // 翻牌批语：先给一句话，再点一下才看分数
                        String verdict = GradeFlip.verdictFor(this, cg);
                        if (!verdict.isEmpty()) {
                            verdicts.put(cid, verdict);
                            GradeFlip.flipToVerdict(tvMeta, tvScore, verdict);
                            return;
                        }
                    }
                    revealed.add(cid);
                    markSeen(cid);
                    flipReveal((View) tvScore.getParent(), showScore, bar, barW);
                });
            } else {
                showScore.run();
                lp.width = barW;
                bar.setLayoutParams(lp);
            }

            listBox.addView(row);
        }
    }

    /** 学期刻度标签：选中为墨色粗字 + 2dp 墨线，未选为弱文字，无胶囊。 */
    private void addSemesterPill(LinearLayout container, String semId, String label) {
        boolean selected = semId.equals(selectedSemesterFilter);
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setPadding(0, 0, dp(18), 0);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13f);
        tv.setTypeface(selected ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) : Typeface.MONOSPACE);
        tv.setTextColor(selected ? G.INK : G.MUTE);
        tv.setPadding(0, dp(4), 0, dp(10));
        tv.setSingleLine(true);
        tab.addView(tv);

        View line = new View(this);
        line.setBackgroundColor(selected ? G.INK : 0x00000000);
        tab.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));

        tab.setOnClickListener(v -> {
            selectedSemesterFilter = semId;
            refresh();
        });
        container.addView(tab);
    }

    private String formatShortSemester(String id) {
        if (id == null || id.isEmpty()) return "未知学期";
        String[] p = id.split("-");
        if (p.length >= 3 && p[0].length() == 4 && p[1].length() == 4) {
            return p[0].substring(2) + "-" + p[1].substring(2) + "(" + p[2] + ")";
        }
        return id;
    }

    private void syncGrades() {
        syncGrades(true);
    }

    private void syncGrades(boolean allowLoginRedirect) {
        if ((activeSync != null && activeSync.isRunning()) || awaitingLogin) {
            Toast.makeText(this, "正在同步，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (allowLoginRedirect && WebShellActivity.needsManualLoginFirst(this)) {
            awaitingLogin = true;
            refresh();
            WebShellActivity.startManualLoginForSync(this, WebShellActivity.REQ_SDJTU_GRADES, "成绩", null);
            return;
        }
        ViewGroup host = findViewById(R.id.grades_sync_host);
        WebView view = new WebView(this);
        view.setLayoutParams(new ViewGroup.LayoutParams(1, 1));
        view.setAlpha(0f);
        host.addView(view);

        boolean autoLogin = !Prefs.hasPortalSession(this) || Prefs.sessionExpired(this);
        Toast.makeText(this, autoLogin ? "正在自动登录并同步成绩…" : "正在后台同步成绩…", Toast.LENGTH_SHORT).show();
        activeSync = CampusSyncClient.start(view, CampusSyncClient.Kind.GRADES,
                new CampusSyncClient.Callback() {
                    @Override
                    public void onSuccess(int count) {
                        activeSync = null;
                        refresh();
                        Toast.makeText(GradesActivity.this,
                                "成绩已更新：" + count + " 门", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onLoginRequired(String message) {
                        activeSync = null;
                        if (allowLoginRedirect) {
                            awaitingLogin = true;
                            refresh();
                            WebShellActivity.startManualLoginForSync(GradesActivity.this,
                                    WebShellActivity.REQ_SDJTU_GRADES, "成绩", message);
                        } else {
                            refresh();
                            WebShellActivity.offerSdjtuLogin(GradesActivity.this, CampusSyncClient.Kind.GRADES, message);
                        }
                    }

                    @Override
                    public void onFailure(String message) {
                        activeSync = null;
                        refresh();
                        DiagLog.failDialog(GradesActivity.this, "成绩没同步成功", message);
                    }
                });
        refresh();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WebShellActivity.REQ_SDJTU_GRADES) {
            awaitingLogin = false;
            if (resultCode == RESULT_OK) {
                syncGrades(false);
            } else {
                refresh();
                Toast.makeText(this, "登录已取消，可重新点击同步", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (activeSync != null) {
            activeSync.cancel();
            activeSync = null;
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
