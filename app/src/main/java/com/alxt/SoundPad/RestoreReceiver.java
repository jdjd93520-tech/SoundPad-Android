package com.alxt.SoundPad;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import rikka.shizuku.Shizuku;

public class RestoreReceiver extends BroadcastReceiver {
    static final String ACTION_TIMEOUT = "com.jdjd.displaylauncher.ACTION_TIMEOUT";
    private static final String TAG = "DisplayLauncher";

    @Override public void onReceive(Context context, Intent intent) {
        if (!DisplayController.isRestorePending(context)) return;
        if (!Shizuku.pingBinder()) {
            Log.e(TAG, "Restore is pending, but Shizuku is unavailable. Start Shizuku and use manual restore.");
            context.getSharedPreferences("display_safety", Context.MODE_PRIVATE)
                    .edit().putString("last_restore_error", "Shizuku was unavailable during automatic restore.").apply();
            return;
        }
        try {
            DisplayController.restoreOriginal(context);
            Log.i(TAG, "Original display settings restored after " + intent.getAction());
        } catch (Exception e) {
            Log.e(TAG, "Automatic restore failed after " + intent.getAction(), e);
            context.getSharedPreferences("display_safety", Context.MODE_PRIVATE)
                    .edit().putString("last_restore_error", String.valueOf(e.getMessage())).apply();
        }
    }
}
