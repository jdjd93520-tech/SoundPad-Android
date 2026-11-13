package com.alxt.SoundPad;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootRestoreReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!context.getSharedPreferences("safe_display_restore", Context.MODE_PRIVATE).getBoolean("active", false)) return;
        Intent restore = new Intent(context, DisplayRestoreService.class).setAction(DisplayRestoreService.ACTION_RESTORE);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(restore);
            else context.startService(restore);
        } catch (Exception ignored) {
            // Original settings remain persisted; opening the app and pressing RESET retries restoration.
        }
    }
}