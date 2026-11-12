package com.alxt.SoundPad;

import android.content.Context;
import android.os.Build;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Minimal ActivityThread bootstrap used by scrcpy's shell-side AudioPolicy implementation. */
final class SystemContextWorkaround {
    private SystemContextWorkaround() {}

    static Context create() throws Exception {
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Constructor<?> constructor = threadClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object thread = constructor.newInstance();

        Field current = threadClass.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, thread);

        Field systemThread = threadClass.getDeclaredField("mSystemThread");
        systemThread.setAccessible(true);
        systemThread.setBoolean(thread, true);

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                Class<?> controllerClass = Class.forName("android.app.ConfigurationController");
                Class<?> internalClass = Class.forName("android.app.ActivityThreadInternal");
                Constructor<?> cc = controllerClass.getDeclaredConstructor(internalClass);
                cc.setAccessible(true);
                Object controller = cc.newInstance(thread);
                Field f = threadClass.getDeclaredField("mConfigurationController");
                f.setAccessible(true);
                f.set(thread, controller);
            } catch (Throwable ignored) {
                // Some vendor builds do not expose this implementation detail.
            }
        }

        Method getSystemContext = threadClass.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        Context context = (Context) getSystemContext.invoke(thread);
        if (context == null) throw new IllegalStateException("Android system context unavailable");
        return context;
    }
}