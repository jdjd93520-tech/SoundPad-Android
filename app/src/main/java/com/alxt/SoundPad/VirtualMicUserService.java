package com.alxt.SoundPad;

import android.content.Context;
import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Runs in the Shizuku shell process. AudioPolicy is accessed reflectively because
 * injector APIs are system APIs and are not part of the normal Android SDK stubs.
 */
public final class VirtualMicUserService extends Binder {
    private static final String DESCRIPTOR = "com.alxt.SoundPad.IVirtualMic";
    private static final int START = FIRST_CALL_TRANSACTION;
    private static final int WRITE = FIRST_CALL_TRANSACTION + 1;
    private static final int STOP = FIRST_CALL_TRANSACTION + 2;
    private final Context context;
    private Object policy;
    private Object mix;
    private AudioTrack track;

    public VirtualMicUserService(Context context) { this.context = context; }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
        data.enforceInterface(DESCRIPTOR);
        try {
            if (code == START) {
                int uid = data.readInt();
                int rate = data.readInt();
                int channels = data.readInt();
                start(uid, rate, channels);
                reply.writeNoException();
                reply.writeString("Audio injector registered for UID " + uid);
                return true;
            }
            if (code == WRITE) {
                byte[] bytes = data.createByteArray();
                int written = track == null ? -1 : track.write(bytes, 0, bytes.length);
                reply.writeNoException();
                reply.writeInt(written);
                return true;
            }
            if (code == STOP) {
                stop();
                reply.writeNoException();
                return true;
            }
        } catch (Throwable error) {
            if (reply != null) { reply.writeException(new IllegalStateException(error.toString())); return true; }
            throw new RemoteException(error.toString());
        }
        return super.onTransact(code, data, reply, flags);
    }

    private void start(int uid, int rate, int channels) throws Exception {
        stop();
        Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Object ruleBuilder = ruleClass.getDeclaredClasses()[0].getConstructor().newInstance();
        ruleBuilder.getClass().getMethod("setTargetMixRole", int.class).invoke(ruleBuilder, 1);
        ruleBuilder.getClass().getMethod("addMixRule", int.class, Object.class).invoke(ruleBuilder, 4, uid);
        Object rule = ruleBuilder.getClass().getMethod("build").invoke(ruleBuilder);

        int channelMask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate).setChannelMask(channelMask).build();

        Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Constructor<?> mixCtor = mixBuilderClass.getConstructor(ruleClass);
        Object mixBuilder = mixCtor.newInstance(rule);
        mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format);
        mixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder, 2);
        mix = mixBuilderClass.getMethod("build").invoke(mixBuilder);

        Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Object policyBuilder = policyBuilderClass.getConstructor(Context.class).newInstance(context);
        policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix);
        policy = policyBuilderClass.getMethod("build").invoke(policyBuilder);
        Object audioManager = context.getSystemService(Context.AUDIO_SERVICE);
        int result = (Integer) audioManager.getClass().getMethod("registerAudioPolicy", policyClass).invoke(audioManager, policy);
        if (result != 0) throw new IllegalStateException("registerAudioPolicy failed: " + result);
        track = (AudioTrack) policyClass.getMethod("createAudioTrackSource", mixClass).invoke(policy, mix);
        if (track == null || track.getState() != AudioTrack.STATE_INITIALIZED)
            throw new IllegalStateException("AudioPolicy did not create an injector track");
        track.play();
    }

    private void stop() {
        if (track != null) { try { track.stop(); } catch (Exception ignored) {} track.release(); track = null; }
        if (policy != null) {
            try {
                Object audioManager = context.getSystemService(Context.AUDIO_SERVICE);
                audioManager.getClass().getMethod("unregisterAudioPolicy", Class.forName("android.media.audiopolicy.AudioPolicy"))
                    .invoke(audioManager, policy);
            } catch (Exception ignored) {}
            policy = null;
            mix = null;
        }
    }

    @Override protected void finalize() throws Throwable { stop(); super.finalize(); }
}