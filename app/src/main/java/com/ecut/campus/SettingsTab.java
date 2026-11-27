package com.ecut.campus;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.ecut.campus.data.Grades;
import com.ecut.campus.data.GradesStore;
import com.ecut.campus.data.SdjtuPortal;
import com.ecut.campus.data.Timetable;
import com.ecut.campus.data.TimetableStore;

import java.util.Locale;

/** 「设置」页签（从 MainActivity 拆出）：一级入口、两个二级页、学校/账号状态、外观与通知。 */
final class SettingsTab {

    private final MainActivity a;
    private final View settingsFragment;
    private boolean probingSession;
    private com.ecut.campus.data.SdjtuSessionCheck.State lastProbeState =
            com.ecut.campus.data.SdjtuSessionCheck.State.UNKNOWN;

    SettingsTab(MainActivity a, View root) {
        this.a = a;
        this.settingsFragment = root;
    }

    void bind() {
        View scroll = settingsFragment.findViewById(R.id.settings_scroll);
        if (scroll != null) {
            scroll.setOnScrollChangeListener((v, sx, sy, osx, osy) -> a.refreshDockBlur());
        }
        TextView versionTv = settingsFragment.findViewById(R.id.tv_about_version);
        if (versionTv != null) {
            versionTv.setText("驴驴课表 · v" + BuildConfig.VERSION_NAME);
        }
        refreshAboutBadge();
        View aboutCard = settingsFragment.findViewById(R.id.card_about);
        if (aboutCard != null) aboutCard.setOnClickListener(v ->
                a.startActivity(new Intent(a, AboutActivity.class)));
        View starTv = settingsFragment.findViewById(R.id.tv_about_star);
        if (starTv != null) {
            starTv.setVisibility(Updater.hasReleaseRepository() ? View.VISIBLE : View.GONE);
            starTv.setOnClickListener(v -> Updater.open(a, Updater.REPO_URL));
        }

        // ===== 0.4：一级只放入口，二级页在 fragment 内切换 =====
        TextView schoolName = settingsFragment.findViewById(R.id.tv_settings_school_name);
        schoolName.setText(SdjtuPortal.NAME);
        android.widget.ImageView seal = settingsFragment.findViewById(R.id.iv_settings_sdjtu_seal);
        // Recover the red ink from the supplied seal's grey preview background.
        seal.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{
                0, 0, 0, 0, 152,
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 0,
                255f / 152, -255f / 152, 0, 0, 0}));
        View rooms = settingsFragment.findViewById(R.id.row_settings_classrooms);
        rooms.setOnClickListener(v -> openStudy(SdjtuAcademicPages.Feature.ROOMS));
        settingsFragment.findViewById(R.id.row_settings_plan)
                .setOnClickListener(v -> openStudy(SdjtuAcademicPages.Feature.PLAN));
        settingsFragment.findViewById(R.id.row_settings_exams)
                .setOnClickListener(v -> a.startActivity(new Intent(a, SdjtuExamActivity.class)));
        View mateRow = settingsFragment.findViewById(R.id.row_settings_classmates);
        if (mateRow != null) mateRow.setOnClickListener(v ->
                a.startActivity(new Intent(a, ClassmatesActivity.class)));
        View ttRow = settingsFragment.findViewById(R.id.row_settings_timetable_page);
        if (ttRow != null) ttRow.setOnClickListener(v -> showPage(R.id.settings_page_timetable));
        View funRow = settingsFragment.findViewById(R.id.row_settings_fun);
        if (funRow != null) funRow.setOnClickListener(v ->
                a.startActivity(new Intent(a, com.ecut.campus.fun.FunActivity.class)));
        View genRow = settingsFragment.findViewById(R.id.row_settings_general_page);
        if (genRow != null) genRow.setOnClickListener(v -> showPage(R.id.settings_page_general));
        View backTt = settingsFragment.findViewById(R.id.btn_settings_back_tt);
        if (backTt != null) backTt.setOnClickListener(v -> showPage(R.id.settings_page_root));
        View backGen = settingsFragment.findViewById(R.id.btn_settings_back_general);
        if (backGen != null) backGen.setOnClickListener(v -> showPage(R.id.settings_page_root));
        View icsRow = settingsFragment.findViewById(R.id.row_export_ics);
        if (icsRow != null) icsRow.setOnClickListener(v -> CalendarSyncManager.showMenu(a, this::refresh));
        bindClassRemind();
        bindNextClassNotice();
        com.google.android.material.materialswitch.MaterialSwitch rainSw =
                settingsFragment.findViewById(R.id.switch_rain_hint);
        if (rainSw != null) {
            rainSw.setChecked(Prefs.rainHint(a));
            rainSw.setOnCheckedChangeListener((b, on) -> {
                Prefs.setRainHint(a, on);
                if (on) {
                    RainWeather.refreshIfNeeded(a, false, a::refreshTimetableTab);
                }
                renderRainHintSub();
                a.refreshTimetableTab();
                ClassWidget.updateAll(a.getApplicationContext());
            });
            View rainRow = settingsFragment.findViewById(R.id.row_rain_hint);
            if (rainRow != null) {
                rainRow.setOnClickListener(v -> showRainCampusPicker(rainSw));
            }
            renderRainHintSub();
        }
        com.google.android.material.materialswitch.MaterialSwitch gradeSw =
                settingsFragment.findViewById(R.id.switch_grade_notify);
        if (gradeSw != null) {
            gradeSw.setChecked(Prefs.gradeNotify(a));
            gradeSw.setOnCheckedChangeListener((b, on) -> {
                Prefs.setGradeNotify(a, on);
                GradeCheckWorker.schedule(a);
                if (on) Notify.requestPermissionIfNeeded(a);
            });
        }
        com.google.android.material.materialswitch.MaterialSwitch schedChangeSw =
                settingsFragment.findViewById(R.id.switch_schedule_change_notify);
        if (schedChangeSw != null) {
            schedChangeSw.setChecked(Prefs.scheduleChangeNotify(a));
            schedChangeSw.setOnCheckedChangeListener((b, on) -> {
                Prefs.setScheduleChangeNotify(a, on);
                TimetableCheckWorker.schedule(a);
                if (on) Notify.requestPermissionIfNeeded(a);
            });
            View schedChangeRow = settingsFragment.findViewById(R.id.row_schedule_change_notify);
            if (schedChangeRow != null) schedChangeRow.setOnClickListener(v -> schedChangeSw.toggle());
        }
        renderThemeSegment();
        renderNightSegment();
        bindWidget();

        View gradesCard = settingsFragment.findViewById(R.id.card_settings_grades);
        if (gradesCard != null) {
            gradesCard.setOnClickListener(v ->
                    a.startActivity(new Intent(a, GradesActivity.class)));
        }

        View scheduleCard = settingsFragment.findViewById(R.id.card_settings_schedule);
        if (scheduleCard != null) {
            scheduleCard.setOnClickListener(v ->
                    a.startActivity(new Intent(a, ScheduleSettingsActivity.class)));
        }

        View timetableCard = settingsFragment.findViewById(R.id.card_settings_timetable);
        if (timetableCard != null) {
            timetableCard.setOnClickListener(v ->
                    a.startActivity(new Intent(a, TimetableSettingsActivity.class)));
        }


        View autoLoginNowBtn = settingsFragment.findViewById(R.id.btn_settings_auto_login);
        if (autoLoginNowBtn != null) autoLoginNowBtn.setOnClickListener(v -> a.autoLogin());

        View openPortalBtn = settingsFragment.findViewById(R.id.btn_settings_open_portal);
        if (openPortalBtn != null) openPortalBtn.setOnClickListener(v -> a.openPortal());

        settingsFragment.findViewById(R.id.btn_sdjtu_auto_login)
                .setOnClickListener(v -> SdjtuCredentialsDialog.show(a, this::refresh));

        View exportBtn = settingsFragment.findViewById(R.id.btn_export_timetable);
        if (exportBtn != null) exportBtn.setOnClickListener(v -> TimetableFiles.exportJson(a));

        View importBtn = settingsFragment.findViewById(R.id.btn_import_timetable);
        if (importBtn != null) importBtn.setOnClickListener(v -> TimetableFiles.pickImport(a));

        View backupBtn = settingsFragment.findViewById(R.id.btn_full_backup);
        if (backupBtn != null) backupBtn.setOnClickListener(v -> AppBackupManager.exportBackup(a));

        View restoreBtn = settingsFragment.findViewById(R.id.btn_full_restore);
        if (restoreBtn != null) restoreBtn.setOnClickListener(v -> AppBackupManager.pickRestore(a));

        View diagRow = settingsFragment.findViewById(R.id.row_export_diag);
        if (diagRow != null) diagRow.setOnClickListener(v -> DiagLog.export(a));

        View advToggle = settingsFragment.findViewById(R.id.row_advanced_toggle);
        View advBody = settingsFragment.findViewById(R.id.box_advanced_body);
        TextView advChev = settingsFragment.findViewById(R.id.tv_advanced_chevron);
        if (advToggle != null && advBody != null) {
            advToggle.setOnClickListener(v -> {
                boolean open = advBody.getVisibility() != View.VISIBLE;
                advBody.setVisibility(open ? View.VISIBLE : View.GONE);
                if (advChev != null) advChev.setText(open ? "▾" : "▸");
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                a.refreshDockBlur();
            });
        }

        View clearDataBtn = settingsFragment.findViewById(R.id.btn_clear_local_data);
        if (clearDataBtn != null) {
            clearDataBtn.setOnClickListener(v -> new AlertDialog.Builder(a)
                    .setTitle("清空本地数据")
                    .setMessage("将清空课表、成绩、空教室查询结果、培养方案、考试安排、作业待办与上课时间配置，并删除本机保存的自动登录账号密码。清空后无法恢复。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("清空", (d, w) -> {
                        boolean credentialsCleared = SdjtuCredentials.clear(a);
                        TimetableStore.clear(a);
                        GradesStore.clear(a);
                        SdjtuStudyStore.clear(a);
                        CourseTodoStore.clear(a);
                        ScheduleSettingsStore.clear(a);
                        Prefs.setLastSyncTime(a, "");
                        Prefs.setGradesDemo(a, false);
                        Prefs.setNewGradeKeys(a, java.util.Collections.emptySet());
                        Prefs.setSemesterStartDate(a, "");
                        Prefs.setCurrentWeek(a, 1);
                        Notify.cancelNewGrades(a);
                        ClassWidget.updateAll(a.getApplicationContext());
                        ClassReminder.reschedule(a.getApplicationContext());
                        ExamReminder.reschedule(a.getApplicationContext());
                        TodoReminder.reschedule(a.getApplicationContext());
                        NextClassNotice.update(a.getApplicationContext());
                        a.refreshTimetableTab();
                        refresh();
                        Toast.makeText(a, credentialsCleared ? "本地数据已清空" : "已清空教务数据并停用自动登录，凭据清除失败",
                                Toast.LENGTH_LONG).show();
                    })
                    .show());
        }
    }

    private int settingsPage = R.id.settings_page_root;

    void showPage(int pageId) {
        showPage(pageId, true);
    }

    void showPage(int pageId, boolean animate) {
        int[] pages = {R.id.settings_page_root, R.id.settings_page_timetable, R.id.settings_page_general};
        boolean forward = pageId != R.id.settings_page_root;
        for (int id : pages) {
            View p = settingsFragment.findViewById(id);
            if (p == null) continue;
            p.animate().cancel();
            if (id == pageId) {
                p.setVisibility(View.VISIBLE);
                if (animate) {
                    p.setAlpha(0f);
                    p.setTranslationX((forward ? 1 : -1) * 36 * a.getResources().getDisplayMetrics().density);
                    p.animate().alpha(1f).translationX(0f).setDuration(220)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
                } else {
                    p.setAlpha(1f);
                    p.setTranslationX(0f);
                }
            } else {
                p.setVisibility(View.GONE);
            }
        }
        settingsPage = pageId;
        a.updateBackNavigationState();
        View scroll = settingsFragment.findViewById(R.id.settings_scroll);
        if (scroll != null) {
            scroll.scrollTo(0, 0);
            scroll.post(a::refreshDockBlur);
        }
        a.refreshDockBlur();
    }

    /** 主题两段：纸墨 / 磨砂玻璃。切换时从点的位置圆形扩散。 */
    private void renderThemeSegment() {
        renderSegment(R.id.seg_theme, new String[]{"纸墨", "磨砂玻璃"}, Prefs.theme(a), (v, theme) -> {
            if (theme == Prefs.theme(a)) return;
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            ThemeReveal.capture(a, v, a.currentTab, settingsPage);
            Prefs.setTheme(a, theme);
            a.recreate();
        });
    }

    /** 深色模式三段：跟随系统 / 浅色 / 深色。 */
    private void renderNightSegment() {
        renderSegment(R.id.seg_night_mode, new String[]{"跟随系统", "浅色", "深色"}, Prefs.nightMode(a), (v, mode) -> {
            if (mode == Prefs.nightMode(a)) return;
            ThemeReveal.capture(a, v, a.currentTab, settingsPage);
            Prefs.setNightMode(a, mode);
            ClassWidget.updateAll(a.getApplicationContext()); // 桌面小组件跟着换深浅色
            App.applyNightMode(mode); // 会重建 Activity
        });
    }

    private interface SegmentPick { void pick(View v, int index); }

    /** 分段选择器：外框（纸墨发丝描边 / 玻璃浅底）+ 选中段墨色实底。主题、深色模式共用。 */
    private void renderSegment(int segId, String[] labels, int cur, SegmentPick onPick) {
        LinearLayout seg = settingsFragment.findViewById(segId);
        if (seg == null) return;
        seg.removeAllViews();
        float d = a.getResources().getDisplayMetrics().density;
        seg.setBackground(Ui.segmentBox(a));
        seg.setPadding(Math.round(3 * d), Math.round(3 * d), Math.round(3 * d), Math.round(3 * d));
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView tv = new TextView(a);
            tv.setText(labels[i]);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setTextSize(12.5f);
            Ui.segmentItem(tv, i == cur);
            tv.setOnClickListener(v -> onPick.pick(v, index));
            seg.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
    }

    boolean isInSubPage() {
        return settingsPage != R.id.settings_page_root;
    }

    /** 返回键：在二级页时先回一级页，返回 true 表示已处理。 */
    boolean backToRoot() {
        if (settingsPage == R.id.settings_page_root) return false;
        showPage(R.id.settings_page_root);
        return true;
    }

    private void renderRainHintSub() {
        TextView sub = settingsFragment.findViewById(R.id.tv_rain_hint_sub);
        if (sub == null) return;
        com.ecut.campus.data.RainForecast.Campus c = RainWeather.currentCampus(a);
        sub.setText(c.name + "（" + c.region + "）· 点此切换校区");
    }

    private void showRainCampusPicker(com.google.android.material.materialswitch.MaterialSwitch rainSw) {
        com.ecut.campus.data.RainForecast.Campus[] all = com.ecut.campus.data.RainForecast.Campus.values();
        String[] labels = new String[all.length];
        String curId = Prefs.rainCampus(a);
        int checked = 0;
        for (int i = 0; i < all.length; i++) {
            labels[i] = all[i].displayLabel();
            if (all[i].id.equals(curId)) checked = i;
        }
        new AlertDialog.Builder(a)
                .setTitle("选择下雨预报校区")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    com.ecut.campus.data.RainForecast.Campus chosen = all[which];
                    boolean changed = !chosen.id.equals(Prefs.rainCampus(a));
                    Prefs.setRainCampus(a, chosen.id);
                    if (!Prefs.rainHint(a)) {
                        Prefs.setRainHint(a, true);
                        rainSw.setChecked(true);
                    }
                    if (changed) RainWeather.clearCache(a);
                    renderRainHintSub();
                    RainWeather.refreshIfNeeded(a, true, a::refreshTimetableTab);
                    Toast.makeText(a, "已切换为" + chosen.name + "天气", Toast.LENGTH_SHORT).show();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    void refresh() {
        if (settingsFragment == null) return;
        TextView gradeSummary = settingsFragment.findViewById(R.id.tv_settings_grade_summary);
        if (gradeSummary != null) {
            Grades grades = GradesStore.load(a);
            if (grades.totalCourseCount() == 0) {
                gradeSummary.setText("尚未同步成绩");
            } else {
                gradeSummary.setText(String.format(Locale.US, "GPA %.2f  ·  加权 %.1f%s",
                        grades.summary.gpa, grades.summary.weightedScore,
                        Prefs.isGradesDemo(a) ? "  ·  演示数据" : ""));
            }
        }

        TextView gradeNew = settingsFragment.findViewById(R.id.tv_settings_grade_new);
        if (gradeNew != null) {
            int n = Prefs.newGradeKeys(a).size();
            if (n > 0) {
                gradeNew.setText("● " + n + " 门新成绩");
                gradeNew.setTextSize(11f);
                gradeNew.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                gradeNew.setTextColor(G.ACCENT);
            } else {
                gradeNew.setText("›");
                gradeNew.setTextSize(22f);
                gradeNew.setTypeface(Typeface.DEFAULT);
                gradeNew.setTextColor(G.MUTE);
            }
        }

        TextView scheduleSummary = settingsFragment.findViewById(R.id.tv_settings_schedule_summary);
        if (scheduleSummary != null) {
            ScheduleSettings s = ScheduleSettingsStore.load(a);
            int n = s.sectionCount();
            scheduleSummary.setText("每天 " + n + " 节  ·  " + s.firstClassStart + "–" + s.endTime(n));
        }

        TextView timetableSummary = settingsFragment.findViewById(R.id.tv_settings_timetable_summary);
        if (timetableSummary != null) {
            String start = Prefs.semesterStartDate(a);
            timetableSummary.setText((Prefs.displayDays(a) == 5 ? "五天模式" : "七天模式")
                    + (start.isEmpty() ? "  ·  未设开学日期" : ""));
        }
        com.google.android.material.materialswitch.MaterialSwitch rainSw =
                settingsFragment.findViewById(R.id.switch_rain_hint);
        if (rainSw != null && rainSw.isChecked() != Prefs.rainHint(a)) {
            rainSw.setChecked(Prefs.rainHint(a));
        }
        renderRainHintSub();

        bindAccountStatus();
        refreshAboutBadge();

        TextView icsSub = settingsFragment.findViewById(R.id.tv_ics_sub);
        if (icsSub != null) {
            icsSub.setText(Prefs.calendarAutoSync(a)
                    ? "已开启自动更新 · 点击写入或导出 .ics"
                    : "一键写入系统日历，或导出 .ics");
        }

        TextView diagSub = settingsFragment.findViewById(R.id.tv_diag_sub);
        if (diagSub != null) {
            int snaps = DiagLog.snapshotCount(a);
            diagSub.setText(snaps > 0
                    ? "含 " + snaps + " 份解析失败快照 · 不含账号密码"
                    : "运行日志与页面结构样本 · 不含账号密码");
        }

        TextView summaryTv = settingsFragment.findViewById(R.id.tv_settings_data_summary);
        if (summaryTv != null) {
            Timetable t = TimetableStore.load(a);
            Grades g = GradesStore.load(a);
            String sync = Prefs.lastSyncTime(a);
            summaryTv.setText("本地课表：" + t.courses.size() + " 门  ·  本地成绩："
                    + g.totalCourseCount() + " 门"
                    + (sync.isEmpty() ? "" : ("  ·  更新于 " + sync)));
        }
    }

    void refreshAboutBadge() {
        if (settingsFragment == null) return;
        TextView badge = settingsFragment.findViewById(R.id.tv_about_update_badge);
        if (badge == null) return;
        String tag = Updater.availableUpdateTag(a);
        if (!tag.isEmpty()) {
            badge.setText("● 新版本 " + tag);
            badge.setTextColor(G.alarm());
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }
    }

    private void openStudy(SdjtuAcademicPages.Feature feature) {
        a.startActivity(new Intent(a, SdjtuStudyActivity.class)
                .putExtra(WebShellActivity.EXTRA_TARGET_KEY, feature.target));
    }

    private void bindAccountStatus() {
        TextView autoLoginBtn = settingsFragment.findViewById(R.id.btn_settings_auto_login);
        TextView autoLogin = settingsFragment.findViewById(R.id.btn_sdjtu_auto_login);
        TextView status = settingsFragment.findViewById(R.id.host_status_text);
        TextView badge = settingsFragment.findViewById(R.id.tv_settings_node_badge);
        if (autoLoginBtn != null) {
            autoLoginBtn.setEnabled(!a.autoLoginBusy && !SdjtuCredentials.isUpdating());
            autoLoginBtn.setText(a.autoLoginBusy ? "同步中…" : "自动登录");
        }
        if (a.autoLoginBusy) {
            status.setText("正在同步，预计 5-10 秒…");
            badge.setText("○ 同步中");
            badge.setTextColor(G.ACCENT);
            autoLogin.setEnabled(false);
            return;
        }
        if (Prefs.hasPortalSession(a) && !SdjtuSessionProbe.hasSchoolCookie(a)) {
            Prefs.setPortalSession(a, false);
            Prefs.setSessionExpired(a, true);
        }
        boolean saved = SdjtuCredentials.isSaved(a);
        boolean connected = Prefs.hasPortalSession(a);
        boolean expired = Prefs.sessionExpired(a);
        boolean paused = SdjtuAutoLogin.isPaused(a);
        autoLogin.setText(saved ? "管理自动登录" : "设置自动登录");
        autoLogin.setEnabled(!SdjtuCredentials.isUpdating());

        long verifiedAt = Prefs.portalSessionVerifiedAt(a);
        long age = verifiedAt > 0 ? Math.max(0L, System.currentTimeMillis() - verifiedAt) : Long.MAX_VALUE;
        if (connected && !expired && !probingSession && age > SdjtuSessionProbe.RECHECK_INTERVAL_MS) {
            probingSession = true;
            SdjtuSessionProbe.probeAsync(a, state -> {
                probingSession = false;
                lastProbeState = state;
                if (a.isFinishing() || a.isDestroyed()) return;
                bindAccountStatus();
                if (state == com.ecut.campus.data.SdjtuSessionCheck.State.EXPIRED) {
                    a.refreshTimetableTab();
                }
            });
        }

        if (expired) {
            com.ecut.campus.data.SessionNotice.Kind kind =
                    com.ecut.campus.data.SessionNotice.of(true, saved, paused, false);
            status.setText(com.ecut.campus.data.SessionNotice.status(kind));
            badge.setText(com.ecut.campus.data.SessionNotice.badge(kind));
            badge.setTextColor(G.ACCENT);
        } else if (connected) {
            if (probingSession && age > SdjtuSessionProbe.STALE_THRESHOLD_MS) {
                status.setText(saved ? "已启用自动登录，正在校验会话…" : "正在校验登录状态…");
                badge.setText("○ 校验中");
                badge.setTextColor(G.MUTE);
            } else if (lastProbeState == com.ecut.campus.data.SdjtuSessionCheck.State.UNKNOWN
                    && age > SdjtuSessionProbe.OFFLINE_STALE_MS) {
                status.setText(saved ? "已启用自动登录，暂无法联网校验" : "暂无法联网确认登录状态");
                badge.setText("○ 待确认");
                badge.setTextColor(G.MUTE);
            } else {
                status.setText(saved ? "已启用自动登录，可直接同步" : "会话有效，可直接同步");
                badge.setText("● 已登录");
                badge.setTextColor(G.INK);
            }
        } else if (saved) {
            status.setText("已启用自动登录，可直接同步");
            badge.setText("○ 待同步");
            badge.setTextColor(G.INK);
        } else {
            status.setText("登录后可同步课表与成绩");
            badge.setText("○ 待连接");
            badge.setTextColor(G.MUTE);
        }
    }

    private static final int[] REMIND_LEADS = {5, 10, 15, 20};

    // ===== 桌面组件：一键添加 + 自选背景 =====
    private void bindWidget() {
        View pin = settingsFragment.findViewById(R.id.row_widget_pin);
        if (pin != null) pin.setOnClickListener(v -> PinWidgetActivity.choose(a));
        View allow = settingsFragment.findViewById(R.id.row_widget_bg_allow);
        if (allow != null) allow.setOnClickListener(v -> BgAllow.show(a));
        View reset = settingsFragment.findViewById(R.id.btn_widget_bg_reset);
        if (reset != null) reset.setOnClickListener(v -> {
            WidgetBg.clear(a, true);
            ClassWidget.updateAll(a.getApplicationContext());
            renderWidgetBg();
            Toast.makeText(a, "组件背景已恢复默认", Toast.LENGTH_SHORT).show();
        });
        android.widget.SeekBar seek = settingsFragment.findViewById(R.id.seek_widget_mask);
        if (seek != null) {
            seek.setProgress(Prefs.widgetMask(a));
            seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar b, int p, boolean user) {
                    TextView tv = settingsFragment.findViewById(R.id.tv_widget_mask);
                    if (tv != null) tv.setText(p + "%");
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar b) {}
                @Override public void onStopTrackingTouch(android.widget.SeekBar b) {
                    Prefs.setWidgetMask(a, b.getProgress());
                    ClassWidget.updateAll(a.getApplicationContext()); // 松手就刷新桌面上的小组件
                    renderWidgetBg();
                }
            });
        }
        renderWidgetBg();
    }

    /** 组件背景一栏：分段（默认 / 选一张图片）、遮罩滑杆、按图亮度给的提示。 */
    void renderWidgetBg() {
        boolean img = WidgetBg.active(a);
        int mask = Prefs.widgetMask(a);
        renderSegment(R.id.seg_widget_bg, new String[]{"默认", "选一张图片"}, img ? 1 : 0, (v, i) -> {
            if (i == 1) {
                WidgetBg.pick(a); // 已经有图时再点也能换一张
            } else if (WidgetBg.active(a)) {
                WidgetBg.clear(a, false);
                ClassWidget.updateAll(a.getApplicationContext());
                renderWidgetBg();
            }
        });
        View box = settingsFragment.findViewById(R.id.box_widget_mask);
        if (box != null) box.setVisibility(img ? View.VISIBLE : View.GONE);
        android.widget.SeekBar seek = settingsFragment.findViewById(R.id.seek_widget_mask);
        if (seek != null && seek.getProgress() != mask) seek.setProgress(mask);
        TextView mtv = settingsFragment.findViewById(R.id.tv_widget_mask);
        if (mtv != null) mtv.setText(mask + "%");
        TextView sub = settingsFragment.findViewById(R.id.tv_widget_bg_sub);
        if (sub != null) sub.setText(img ? "自选图片 · 遮罩 " + mask + "%" : "默认纸色，随深浅色切换");
        TextView hint = settingsFragment.findViewById(R.id.tv_widget_bg_hint);
        if (hint != null && img) {
            double g = WidgetBg.headerGray(a);
            boolean night = (a.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            double mg = com.ecut.campus.data.WidgetLook.gray(WidgetBg.maskColor(night));
            int want = g < 0 ? mask : com.ecut.campus.data.WidgetLook.suggestedMask(g, mg, mask);
            hint.setText(want > mask
                    ? "标题处明暗混杂，建议遮罩调至 " + want + "%"
                    : "遮罩越高字越清，文字深浅自动适配");
        }
        TextView pinSub = settingsFragment.findViewById(R.id.tv_widget_pin_sub);
        if (pinSub != null) {
            int n = ClassWidget.placedCount(a);
            pinSub.setText(n > 0 ? "已添加 " + n + " 个 · 课表 / 倒计时 / 进度条"
                    : "今日课程 · 2×2 倒计时 · 4×1 进度条");
        }
        TextView allowSub = settingsFragment.findViewById(R.id.tv_widget_bg_allow_sub);
        if (allowSub != null) allowSub.setText(BgAllow.status(a));
    }

    /** 选完图片回来：后台复制缩小，好了刷新小组件和这一栏。 */
    void onWidgetImagePicked(android.net.Uri uri) {
        Toast.makeText(a, "正在处理图片…", Toast.LENGTH_SHORT).show();
        android.content.Context app = a.getApplicationContext();
        Bg.io(() -> {
            boolean ok = WidgetBg.importFrom(app, uri);
            if (ok) ClassWidget.updateAll(app);
            return ok;
        }, ok -> {
            if (a.isFinishing() || a.isDestroyed()) return;
            renderWidgetBg();
            Toast.makeText(a, ok ? "组件背景换好了" : "这张图读不了，换一张试试", Toast.LENGTH_SHORT).show();
        });
    }

    private void bindClassRemind() {
        com.google.android.material.materialswitch.MaterialSwitch sw =
                settingsFragment.findViewById(R.id.switch_class_remind);
        TextView sub = settingsFragment.findViewById(R.id.tv_class_remind_sub);
        View row = settingsFragment.findViewById(R.id.row_class_remind);
        if (sw == null) return;
        Runnable label = () -> {
            if (sub == null) return;
            String t = "提前 " + Prefs.classRemindLead(a) + " 分钟提醒";
            if (Prefs.classRemind(a) && !ClassReminder.canNotify(a)) t += " · 通知未开";
            else if (Prefs.classRemind(a)) t += " · 点此改时间";
            sub.setText(t);
        };
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(Prefs.classRemind(a));
        label.run();
        sw.setOnCheckedChangeListener((b, on) -> {
            Prefs.setClassRemind(a, on);
            if (on) Notify.requestPermissionIfNeeded(a);
            ClassReminder.reschedule(a);
            label.run();
        });
        if (row != null) row.setOnClickListener(v -> {
            if (!Prefs.classRemind(a)) { sw.setChecked(true); return; }
            String[] items = new String[REMIND_LEADS.length];
            int checked = 1;
            for (int i = 0; i < REMIND_LEADS.length; i++) {
                items[i] = "提前 " + REMIND_LEADS[i] + " 分钟";
                if (REMIND_LEADS[i] == Prefs.classRemindLead(a)) checked = i;
            }
            new AlertDialog.Builder(a).setTitle("上课前多久提醒")
                    .setSingleChoiceItems(items, checked, (d, w) -> {
                        Prefs.setClassRemindLead(a, REMIND_LEADS[w]);
                        ClassReminder.reschedule(a);
                        label.run();
                        d.dismiss();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }
    // ===== 1.2.7：通知栏常驻「下一节课」（默认关） =====
    private void bindNextClassNotice() {
        com.google.android.material.materialswitch.MaterialSwitch sw =
                settingsFragment.findViewById(R.id.switch_next_class_notice);
        TextView sub = settingsFragment.findViewById(R.id.tv_next_class_notice_sub);
        View row = settingsFragment.findViewById(R.id.row_next_class_notice);
        if (sw == null) return;
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(Prefs.nextClassNotice(a));
        renderNextClassNoticeSub(sub);
        sw.setOnCheckedChangeListener((b, on) -> {
            Prefs.setNextClassNotice(a, on);
            if (on) Notify.requestPermissionIfNeeded(a);
            NextClassNotice.update(a.getApplicationContext());
            renderNextClassNoticeSub(sub);
        });
        if (row != null) row.setOnClickListener(v -> sw.toggle());
    }

    private void renderNextClassNoticeSub(TextView sub) {
        if (sub == null) return;
        String t = "常驻静音通知，显示课名与教室";
        if (Prefs.nextClassNotice(a) && !ClassReminder.canNotify(a)) t = "通知未开启，无法显示";
        sub.setText(t);
    }
}
