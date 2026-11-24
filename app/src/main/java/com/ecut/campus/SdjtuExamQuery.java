package com.ecut.campus;

import android.app.Activity;
import android.view.ViewGroup;

import org.json.JSONException;
import org.json.JSONObject;

/** 复用本人统一门户会话，顺序查询同一学期的两份学校表单，一次回调交付合并结果。 */
final class SdjtuExamQuery {
    interface Callback {
        void onSuccess(JSONObject data);
        void onPartial(JSONObject data, String message, boolean loginRequired);
        void onFailure(String message, boolean loginRequired);
    }

    private final Activity activity;
    private final ViewGroup host;
    private final Callback callback;
    private SdjtuStudySync running;
    private JSONObject first;
    private String term = "";
    private String firstFailure;
    private boolean finished;

    private SdjtuExamQuery(Activity activity, ViewGroup host, Callback callback) {
        this.activity = activity;
        this.host = host;
        this.callback = callback;
    }

    static SdjtuExamQuery start(Activity activity, ViewGroup host, Callback callback) {
        SdjtuExamQuery query = new SdjtuExamQuery(activity, host, callback);
        query.read(false);
        return query;
    }

    private void read(boolean early) {
        JSONObject request = new JSONObject();
        try {
            request.put("examKind", early ? "early" : "final");
            if (!term.isEmpty()) request.put("term", term);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        running = SdjtuStudySync.start(activity, host, SdjtuAcademicPages.Feature.EXAMS, request,
                new SdjtuStudySync.Callback() {
            @Override public void onSuccess(JSONObject data) {
                if (finished) return;
                if (!early) {
                    first = data;
                    term = data.optString("term");
                    read(true);
                    return;
                }
                complete(data, firstFailure, false);
            }

            @Override public void onChoose(String reason, JSONObject options) {
                onFailure(reason, false);
            }

            @Override public void onFailure(String message, boolean loginRequired) {
                if (finished) return;
                if (!early && !loginRequired) {
                    firstFailure = message;
                    term = running.submittedTerm();
                    read(true);
                } else {
                    complete(null, message, loginRequired);
                }
            }
        });
    }

    private void complete(JSONObject second, String reason, boolean loginRequired) {
        finished = true;
        running = null;
        // A returned row set for a different semester cannot complete this refresh.
        if (second != null && !term.isEmpty() && !term.equals(second.optString("term"))) {
            second = null;
            reason = "教务返回的学期与本次查询不一致。";
        }
        if (first == null && second == null) {
            callback.onFailure(reason == null ? "未能取得考试安排，请重试。" : reason, loginRequired);
            return;
        }
        JSONObject merged;
        try {
            merged = SdjtuExamSchedule.merge(first, second);
        } catch (IllegalArgumentException e) {
            callback.onFailure("教务返回的考试安排无法识别，请重试。", loginRequired);
            return;
        }
        if (first != null && second != null) callback.onSuccess(merged);
        else callback.onPartial(merged, "考试安排未全部取得。" + (reason == null ? "" : reason), loginRequired);
    }

    void cancel() {
        finished = true;
        if (running != null) running.cancel();
        running = null;
    }
}
