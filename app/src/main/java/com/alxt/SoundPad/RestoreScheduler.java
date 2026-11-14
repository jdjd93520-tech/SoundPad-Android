package com.alxt.SoundPad;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

final class RestoreScheduler {
    private static final int REQUEST_CODE = 8107;
    private RestoreScheduler() {}

    static void schedule(Context context) {
        schedule(context, 10 * 60 * 1000L);
    }

    static void schedule(Context context, long delayMillis) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) throw new IllegalStateException("Android alarm service is unavailable.");
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMillis, pendingIntent(context));
    }

    static void cancel(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(pendingIntent(context));
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, RestoreReceiver.class);
        intent.setAction(RestoreReceiver.ACTION_TIMEOUT);
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}