package ru.vlad.diary;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Срабатывает по будильнику и показывает уведомление. */
public class ReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String uid = intent.getStringExtra(Reminders.EXTRA_UID);
        String text = intent.getStringExtra(Reminders.EXTRA_TEXT);
        String dayKey = intent.getStringExtra(Reminders.EXTRA_DAY);
        if (uid == null || uid.isEmpty()) {
            return;
        }
        if (text == null || text.isEmpty()) {
            text = "Запланированное дело";
        }
        Reminders.notifyNow(context, uid, text, dayKey == null ? "" : dayKey);
    }
}
