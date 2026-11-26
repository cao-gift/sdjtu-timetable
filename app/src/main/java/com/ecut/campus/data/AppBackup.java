package com.ecut.campus.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 全量本地备份格式（纯 Java，无 Android 依赖）：
 * 打包课表归档、作息、显示与隐藏词组设置、同学分享码、作业待办与小玩意偏好；
 * 严禁包含门户账号密码、Cookie 或会话令牌。
 */
public final class AppBackup {

    public static final int SCHEMA_VERSION = 1;
    public static final String KIND = "lvlv_full_backup";

    private AppBackup() {}

    public static JSONObject build(String appVersion,
                                   String exportedAt,
                                   JSONObject timetableArchive,
                                   JSONObject schedule,
                                   JSONObject prefs,
                                   JSONArray friends,
                                   JSONObject todos,
                                   JSONObject fun) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("kind", KIND);
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("appVersion", appVersion == null ? "" : appVersion);
        root.put("exportedAt", exportedAt == null ? "" : exportedAt);
        if (timetableArchive != null) root.put("timetableArchive", timetableArchive);
        if (schedule != null) root.put("schedule", schedule);
        if (prefs != null) root.put("prefs", prefs);
        if (friends != null) root.put("friends", friends);
        if (todos != null) root.put("todos", todos);
        if (fun != null) root.put("fun", fun);
        return root;
    }

    /** 校验备份 JSON 合法性；不合法或版本过高时抛出 JSONException。 */
    public static JSONObject parseAndValidate(String json) throws JSONException {
        if (json == null || json.trim().isEmpty()) {
            throw new JSONException("备份文件为空");
        }
        JSONObject root = new JSONObject(json);
        if (!KIND.equals(root.optString("kind"))) {
            throw new JSONException("这不是驴驴课表的全量备份文件");
        }
        int ver = root.optInt("schemaVersion", 0);
        if (ver <= 0) throw new JSONException("备份文件缺少版本号");
        if (ver > SCHEMA_VERSION) {
            throw new JSONException("备份版本 " + ver + " 高于当前支持版本 " + SCHEMA_VERSION + "，请先升级 App");
        }
        // 安全防线：忽略任何意外出现的敏感字段
        root.remove("credentials");
        root.remove("password");
        root.remove("cookies");
        return root;
    }

    /** 生成恢复前确认摘要。 */
    public static String summarize(JSONObject root) {
        if (root == null) return "";
        int semesters = 0, courses = 0;
        JSONObject arch = root.optJSONObject("timetableArchive");
        if (arch != null) {
            JSONArray tts = arch.optJSONArray("timetables");
            if (tts != null) {
                semesters = tts.length();
                for (int i = 0; i < tts.length(); i++) {
                    JSONObject t = tts.optJSONObject(i);
                    JSONArray cs = t == null ? null : t.optJSONArray("courses");
                    if (cs != null) courses += cs.length();
                }
            }
        }
        JSONArray friends = root.optJSONArray("friends");
        int friendCount = friends == null ? 0 : friends.length();
        JSONObject todos = root.optJSONObject("todos");
        JSONArray todoArr = todos == null ? null : todos.optJSONArray("items");
        int todoCount = todoArr == null ? 0 : todoArr.length();

        StringBuilder sb = new StringBuilder();
        sb.append("导出版本：v").append(root.optString("appVersion", "?"));
        String at = root.optString("exportedAt", "");
        if (!at.isEmpty()) sb.append("\n导出时间：").append(at);
        sb.append("\n包含内容：");
        sb.append(semesters).append(" 个学期（共 ").append(courses).append(" 门课）");
        sb.append("、").append(friendCount).append(" 位同学");
        sb.append("、").append(todoCount).append(" 条作业待办");
        sb.append("，以及上课时间、显示偏好与地点隐藏词组。");
        sb.append("\n\n不会包含门户账号密码；恢复后将覆盖当前本机课表与设置。");
        return sb.toString();
    }
}
