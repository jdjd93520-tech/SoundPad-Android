package com.alxt.SoundPad;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.Os;
import android.util.Log;

import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AudioInjectionService extends IAudioInjectionService.Stub {
    private static final String TAG = "SoundPadInjection";
    private static final String MODIFY_AUDIO_ROUTING = "android.permission.MODIFY_AUDIO_ROUTING";
    // Package IDs used by the Global, BGMI, Korean and Vietnamese PUBG Mobile releases.
    private static final String[] PUBG_PACKAGES = {
        "com.tencent.ig", "com.pubg.imobile", "com.pubg.krmobile", "com.vng.pubgmobile"
    };

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean targetRecorderActive = new AtomicBoolean(false);
    private volatile ParcelFileDescriptor inputPipe;
    private volatile AudioTrack track;
    private volatile AudioManager audioManager;
    private volatile Object audioPolicy;
    private volatile Thread worker;
    private volatile AudioManager.AudioRecordingCallback recordingCallback;
    private volatile String status = "Shizuku service ready";

    public AudioInjectionService() {
        status = "Shizuku service ready (uid=" + Os.getuid() + ")";
    }

    @Override
    public synchronized String startInjection(ParcelFileDescriptor pipe) throws RemoteException {
        if (Build.VERSION.SDK_INT < 33) {
            closeQuietly(pipe);
            return "AudioPolicy microphone injection requires Android 13 or newer.";
        }
        if (pipe == null) return "No audio pipe was supplied.";
        stopInjection();
        try {
            Context context = SystemContextWorkaround.create();
            int permission = context.checkSelfPermission(MODIFY_AUDIO_ROUTING);
            if (permission != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Shizuku shell uid " + Os.getuid()
                    + " does not have MODIFY_AUDIO_ROUTING on this Android build.");
            }

            Set<Integer> targetUids = findInstalledPubgUids(context);
            if (targetUids.isEmpty()) {
                throw new IllegalStateException("No supported PUBG Mobile package was found. "
                    + "Expected Global, BGMI, Korean or Vietnamese package.");
            }

            // Follow MicInjector's proven Android 13+ injector pattern: match the
            // recorder capture preset, rather than relying only on RULE_MATCH_UID.
            // PUBG may open its voice recorder as MIC or VOICE_COMMUNICATION;
            // CAMCORDER is retained for apps that use that preset.
            Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
            Object ruleBuilder = ruleBuilderClass.getDeclaredConstructor().newInstance();
            Method setRole = ruleBuilderClass.getDeclaredMethod("setTargetMixRole", int.class);
            setRole.setAccessible(true);
            setRole.invoke(ruleBuilder, 1); // MIX_ROLE_INJECTOR

            Field presetRuleField = Class.forName("android.media.audiopolicy.AudioMixingRule")
                .getDeclaredField("RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET");
            presetRuleField.setAccessible(true);
            int matchCapturePresetRule = presetRuleField.getInt(null);
            Method addRule = ruleBuilderClass.getDeclaredMethod("addMixRule", int.class, Object.class);
            addRule.setAccessible(true);
            addCapturePresetRule(ruleBuilder, addRule, matchCapturePresetRule, MediaRecorder.AudioSource.MIC);
            addCapturePresetRule(ruleBuilder, addRule, matchCapturePresetRule, MediaRecorder.AudioSource.VOICE_COMMUNICATION);
            addCapturePresetRule(ruleBuilder, addRule, matchCapturePresetRule, MediaRecorder.AudioSource.CAMCORDER);

            Object rule = ruleBuilderClass.getDeclaredMethod("build").invoke(ruleBuilder);
            Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
            Constructor<?> mixConstructor = mixBuilderClass.getDeclaredConstructor(rule.getClass());
            mixConstructor.setAccessible(true);
            Object mixBuilder = mixConstructor.newInstance(rule);

            AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
            Method setFormat = mixBuilderClass.getDeclaredMethod("setFormat", AudioFormat.class);
            setFormat.setAccessible(true);
            setFormat.invoke(mixBuilder, format);
            Method setRouteFlags = mixBuilderClass.getDeclaredMethod("setRouteFlags", int.class);
            setRouteFlags.setAccessible(true);
            setRouteFlags.invoke(mixBuilder, 1 << 1); // ROUTE_FLAG_LOOP_BACK, as in AOSP AudioInjector.
            Object mix = mixBuilderClass.getDeclaredMethod("build").invoke(mixBuilder);

            Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
            Constructor<?> policyConstructor = policyBuilderClass.getDeclaredConstructor(Context.class);
            policyConstructor.setAccessible(true);
            Object policyBuilder = policyConstructor.newInstance(context);
            Method addMix = policyBuilderClass.getDeclaredMethod("addMix", mix.getClass());
            addMix.setAccessible(true);
            addMix.invoke(policyBuilder, mix);
            Object policy = policyBuilderClass.getDeclaredMethod("build").invoke(policyBuilder);

            AudioManager manager = context.getSystemService(AudioManager.class);
            if (manager == null) throw new IllegalStateException("AudioManager unavailable");
            Method register = manager.getClass().getDeclaredMethod("registerAudioPolicy", policy.getClass());
            register.setAccessible(true);
            int result = (Integer) register.invoke(manager, policy);
            if (result != 0) {
                throw new IllegalStateException("registerAudioPolicy returned " + result);
            }

            Method createTrack = policy.getClass().getDeclaredMethod("createAudioTrackSource", mix.getClass());
            createTrack.setAccessible(true);
            AudioTrack audioTrack = (AudioTrack) createTrack.invoke(policy, mix);
            if (audioTrack == null) throw new IllegalStateException("AudioPolicy did not create an injector AudioTrack");
            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                audioTrack.release();
                throw new IllegalStateException("Injector AudioTrack was not initialized");
            }
            audioTrack.play();

            this.audioManager = manager;
            this.audioPolicy = policy;
            this.track = audioTrack;
            this.inputPipe = pipe;
            this.targetRecorderActive.set(false);

            AudioManager.AudioRecordingCallback callback = new AudioManager.AudioRecordingCallback() {
                @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                    boolean found = false;
                    int activeUid = -1;
                    for (AudioRecordingConfiguration config : configs) {
                        if (targetUids.contains(getClientUid(config))) {
                            found = true;
                            activeUid = getClientUid(config);
                            break;
                        }
                    }
                    targetRecorderActive.set(found);
                    status = found
                        ? "Injector registered; PUBG microphone recorder detected (uid=" + activeUid + ")."
                        : "Injector registered; waiting for PUBG to open its microphone.";
                }
            };
            this.recordingCallback = callback;
            manager.registerAudioRecordingCallback(callback, new Handler(Looper.getMainLooper()));
            for (AudioRecordingConfiguration config : manager.getActiveRecordingConfigurations()) {
                if (targetUids.contains(getClientUid(config))) {
                    targetRecorderActive.set(true);
                    status = "Injector registered; PUBG microphone recorder detected (uid="
                        + getClientUid(config) + ").";
                    break;
                }
            }

            running.set(true);
            worker = new Thread(() -> pump(pipe, audioTrack, manager, policy), "SoundPad-mic-injector");
            worker.start();
            if (!targetRecorderActive.get()) {
                status = "AudioPolicy injector registered for PUBG UID(s) " + targetUids
                    + "; waiting for PUBG voice chat to open its microphone.";
            }
            return "OK: AudioPolicy injector registered for PUBG UID(s) " + targetUids
                + ". PUBG audio reception is not yet verified.";
        } catch (Throwable error) {
            closeQuietly(pipe);
            AudioManager manager = audioManager;
            Object policy = audioPolicy;
            audioManager = null;
            audioPolicy = null;
            unregisterRecordingCallback(manager);
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

    private static void addCapturePresetRule(
        Object ruleBuilder, Method addRule, int ruleType, int capturePreset
    ) throws Exception {
        AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder();
        Method setCapturePreset = AudioAttributes.Builder.class.getDeclaredMethod(
            "setCapturePreset", int.class
        );
        setCapturePreset.setAccessible(true);
        setCapturePreset.invoke(attributesBuilder, capturePreset);
        addRule.invoke(ruleBuilder, ruleType, attributesBuilder.build());
    }

    // getClientUid() is hidden from the public Android SDK stubs.
    private static int getClientUid(AudioRecordingConfiguration config) {
        try {
            Method method = AudioRecordingConfiguration.class.getDeclaredMethod("getClientUid");
            method.setAccessible(true);
            return (Integer) method.invoke(config);
        } catch (Throwable error) {
            Log.w(TAG, "Cannot read recording client UID on this Android build", error);
            return -1;
        }
    }

    private static Set<Integer> findInstalledPubgUids(Context context) throws Exception {
        Set<Integer> uids = new LinkedHashSet<>();
        for (String packageName : PUBG_PACKAGES) {
            try {
                ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
                uids.add(info.uid);
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                // This regional release is not installed.
            }
        }
        return uids;
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
            status = "Audio injection stream ended.";
        } catch (Throwable error) {
            status = "Audio injection stopped: " + rootMessage(error);
            Log.e(TAG, status, error);
        } finally {
            running.set(false);
            closeQuietly(pipe);
            try { audioTrack.stop(); } catch (Throwable ignored) {}
            try { audioTrack.release(); } catch (Throwable ignored) {}
            unregisterRecordingCallback(manager);
            unregister(manager, policy);
            if (inputPipe == pipe) inputPipe = null;
            if (track == audioTrack) track = null;
            if (audioPolicy == policy) audioPolicy = null;
            if (audioManager == manager) audioManager = null;
            if (worker == Thread.currentThread()) worker = null;
        }
    }

    private void unregisterRecordingCallback(AudioManager manager) {
        AudioManager.AudioRecordingCallback callback = recordingCallback;
        recordingCallback = null;
        if (manager != null && callback != null) {
            try { manager.unregisterAudioRecordingCallback(callback); }
            catch (Throwable error) { Log.w(TAG, "Could not unregister recording callback", error); }
        }
    }

    @Override
    public synchronized void stopInjection() {
        running.set(false);
        ParcelFileDescriptor pipe = inputPipe;
        inputPipe = null;
        closeQuietly(pipe);
        AudioTrack activeTrack = track;
        if (activeTrack != null) {
            try { activeTrack.stop(); } catch (Throwable ignored) {}
        }
        Thread activeWorker = worker;
        if (activeWorker != null && activeWorker != Thread.currentThread()) {
            try { activeWorker.join(1200); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (activeWorker == null || !activeWorker.isAlive()) {
            AudioTrack remainingTrack = track;
            track = null;
            if (remainingTrack != null) {
                try { remainingTrack.release(); } catch (Throwable ignored) {}
            }
            AudioManager manager = audioManager;
            Object policy = audioPolicy;
            audioManager = null;
            audioPolicy = null;
            unregisterRecordingCallback(manager);
            if (manager != null && policy != null) unregister(manager, policy);
        }
        status = "Injection stopped";
    }

    private static void unregister(AudioManager manager, Object policy) {
        if (manager == null || policy == null) return;
        try {
            Method method = manager.getClass().getDeclaredMethod("unregisterAudioPolicy", policy.getClass());
            method.setAccessible(true);
            method.invoke(manager, policy);
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

    @Override public String getStatus() { return status; }

    @Override public void destroy() {
        stopInjection();
        System.exit(0);
    }
}
