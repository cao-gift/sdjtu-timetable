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

import com.ecut.campus.data.CourseTodo;

/**
 * 作业截止提醒（截止前一天 20:00 与截止当天 08:30）：
 * 每次只排最近一个闹钟，触发后发通知并排下一个。
 */
public final class TodoReminder {

    static final String CH = "todo_remind";
    static final int NOTIF_ID = 2104;
    private static final int REQ = 64;
    private static final String EX_COURSE = "course";
    private static final String EX_TITLE = "title";
    private static final String EX_TEXT = "text";

    private TodoReminder() {}

    public static String upcomingWidgetHint(Context c, long now) {
        if (c == null) return "";
        return CourseTodoStore.load(c).urgentWidgetHint(now, 2);
    }

    public static void reschedule(Context c) {
        if (c == null) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pending(c, null));
        long now = System.currentTimeMillis();
        CourseTodo.DeadlineTrigger next = CourseTodoStore.load(c).nextDeadlineReminder(now);
        if (next == null) return;
        Intent i = new Intent(c, Receiver.class);
        i.putExtra(EX_COURSE, next.item.courseName);
        i.putExtra(EX_TITLE, next.leadLabel + "：" + next.item.title);
        StringBuilder body = new StringBuilder();
        if (!next.item.courseName.isEmpty()) body.append(next.item.courseName);
        if (!next.item.dueDate.isEmpty()) {
            if (body.length() > 0) body.append(" · ");
            body.append("截止 ").append(next.item.dueDate);
        }
        if (!next.item.note.isEmpty()) {
            if (body.length() > 0) body.append("\n");
            body.append(next.item.note);
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
        if (!ClassReminder.canNotify(c)) return;
        String title = data.getStringExtra(EX_TITLE);
        if (title == null || title.isEmpty()) return;
        String text = data.getStringExtra(EX_TEXT);
        String course = data.getStringExtra(EX_COURSE);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CH, "作业截止提醒", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("作业截止前一天晚上与当天早晨提醒");
            nm.createNotificationChannel(ch);
        }
        Intent open = (course != null && !course.isEmpty())
                ? CourseTodoActivity.intent(c, course).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                : new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 12, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String safe = text == null || text.isEmpty() ? "请及时完成作业" : text;
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_nav_timetable)
                .setContentTitle(title)
                .setContentText(safe.replace('\n', ' '))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(safe))
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
            ClassWidget.updateAll(c);
        }
    }
}
