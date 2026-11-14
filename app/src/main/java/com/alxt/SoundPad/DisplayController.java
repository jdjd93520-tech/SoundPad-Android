package com.alxt.SoundPad;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rikka.shizuku.Shizuku;

final class DisplayController {
    private static final String PREFS = "display_safety";
    private static final String KEY_ACTIVE = "restore_pending";
    private static final String KEY_SIZE = "original_size";
    private static final String KEY_DENSITY = "original_density";
    private static final String KEY_SCALING = "original_scaling";

    private DisplayController() {}

    static synchronized void applyMode(Context context, String mode) throws Exception {
        requirePermission();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ACTIVE, false)) {
            String size = readSizeOverride(run("wm size"));
            String density = readDensityOverride(run("wm density"));
            String scaling = readScaling(run("wm scaling"));
            boolean saved = prefs.edit().putString(KEY_SIZE, size).putString(KEY_DENSITY, density)
                    .putString(KEY_SCALING, scaling).putBoolean(KEY_ACTIVE, true).commit();
            if (!saved) throw new IllegalStateException("Could not save the original display settings.");
        }
        // Arm recovery before changing the display, so a failed or interrupted apply still has a restore path.
        RestoreScheduler.schedule(context);

        String requested = "center".equals(mode) ? "off" : "auto";
        run("wm scaling " + requested);
        String actual = readScaling(run("wm scaling"));
        if (!requested.equals(actual)) {
            throw new IllegalStateException("Android did not confirm scaling mode '" + requested + "'. Reported: " + actual);
        }
    }

    static synchronized void restoreOriginal(Context context) throws Exception {
        requirePermission();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String size = prefs.getString(KEY_SIZE, null);
        String density = prefs.getString(KEY_DENSITY, null);
        String scaling = prefs.getString(KEY_SCALING, null);
        if (size == null || density == null || scaling == null) {
            throw new IllegalStateException("No saved original display settings were found.");
        }

        restoreSize(size);
        restoreDensity(density);
        run("wm scaling " + scaling);

        String actualSize = readSizeOverride(run("wm size"));
        String actualDensity = readDensityOverride(run("wm density"));
        String actualScaling = readScaling(run("wm scaling"));
        if (!size.equals(actualSize) || !density.equals(actualDensity) || !scaling.equals(actualScaling)) {
            throw new IllegalStateException("Restore verification failed. Current: size=" + actualSize
                    + ", density=" + actualDensity + ", scaling=" + actualScaling);
        }
        prefs.edit().putBoolean(KEY_ACTIVE, false).remove("last_restore_error").commit();
        RestoreScheduler.cancel(context);
    }

    static String readDisplaySummary() throws Exception {
        String sizeOut = run("wm size");
        String densityOut = run("wm density");
        String scalingOut = run("wm scaling");
        return "Resolution: " + readCurrentSize(sizeOut) + "\nDensity: "
                + readCurrentDensity(densityOut) + " dpi\nScaling: " + readScaling(scalingOut);
    }

    static boolean isRestorePending(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false);
    }

    private static void requirePermission() {
        if (!Shizuku.pingBinder()) throw new IllegalStateException("Shizuku is not running.");
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Shizuku permission has not been granted.");
        }
    }

    private static String run(String command) throws Exception {
        requirePermission();
        Process process = Shizuku.newProcess(new String[]{"sh", "-c", command + " 2>&1"}, null, null);
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
        }
        int code = process.waitFor();
        String result = output.toString().trim();
        if (code != 0) throw new IllegalStateException(command + " failed: " + result);
        return result;
    }

    private static void restoreSize(String value) throws Exception {
        if ("reset".equals(value)) run("wm size reset");
        else if (value.matches("\\d+x\\d+")) run("wm size " + value);
        else throw new IllegalStateException("Saved display size is invalid: " + value);
    }

    private static void restoreDensity(String value) throws Exception {
        if ("reset".equals(value)) run("wm density reset");
        else if (value.matches("\\d+")) run("wm density " + value);
        else throw new IllegalStateException("Saved display density is invalid: " + value);
    }

    private static String readSizeOverride(String output) {
        Matcher m = Pattern.compile("(?m)^Override size: (\\d+x\\d+)\\s*$").matcher(output);
        return m.find() ? m.group(1) : "reset";
    }

    private static String readDensityOverride(String output) {
        Matcher m = Pattern.compile("(?m)^Override density: (\\d+)\\s*$").matcher(output);
        return m.find() ? m.group(1) : "reset";
    }

    private static String readCurrentSize(String output) {
        Matcher m = Pattern.compile("(?m)^(?:Override|Physical) size: (\\d+x\\d+)\\s*$").matcher(output);
        String found = "";
        while (m.find()) found = m.group(1);
        return found.isEmpty() ? output : found;
    }

    private static String readCurrentDensity(String output) {
        Matcher m = Pattern.compile("(?m)^(?:Override|Physical) density: (\\d+)\\s*$").matcher(output);
        String found = "";
        while (m.find()) found = m.group(1);
        return found.isEmpty() ? output : found;
    }

    private static String readScaling(String output) {
        String lower = output.toLowerCase();
        if (lower.contains("off")) return "off";
        if (lower.contains("auto")) return "auto";
        throw new IllegalStateException("Could not understand Android scaling status: " + output);
    }
}
