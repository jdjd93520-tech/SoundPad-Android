package com.alxt.SoundPad;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import rikka.shizuku.Shizuku;

public class DisplayRecoveryReceiver extends BroadcastReceiver {
    static final String ACTION_TIMEOUT = "com.alxt.SoundPad.DISPLAY_RESTORE_TIMEOUT";

    @Override public void onReceive(Context context, Intent intent) {
        SharedPreferences p = context.getSharedPreferences(AppLauncherActivity.PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(AppLauncherActivity.KEY_ACTIVE, false)
                && !p.getBoolean(AppLauncherActivity.KEY_PENDING_RESTORE, false)) return;

        if (ACTION_TIMEOUT.equals(intent.getAction())) {
            long deadline = p.getLong(AppLauncherActivity.KEY_DEADLINE, 0L);
            if (deadline > 0L && System.currentTimeMillis() < deadline) {
                AppLauncherActivity.scheduleRestore(context, deadline);
                return;
            }
        }
        // A reboot or timeout is a restore event. If Shizuku is not ready yet,
        // persist the recovery flag so the next app launch can finish it.
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            p.edit().putBoolean(AppLauncherActivity.KEY_PENDING_RESTORE, true).apply();
            return;
        }
        try {
            AppLauncherActivity.restoreWithShizuku(context);
        } catch (Exception e) {
            p.edit().putBoolean(AppLauncherActivity.KEY_PENDING_RESTORE, true).apply();
        }
    }
}
