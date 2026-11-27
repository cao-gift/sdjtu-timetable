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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 查空教室页面的条件构造、顶部条件卡、节次筛选条与教室节次矩阵卡片渲染（从 SdjtuStudyActivity 拆出）。
 */
final class SdjtuStudyRoomsRenderer {

    static final class RoomEntry {
        final String building, room, state, badge;
        final List<SdjtuStudyFormat.PeriodSlot> periods;
        final int freeCount;
        final SdjtuStudyFormat.StudyRecommendation rec;

        RoomEntry(String building, String room, String state, String badge,
                  List<SdjtuStudyFormat.PeriodSlot> periods, int freeCount,
                  SdjtuStudyFormat.StudyRecommendation rec) {
            this.building = building;
            this.room = room;
            this.state = state;
            this.badge = badge;
            this.periods = periods;
            this.freeCount = freeCount;
            this.rec = rec == null ? SdjtuStudyFormat.StudyRecommendation.EMPTY : rec;
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

    SdjtuStudyRoomsRenderer(Activity a) {
        this.a = a;
    }

    JSONObject defaultQueryRequest(int curWeek, int curDay) {
        String campusVal = Prefs.studyRoomCampusValue(a);
        String campusHint = Prefs.effectiveStudyRoomCampusHint(a);
        String buildingVal = Prefs.studyRoomBuildingValue(a);
        String buildingHint = Prefs.effectiveStudyRoomBuildingHint(a);

        JSONObject cond = new JSONObject();
        if (!campusVal.isEmpty()) SdjtuStudyActivity.put(cond, "campus", campusVal);
        if (!buildingVal.isEmpty()) SdjtuStudyActivity.put(cond, "building", buildingVal);
        SdjtuStudyActivity.put(cond, "campusHint", campusHint);
        SdjtuStudyActivity.put(cond, "buildingHint", buildingHint);
        SdjtuStudyActivity.put(cond, "fallbackFirstBuilding", buildingVal.isEmpty());
        SdjtuStudyActivity.put(cond, "week", String.valueOf(curWeek));
        SdjtuStudyActivity.put(cond, "day", String.valueOf(curDay));
        SdjtuStudyActivity.put(cond, "period", "");
        SdjtuStudyActivity.put(cond, "room", "");
        SdjtuStudyActivity.put(cond, "requireBuilding", true);
        SdjtuStudyActivity.put(cond, "campusLabel", campusHint);
        SdjtuStudyActivity.put(cond, "buildingLabel", buildingHint.isEmpty() ? "默认教学楼" : buildingHint);
        SdjtuStudyActivity.put(cond, "weekLabel", "第" + curWeek + "周");
        SdjtuStudyActivity.put(cond, "dayLabel", SdjtuStudyFormat.weekdayLabel(curDay));

        JSONObject query = new JSONObject();
        SdjtuStudyActivity.put(query, "day", curDay);
        SdjtuStudyActivity.put(query, "period", "all");

        JSONObject req = new JSONObject();
        SdjtuStudyActivity.put(req, "roomAction", "query");
        SdjtuStudyActivity.put(req, "roomConditions", cond);
        SdjtuStudyActivity.put(req, "query", query);
        return req;
    }

    void renderHeroCard(LinearLayout heroCard, @Nullable JSONObject data, JSONObject request,
                        long syncedAt, int defaultWeek, boolean busy, boolean awaitingLogin,
                        Runnable onQuery, Runnable onPick) {
        heroCard.removeAllViews();
        heroCard.setVisibility(View.VISIBLE);
        JSONObject cond = (data != null && data.optJSONObject("roomConditions") != null)
                ? data.optJSONObject("roomConditions")
                : request.optJSONObject("roomConditions");
        String campusFallback = Prefs.effectiveStudyRoomCampusHint(a);
        String buildingFallback = Prefs.effectiveStudyRoomBuildingHint(a);
        String campusLabel = labelOfOption(cond, "campuses", cond == null ? "" : cond.optString("campus"),
                cond == null ? campusFallback : cond.optString("campusLabel", campusFallback));
        String buildingLabel = labelOfOption(cond, "buildings", cond == null ? "" : cond.optString("building"),
                cond == null ? buildingFallback : cond.optString("buildingLabel", buildingFallback));
        String weekNum = cond == null ? "" : cond.optString("week");
        if (weekNum.isEmpty()) weekNum = String.valueOf(defaultWeek);
        int dayNum = data != null && data.optJSONObject("query") != null && data.optJSONObject("query").optInt("day") > 0
                ? data.optJSONObject("query").optInt("day")
                : (cond != null && !cond.optString("day").isEmpty()
                        ? parseIntSafe(cond.optString("day"), WeekMath.todayDayOfWeek())
                        : WeekMath.todayDayOfWeek());
        String periodLbl = cond == null ? "" : labelOfOption(cond, "periods", cond.optString("period"), cond.optString("periodLabel"));
        String roomFilter = cond == null ? "" : cond.optString("room").trim();

        TextView cap = text(11, G.MUTE);
        cap.setTypeface(Typeface.MONOSPACE);
        cap.setText(syncedAt > 0
                ? "QUERY · 实时查询 " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(syncedAt)) + "（不缓存）"
                : "QUERY · 实时查询（不缓存）");
        heroCard.addView(cap);

        TextView place = text(16, G.INK);
        place.setTypeface(Typeface.DEFAULT_BOLD);
        place.setPadding(0, dp(6), 0, dp(2));
        place.setText(campusLabel + (buildingLabel.isEmpty() ? "" : "  ·  " + buildingLabel));
        heroCard.addView(place);

        StringBuilder meta = new StringBuilder();
        meta.append("第 ").append(weekNum).append(" 周  ·  ").append(SdjtuStudyFormat.weekdayLabel(dayNum));
        meta.append("  ·  ").append(periodLbl.isEmpty() ? "全部节次" : periodLbl);
        if (!roomFilter.isEmpty()) meta.append("  ·  教室 ").append(roomFilter);
        TextView sub = text(12.5f, G.SUB);
        sub.setTypeface(Typeface.MONOSPACE);
        sub.setPadding(0, dp(2), 0, 0);
        sub.setText(meta.toString());
        heroCard.addView(sub);

        LinearLayout actions = new LinearLayout(a);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(14), 0, 0);

        TextView queryBtn = Ui.button(a, busy ? "查询中…" : "查询", true, v -> onQuery.run());
        queryBtn.setEnabled(!busy && !awaitingLogin);
        actions.addView(queryBtn, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView pickBtn = Ui.button(a, "修改条件", false, v -> onPick.run());
        pickBtn.setEnabled(!busy && !awaitingLogin);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, -2, 1f);
        plp.setMarginStart(dp(10));
        actions.addView(pickBtn, plp);
        heroCard.addView(actions);

        heroCard.setClickable(false);
        heroCard.setOnClickListener(null);
    }

    /** 渲染节次筛选胶囊条；返回校正后的 selectedPeriodFilter。 */
    String renderPeriodBar(LinearLayout periodBarBox, @Nullable JSONObject data,
                           String selectedPeriodFilter, java.util.function.Consumer<String> onSelect) {
        periodBarBox.removeAllViews();
        if (data == null) {
            periodBarBox.setVisibility(View.GONE);
            return selectedPeriodFilter;
        }
        JSONObject choices = data.optJSONObject("choices");
        JSONArray periods = choices == null ? null : choices.optJSONArray("periods");
        if (periods == null || periods.length() <= 1) {
            periodBarBox.setVisibility(View.GONE);
            return "";
        }
        periodBarBox.setVisibility(View.VISIBLE);
        addPeriodChip(periodBarBox, "", "全部时段", selectedPeriodFilter, onSelect);
        for (int i = 0; i < periods.length(); i++) {
            String p = periods.optString(i).trim();
            if (p.isEmpty()) continue;
            addPeriodChip(periodBarBox, p, SdjtuStudyFormat.shortPeriodLabel(p), selectedPeriodFilter, onSelect);
        }
        return selectedPeriodFilter;
    }

    private void addPeriodChip(LinearLayout periodBarBox, String value, String label,
                               String selectedPeriodFilter, java.util.function.Consumer<String> onSelect) {
        boolean selected = value.equals(selectedPeriodFilter);
        TextView tv = new TextView(a);
        tv.setText(label);
        tv.setTextSize(12.5f);
        tv.setTypeface(selected ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) : Typeface.MONOSPACE);
        tv.setTextColor(selected ? G.ON_INK : G.SUB);
        tv.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(G.GLASS ? 18 : 6));
        if (selected) {
            bg.setColor(G.INK);
        } else {
            bg.setColor(G.CARD);
            bg.setStroke(Math.max(1, dp(1)), G.CARD_STROKE);
        }
        tv.setBackground(bg);
        tv.setOnClickListener(v -> onSelect.accept(value));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(8));
        periodBarBox.addView(tv, lp);
    }

    void styleFreeOnlyButton(@Nullable TextView btn, boolean freeOnly) {
        if (btn == null) return;
        btn.setText(freeOnly ? "仅看空闲" : "全部教室");
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(G.GLASS ? 12 : 6));
        if (freeOnly) {
            bg.setColor(freeChipBg());
            btn.setTextColor(freeChipFg());
        } else {
            bg.setColor(G.CARD);
            bg.setStroke(Math.max(1, dp(1)), G.CARD_STROKE);
            btn.setTextColor(G.SUB);
        }
        btn.setBackground(bg);
    }

    RowsResult buildRows(@Nullable JSONObject data, String needle, String selectedPeriodFilter,
                         boolean freeOnly, int actualCurrentWeek, int todayDayOfWeek,
                         int nowMinutes, ScheduleSettings schedule) {
        List<SdjtuStudyActivity.Item> items = new ArrayList<>();
        JSONArray records = data == null ? null : data.optJSONArray("rooms");
        JSONObject cond = data == null ? null : data.optJSONObject("roomConditions");
        String buildingHint = labelOfOption(cond, "buildings",
                cond == null ? "" : cond.optString("building"),
                cond == null ? "" : cond.optString("buildingLabel"));

        int queriedWeek = parseIntSafe(cond == null ? "" : cond.optString("week"), 0);
        if (queriedWeek <= 0 && data != null && data.optJSONObject("query") != null) {
            queriedWeek = data.optJSONObject("query").optInt("week", 0);
        }
        int queriedDay = data != null && data.optJSONObject("query") != null
                ? data.optJSONObject("query").optInt("day", 0) : 0;
        if (queriedDay <= 0 && cond != null) {
            queriedDay = parseIntSafe(cond.optString("day"), 0);
        }
        boolean isQueryingToday = (queriedWeek <= 0 || queriedWeek == actualCurrentWeek)
                && (queriedDay <= 0 || queriedDay == todayDayOfWeek);

        List<RoomEntry> matched = new ArrayList<>();
        int totalRecords = records == null ? 0 : records.length();
        int totalFree = 0;
        int nowFreeCount = 0;
        int untilEndCount = 0;
        String untilEndTime = "";
        boolean fromNowActive = false;
        LinkedHashSet<String> distinctBuildings = new LinkedHashSet<>();

        for (int i = 0; i < totalRecords; i++) {
            JSONObject r = records.optJSONObject(i);
            if (r == null) continue;
            String rawName = r.optString("name");
            SdjtuStudyFormat.RoomLabel split = SdjtuStudyFormat.splitRoomLabel(rawName, buildingHint);
            String state = SdjtuStudyFormat.effectiveState(r, selectedPeriodFilter);
            if ("free".equals(state)) totalFree++;
            SdjtuStudyFormat.StudyRecommendation rec = SdjtuStudyFormat.evaluateStudy(
                    r, selectedPeriodFilter, isQueryingToday, nowMinutes, schedule);
            if (rec.fromNowMode) {
                fromNowActive = true;
                if (rec.streakSections > 0) {
                    nowFreeCount++;
                    if (rec.untilDayEnd) {
                        untilEndCount++;
                        if (untilEndTime.isEmpty()) untilEndTime = rec.untilTime;
                    }
                }
            }
            if (!needle.isEmpty() && !(rawName + " " + split.room + " " + split.building)
                    .toLowerCase(Locale.ROOT).contains(needle)) continue;
            if (freeOnly && !"free".equals(state)) continue;
            if (!split.building.isEmpty()) distinctBuildings.add(split.building);
            String badge = rec.badgeOverride.isEmpty()
                    ? SdjtuStudyFormat.roomBadge(r, selectedPeriodFilter) : rec.badgeOverride;
            matched.add(new RoomEntry(split.building, split.room, state, badge,
                    SdjtuStudyFormat.roomPeriods(r),
                    SdjtuStudyFormat.freePeriodCount(r), rec));
        }

        matched.sort((x, y) -> {
            boolean af = "free".equals(x.state), bf = "free".equals(y.state);
            if (af != bf) return af ? -1 : 1;
            if (x.rec.streakSections != y.rec.streakSections) {
                return Integer.compare(y.rec.streakSections, x.rec.streakSections);
            }
            if (x.rec.untilDayEnd != y.rec.untilDayEnd) {
                return x.rec.untilDayEnd ? -1 : 1;
            }
            if (selectedPeriodFilter.isEmpty() && x.rec.maxAnyStreakSections != y.rec.maxAnyStreakSections) {
                return Integer.compare(y.rec.maxAnyStreakSections, x.rec.maxAnyStreakSections);
            }
            if (selectedPeriodFilter.isEmpty() && x.freeCount != y.freeCount) {
                return Integer.compare(y.freeCount, x.freeCount);
            }
            return x.room.compareToIgnoreCase(y.room);
        });

        boolean showBuildingOnCard = distinctBuildings.size() > 1;
        for (RoomEntry e : matched) {
            items.add(SdjtuStudyActivity.Item.room(e, showBuildingOnCard));
        }

        String countText;
        if (data == null) {
            countText = "";
        } else if (totalRecords == 0) {
            countText = "本次教务查询返回 0 间教室";
        } else if (matched.isEmpty()) {
            countText = freeOnly
                    ? "当前筛选时段暂无空闲教室（共 " + totalRecords + " 间，可切「全部教室」查看）"
                    : "没有符合搜索条件的教室";
        } else if (fromNowActive && selectedPeriodFilter.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("显示 ").append(matched.size()).append(" 间 · 此刻起空闲 ")
                    .append(nowFreeCount).append("/").append(totalRecords).append(" 间");
            if (untilEndCount > 0 && !untilEndTime.isEmpty()) {
                sb.append("（").append(untilEndCount).append(" 间可连坐至 ").append(untilEndTime).append("）");
            }
            countText = sb.toString();
        } else {
            String scopeLbl = selectedPeriodFilter.isEmpty()
                    ? "当天有空闲" : SdjtuStudyFormat.shortPeriodLabel(selectedPeriodFilter) + "空闲";
            countText = "显示 " + matched.size() + " 间 · " + scopeLbl + " " + totalFree + "/" + totalRecords + " 间";
        }
        return new RowsResult(items, countText);
    }

    View bindRoomCard(SdjtuStudyActivity.Item item, String selectedPeriodFilter) {
        RoomEntry r = item.room;
        LinearLayout outer = new LinearLayout(a);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(0, 0, 0, dp(10));

        LinearLayout card = SdjtuStudyActivity.makeCard(a);
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout nameCol = new LinearLayout(a);
        nameCol.setOrientation(LinearLayout.VERTICAL);
        TextView roomTv = text(17.5f, G.INK);
        roomTv.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        roomTv.setText(r.room);
        nameCol.addView(roomTv);
        if (item.showBuilding && !r.building.isEmpty()) {
            TextView bldTv = text(11.5f, G.SUB);
            bldTv.setText(r.building);
            nameCol.addView(bldTv);
        }
        top.addView(nameCol, new LinearLayout.LayoutParams(0, -2, 1f));

        boolean badgeBusyNow = r.rec.fromNowMode && r.rec.streakSections == 0 && "free".equals(r.state);
        int badgeFg = badgeBusyNow ? G.SUB
                : "free".equals(r.state) ? freeChipFg() : "unknown".equals(r.state) ? warnChipFg() : G.MUTE;
        int badgeBgColor = badgeBusyNow ? G.CHIP
                : "free".equals(r.state) ? freeChipBg() : "unknown".equals(r.state) ? warnChipBg() : G.CHIP;

        TextView badge = text(11.5f, badgeFg);
        badge.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        badge.setPadding(dp(9), dp(4), dp(9), dp(4));
        GradientDrawable bbg = new GradientDrawable();
        bbg.setCornerRadius(dp(G.GLASS ? 12 : 4));
        bbg.setColor(badgeBgColor);
        badge.setBackground(bbg);
        badge.setText(r.badge);
        top.addView(badge);
        card.addView(top);

        if (!r.rec.hintText.isEmpty()) {
            int hintColor = r.rec.recommended ? freeChipFg() : (badgeBusyNow ? G.MUTE : G.SUB);
            TextView hintTv = text(12f, hintColor);
            if (r.rec.recommended) hintTv.setTypeface(Typeface.DEFAULT_BOLD);
            hintTv.setPadding(0, dp(5), 0, 0);
            hintTv.setText(r.rec.hintText);
            card.addView(hintTv);
        }

        if (!r.periods.isEmpty()) {
            LinearLayout strip = new LinearLayout(a);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setPadding(0, dp(10), 0, 0);
            for (int i = 0; i < r.periods.size(); i++) {
                SdjtuStudyFormat.PeriodSlot slot = r.periods.get(i);
                boolean isFree = "free".equals(slot.state);
                boolean isUnknown = "unknown".equals(slot.state);
                boolean focused = !selectedPeriodFilter.isEmpty() && selectedPeriodFilter.equals(slot.period);
                boolean isAnchor = selectedPeriodFilter.isEmpty() && r.rec.fromNowMode && i == r.rec.anchorSlotIndex;

                LinearLayout cell = new LinearLayout(a);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(dp(4), dp(6), dp(4), dp(6));

                GradientDrawable cbg = new GradientDrawable();
                cbg.setCornerRadius(dp(G.GLASS ? 10 : 4));
                cbg.setColor(isFree ? freeChipBg() : isUnknown ? warnChipBg() : G.CHIP);
                if (focused) cbg.setStroke(dp(1.5f), G.INK);
                else if (isAnchor) cbg.setStroke(Math.max(1, dp(1.25f)), isFree ? freeChipFg() : G.STROKE);
                cell.setBackground(cbg);

                TextView pLabel = text(10.5f, isFree ? freeChipFg() : isUnknown ? warnChipFg() : G.MUTE);
                pLabel.setTypeface(Typeface.MONOSPACE);
                pLabel.setSingleLine(true);
                pLabel.setText(slot.shortLabel.replace("节", ""));
                cell.addView(pLabel);

                TextView pState = text(11.5f, isFree ? freeChipFg() : isUnknown ? warnChipFg() : G.MUTE);
                pState.setTypeface(Typeface.DEFAULT_BOLD);
                pState.setText(isFree ? "空" : isUnknown ? "?" : "占");
                cell.addView(pState);

                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
                if (i > 0) clp.setMarginStart(dp(6));
                strip.addView(cell, clp);
            }
            card.addView(strip);
        }

        outer.addView(card);
        return outer;
    }

    static int freeChipBg() { return G.NIGHT ? 0xFF1E3324 : 0xFFE6F0E8; }
    static int freeChipFg() { return G.NIGHT ? 0xFF8CD19B : 0xFF2F5D3A; }
    static int warnChipBg() { return G.NIGHT ? 0xFF332619 : 0xFFF3EAE0; }
    static int warnChipFg() { return G.NIGHT ? 0xFFE8A464 : 0xFFB4540A; }

    static String labelOfOption(@Nullable JSONObject cond, String arrayKey, String value, String fallback) {
        if (cond != null && value != null && !value.isEmpty()) {
            JSONArray arr = cond.optJSONArray(arrayKey);
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && value.equals(o.optString("value"))) {
                    String lbl = o.optString("label").trim();
                    if (!lbl.isEmpty()) return lbl;
                }
            }
        }
        return fallback == null ? "" : fallback;
    }

    private static int parseIntSafe(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(a);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(float n) { return Math.round(n * a.getResources().getDisplayMetrics().density); }
}
