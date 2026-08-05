package ru.vlad.diary;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/** Планирование напоминаний через системный AlarmManager. */
public class Reminders {

    public static final String CHANNEL = "reminders";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_DAY = "day";
    public static final String EXTRA_UID = "uid";

    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Напоминания",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Напоминания о запланированных делах");
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
    }

    private static PendingIntent intentFor(Context ctx, String uid, String text, String dayKey) {
        Intent i = new Intent(ctx, ReminderReceiver.class);
        i.putExtra(EXTRA_UID, uid);
        i.putExtra(EXTRA_TEXT, text);
        i.putExtra(EXTRA_DAY, dayKey);
        i.setData(android.net.Uri.parse("diary://" + uid));
        return PendingIntent.getBroadcast(ctx, uid.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** true, если удалось поставить точный будильник. */
    static boolean schedule(Context ctx, String uid, String text, String dayKey, long at) {
        ensureChannel(ctx);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null || at <= System.currentTimeMillis()) {
            return false;
        }
        PendingIntent pi = intentFor(ctx, uid, text, dayKey);
        boolean exact = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            exact = false;
        }
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
        return exact;
    }

    static void cancel(Context ctx, String uid) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(intentFor(ctx, uid, "", ""));
        }
    }

    /** Перерегистрирует все будущие напоминания (после перезагрузки телефона). */
    static void rescheduleAll(Context ctx) {
        Map<String, ?> all = ctx.getSharedPreferences("diary", Context.MODE_PRIVATE).getAll();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String key = e.getKey();
            if (key.length() != 10 || !(e.getValue() instanceof String)) {
                continue;
            }
            try {
                JSONArray arr = new JSONArray((String) e.getValue());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    long at = o.optLong("r", 0);
                    String uid = o.optString("i", "");
                    if (at > now && !uid.isEmpty() && !o.optBoolean("d", false)) {
                        schedule(ctx, uid, o.optString("t"), key, at);
                    }
                }
            } catch (Exception ignored) {
                // повреждённый день пропускаем
            }
        }
    }

    static void notifyNow(Context ctx, String uid, String text, String dayKey) {
        ensureChannel(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }

        Intent open = new Intent(ctx, MainActivity.class);
        open.putExtra(EXTRA_DAY, dayKey);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, uid.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(ctx, CHANNEL);
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Ежедневник")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setDefaults(Notification.DEFAULT_ALL);

        nm.notify(uid.hashCode(), b.build());
    }
}
