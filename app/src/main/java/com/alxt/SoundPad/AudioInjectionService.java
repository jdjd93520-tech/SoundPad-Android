package com.alxt.SoundPad;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.Os;
import android.util.Log;

import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.ShizukuProvider;

public final class AudioInjectionService extends IAudioInjectionService.Stub {
    private static final String TAG = "SoundPadInjection";
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ParcelFileDescriptor inputPipe;
    private volatile AudioTrack track;
    private volatile AudioManager audioManager;
    private volatile Object audioPolicy;
    private volatile Thread worker;
    private volatile String status = "Shizuku service ready";

    public AudioInjectionService() {
        status = "Shizuku service ready (uid=" + Os.getuid() + ")";
    }

    @Override
    public synchronized String startInjection(ParcelFileDescriptor pipe) throws RemoteException {
        if (Build.VERSION.SDK_INT < 33) {
            closeQuietly(pipe);
            return "Microphone injection requires Android 13 or newer.";
        }
        if (pipe == null) return "No audio pipe was supplied.";
        stopInjection();
        try {
            Context context = SystemContextWorkaround.create();
            Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
            Object ruleBuilder = ruleBuilderClass.getDeclaredConstructor().newInstance();
            try {
                Method role = ruleBuilderClass.getDeclaredMethod("setTargetMixRole", int.class);
                role.invoke(ruleBuilder, 1); // MIX_ROLE_INJECTOR
            } catch (Throwable ignored) {}

            Method addRule = ruleBuilderClass.getDeclaredMethod("addMixRule", int.class, Object.class);
            int matchCapturePreset = 1 << 1;
            int[] presets = {
                MediaRecorder.AudioSource.DEFAULT,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.VOICE_PERFORMANCE
            };
            for (int preset : presets) {
                AudioAttributes.Builder attrs = new AudioAttributes.Builder();
                Method setPreset = attrs.getClass().getDeclaredMethod("setCapturePreset", int.class);
                setPreset.invoke(attrs, preset);
                addRule.invoke(ruleBuilder, matchCapturePreset, attrs.build());
            }

            Object rule = ruleBuilderClass.getDeclaredMethod("build").invoke(ruleBuilder);
            Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
            Constructor<?> mixConstructor = mixBuilderClass.getDeclaredConstructor(rule.getClass());
            Object mixBuilder = mixConstructor.newInstance(rule);
            AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
            mixBuilderClass.getDeclaredMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format);
            mixBuilderClass.getDeclaredMethod("setRouteFlags", int.class).invoke(mixBuilder, 1 << 1);
            Object mix = mixBuilderClass.getDeclaredMethod("build").invoke(mixBuilder);

            Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
            Constructor<?> policyConstructor = policyBuilderClass.getDeclaredConstructor(Context.class);
            Object policyBuilder = policyConstructor.newInstance(context);
            policyBuilderClass.getDeclaredMethod("addMix", mix.getClass()).invoke(policyBuilder, mix);
            Object policy = policyBuilderClass.getDeclaredMethod("build").invoke(policyBuilder);

            AudioManager manager = context.getSystemService(AudioManager.class);
            if (manager == null) throw new IllegalStateException("AudioManager unavailable");
            Method register = manager.getClass().getDeclaredMethod("registerAudioPolicy", policy.getClass());
            int result = (Integer) register.invoke(manager, policy);
            if (result != 0) throw new IllegalStateException("registerAudioPolicy returned " + result);
            this.audioManager = manager;
            this.audioPolicy = policy;

            Method createTrack = policy.getClass().getDeclaredMethod("createAudioTrackSource", mix.getClass());
            AudioTrack audioTrack = (AudioTrack) createTrack.invoke(policy, mix);
            if (audioTrack == null) throw new IllegalStateException("AudioPolicy did not create an AudioTrack");
            audioTrack.play();

            this.inputPipe = pipe;
            this.track = audioTrack;
            this.audioManager = manager;
            this.audioPolicy = policy;
            running.set(true);
            worker = new Thread(() -> pump(pipe, audioTrack, manager, policy), "SoundPad-mic-injector");
            worker.start();
            status = "Injection active (shell uid=" + Os.getuid() + ")";
            return "OK: AudioPolicy injection started. Start internal-audio recording to feed it.";
        } catch (Throwable error) {
            closeQuietly(pipe);
            AudioManager manager = audioManager;
            Object policy = audioPolicy;
            audioManager = null;
            audioPolicy = null;
            if (manager != null && policy != null) unregister(manager, policy);
            AudioTrack failedTrack = track;
            track = null;
            if (failedTrack != null) {
                try { failedTrack.release(); } catch (Throwable ignored) {}
            }
            status = "Injection setup failed: " + rootMessage(error);
            Log.e(TAG, status, error);
            return status;
        }
    }

    private void pump(ParcelFileDescriptor pipe, AudioTrack audioTrack, AudioManager manager, Object policy) {
        byte[] buffer = new byte[4096];
        try (FileInputStream in = new FileInputStream(pipe.getFileDescriptor())) {
            while (running.get()) {
                int count = in.read(buffer);
                if (count < 0) break;
                if (count == 0) continue;
                int offset = 0;
                while (running.get() && offset < count) {
                    int written = audioTrack.write(buffer, offset, count - offset);
                    if (written < 0) throw new IllegalStateException("AudioTrack.write returned " + written);
                    if (written == 0) continue;
                    offset += written;
                }
            }
            status = "Injection stream ended";
        } catch (Throwable error) {
            status = "Injection stopped: " + rootMessage(error);
            Log.e(TAG, status, error);
        } finally {
            running.set(false);
            closeQuietly(pipe);
            try { audioTrack.stop(); } catch (Throwable ignored) {}
            try { audioTrack.release(); } catch (Throwable ignored) {}
            unregister(manager, policy);
            if (inputPipe == pipe) inputPipe = null;
            if (track == audioTrack) track = null;
            if (audioPolicy == policy) audioPolicy = null;
            if (audioManager == manager) audioManager = null;
            if (worker == Thread.currentThread()) worker = null;
        }
    }

    @Override
    public synchronized void stopInjection() {
        running.set(false);
        ParcelFileDescriptor pipe = inputPipe;
        inputPipe = null;
        closeQuietly(pipe);
        Thread activeWorker = worker;
        if (activeWorker != null && activeWorker != Thread.currentThread()) {
            try { activeWorker.join(1200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (activeWorker == null || !activeWorker.isAlive()) {
            AudioTrack activeTrack = track;
            track = null;
            if (activeTrack != null) {
                try { activeTrack.stop(); } catch (Throwable ignored) {}
                try { activeTrack.release(); } catch (Throwable ignored) {}
            }
            AudioManager manager = audioManager;
            Object policy = audioPolicy;
            audioManager = null;
            audioPolicy = null;
            if (manager != null && policy != null) unregister(manager, policy);
        }
        status = "Injection stopped";
    }

    private static void unregister(AudioManager manager, Object policy) {
        try {
            manager.getClass().getDeclaredMethod("unregisterAudioPolicy", policy.getClass()).invoke(manager, policy);
        } catch (Throwable error) {
            Log.w(TAG, "Could not unregister AudioPolicy", error);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable e = error;
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd != null) try { pfd.close(); } catch (Throwable ignored) {}
    }

    @Override
    public String getStatus() {
        return status;
    }

    @Override
    public void destroy() {
        stopInjection();
        System.exit(0);
    }
}