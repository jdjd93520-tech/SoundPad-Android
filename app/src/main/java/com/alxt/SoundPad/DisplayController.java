package com.alxt.SoundPad;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
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
            String scaling = readScaling(run("settings get global display_scaling_force"));
            boolean saved = prefs.edit().putString(KEY_SIZE, size).putString(KEY_DENSITY, density)
                    .putString(KEY_SCALING, scaling).putBoolean(KEY_ACTIVE, true).commit();
            if (!saved) throw new IllegalStateException("Could not save the original display settings.");
        }
        RestoreScheduler.schedule(context);

        if ("center".equals(mode)) {
            // Android has no universal command for GPU-style centered, unscaled output.
            // The safe native fallback is to return to the user's saved display configuration.
            restoreSavedConfiguration(prefs);
            return;
        }

        String physical = readPhysicalSize(run("wm size"));
        String target = landscapeSixteenNineLogicalSize(physical);
        run("wm size " + target);
        String actual = readSizeOverride(run("wm size"));
        if (!target.equals(actual)) {
            throw new IllegalStateException("Android did not confirm the 16:9 logical resolution. Requested: "
                    + target + ", reported: " + actual);
        }
    }

    static synchronized void restoreOriginal(Context context) throws Exception {
        requirePermission();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ACTIVE, false)) {
            throw new IllegalStateException("No saved original display settings were found.");
        }
        restoreSavedConfiguration(prefs);
        prefs.edit().putBoolean(KEY_ACTIVE, false).remove("last_restore_error").commit();
        RestoreScheduler.cancel(context);
    }

    private static void restoreSavedConfiguration(SharedPreferences prefs) throws Exception {
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
        String actualScaling = readScaling(run("settings get global display_scaling_force"));
        if (!size.equals(actualSize) || !density.equals(actualDensity) || !scaling.equals(actualScaling)) {
            throw new IllegalStateException("Restore verification failed. Current: size=" + actualSize
                    + ", density=" + actualDensity + ", scaling=" + actualScaling);
        }
    }

    static String readDisplaySummary() throws Exception {
        String sizeOut = run("wm size");
        String densityOut = run("wm density");
        String scalingOut = run("settings get global display_scaling_force");
        return "Resolution: " + readCurrentSize(sizeOut) + "\nDensity: "
                + readCurrentDensity(densityOut) + " dpi\nScaling policy: " + readScaling(scalingOut);
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

    private static Process createShellProcess(String[] command) throws Exception {
        Method method = Shizuku.class.getDeclaredMethod("newProcess",
                String[].class, String[].class, String.class);
        method.setAccessible(true);
        try {
            return (Process) method.invoke(null, new Object[]{command, null, null});
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("Shizuku could not start the shell process.", cause);
        }
    }

    private static String run(String command) throws Exception {
        requirePermission();
        Process process = createShellProcess(new String[]{"sh", "-c", command + " 2>&1"});
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

    private static String landscapeSixteenNineLogicalSize(String physicalSize) {
        Matcher matcher = Pattern.compile("^(\\d+)x(\\d+)$").matcher(physicalSize);
        if (!matcher.matches()) throw new IllegalStateException("Could not read physical display resolution: " + physicalSize);
        int width = Integer.parseInt(matcher.group(1));
        int height = Integer.parseInt(matcher.group(2));
        int shortSide = Math.min(width, height);
        int longSide = ((shortSide * 16 / 9) + 1) & ~1;
        return width <= height ? shortSide + "x" + longSide : longSide + "x" + shortSide;
    }

    private static String readPhysicalSize(String output) {
        Matcher m = Pattern.compile("(?m)^Physical size: (\\d+x\\d+)\\s*$").matcher(output);
        if (!m.find()) throw new IllegalStateException("Android did not report the panel's physical resolution: " + output);
        return m.group(1);
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
        String value = output.trim();
        if ("0".equals(value) || "null".equalsIgnoreCase(value) || value.isEmpty()) return "auto";
        if ("1".equals(value)) return "off";
        throw new IllegalStateException("Could not understand Android display scaling setting: " + value);
    }
}