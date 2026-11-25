package com.ecut.campus;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.ecut.campus.data.ExamCountdown;

import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

/**
 * 考前提醒（考前一天 + 考前一小时各提醒一次）：
 * 读取本机已同步的考试安排，每次只排最近一个闹钟，触发后立即排下一个。
 */
public final class ExamReminder {

    static final String CH = "exam_remind";
    static final int NOTIF_ID = 2103;
    private static final int REQ = 63;
    private static final String EX_TITLE = "title";
    private static final String EX_TEXT = "text";

    private ExamReminder() {}

    public static List<ExamCountdown.Entry> loadEntries(Context c) {
        if (c == null) return Collections.emptyList();
        JSONObject snap = SdjtuStudyStore.load(c, SdjtuAcademicPages.Feature.EXAMS);
        if (snap == null) return Collections.emptyList();
        return ExamCountdown.parseAll(snap.optJSONObject("data"));
    }

    public static String upcomingBanner(Context c, long now) {
        return ExamCountdown.upcomingBanner(loadEntries(c), now, 7);
    }

    public static void reschedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pending(c, null));
        if (!Prefs.examRemind(c)) return;
        long now = System.currentTimeMillis();
        ExamCountdown.ReminderTrigger next = ExamCountdown.nextReminder(loadEntries(c), now);
        if (next == null) return;
        Intent i = new Intent(c, Receiver.class);
        i.putExtra(EX_TITLE, next.leadLabel + "：" + next.entry.name);
        StringBuilder body = new StringBuilder();
        if (!next.entry.timeRaw.isEmpty()) body.append(next.entry.timeRaw);
        if (!next.entry.room.isEmpty()) {
            if (body.length() > 0) body.append(" · ");
            body.append("考场 ").append(next.entry.room);
        }
        if (!next.entry.seat.isEmpty()) {
            if (body.length() > 0) body.append(" · ");
            body.append("座位 ").append(next.entry.seat);
        }
        i.putExtra(EX_TEXT, body.toString());
        long fire = Math.max(now + 2_000L, next.fireAtMillis);
        PendingIntent pi = pending(c, i);
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire, pi);
        else am.set(AlarmManager.RTC_WAKEUP, fire, pi);
    }

    private static PendingIntent pending(Context c, Intent extras) {
        Intent i = extras != null ? extras : new Intent(c, Receiver.class);
        return PendingIntent.getBroadcast(c, REQ, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void post(Context c, Intent data) {
        if (!Prefs.examRemind(c) || !ClassReminder.canNotify(c)) return;
        String title = data.getStringExtra(EX_TITLE);
        if (title == null || title.isEmpty()) return;
        String text = data.getStringExtra(EX_TEXT);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CH, "考试提醒", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("考前一天与考前一小时提醒考试时间与考场");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(c, SdjtuExamActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 11, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_nav_timetable)
                .setContentTitle(title)
                .setContentText(text == null || text.isEmpty() ? "请提前核对准考证与考场" : text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setColor(0xFFFF4A1C)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(c).notify(NOTIF_ID, b.build());
        } catch (SecurityException ignored) {
        }
    }

    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (intent.getAction() == null) post(c, intent);
            reschedule(c);
        }
    }
}
