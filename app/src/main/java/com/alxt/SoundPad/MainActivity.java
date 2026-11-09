package com.alxt.SoundPad;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.Arrays;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 8;
    private static final int REQ_RECORD_AUDIO = 9;
    private static final int SAMPLE_RATE = 44100;

    private TextToSpeech tts;
    private EditText words;
    private TextView status;
    private float savedPitch = 1.0f;
    private float savedHz = 150.0f;
    private boolean ttsReady = false;
    private volatile boolean playbackRunning = false;
    private volatile AudioTrack playbackTrack;
    private Thread playbackThread;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        savedPitch = getPreferences(0).getFloat("voice_pitch", 1.0f);
        savedHz = getPreferences(0).getFloat("voice_hz", 150.0f);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(22, 22, 22, 22);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);

        TextView heading = new TextView(this);
        heading.setText("SoundPad — Voice Studio");
        heading.setTextSize(24);
        root.addView(heading);

        status = new TextView(this);
        status.setText("Ready. Internal playback capture requires Android 10+ and the source app must allow capture.");
        root.addView(status);

        addButton(root, "Start internal audio recording", v -> requestCapturePermission());
        addButton(root, "Stop recording", v -> {
            startService(new Intent(this, CaptureService.class).setAction("STOP"));
            status.setText("Stopping recording and saving the audio…");
        });
        addButton(root, "▶ Play recorded internal audio", v -> playRecording());
        addButton(root, "Stop playback", v -> stopPlayback());
        addButton(root, "Analyze recording & save voice pitch profile", v -> analyzeAndSaveProfile());
        addButton(root, "Use saved voice pitch profile", v -> applySavedProfile());
        addButton(root, "Use natural Google-style TTS", v -> applyNaturalGoogleStyle());
        addButton(root, "Open Android TTS engine & voice settings", v -> openTtsSettings());
        addButton(root, "Apply warm older-male voice style", v -> applyOlderMaleStyle());

        TextView hint = new TextView(this);
        hint.setText("Text to speak:");
        root.addView(hint);
        words = new EditText(this);
        words.setHint("Type your text here…");
        words.setMinLines(3);
        words.setGravity(48);
        root.addView(words);

        addButton(root, "Speak text (natural voice)", v -> speakText());
        addButton(root, "Stop speech", v -> { if (tts != null) tts.stop(); });

        TextView note = new TextView(this);
        note.setText("\nThe recording playback button lets you hear the captured audio. The saved profile changes the Android TTS pitch approximately; it does not clone the speaker's timbre or identity. Some TTS engines ignore pitch requests or have limited pitch ranges. Capture works only when Android and the source app permit playback capture.");
        root.addView(note);
        setContentView(scroll);

        tts = new TextToSpeech(this, result -> {
            if (result == TextToSpeech.SUCCESS) {
                int lang = tts.setLanguage(Locale.getDefault());
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                    lang = tts.setLanguage(Locale.US);
                }
                ttsReady = lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED;
                if (ttsReady) {
                    boolean applied = applyPitchToEngine();
                    status.setText("TTS ready. Saved pitch: " + String.format(Locale.US, "%.0f Hz / %.2fx", savedHz, savedPitch)
                        + (applied ? " (applied)" : " (engine rejected pitch setting)"));
                } else status.setText("No supported TTS language is installed. Install or enable a device TTS voice.");
            } else status.setText("TTS initialization failed. Check Android Text-to-speech settings.");
        });
    }

    private void addButton(LinearLayout parent, String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(click);
        parent.addView(b);
    }

    private File recordingFile() { return new File(getFilesDir(), "capture.pcm"); }

    private void requestCapturePermission() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            return;
        }
        launchCaptureConsent();
    }

    private void launchCaptureConsent() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            status.setText("This Android device does not provide screen/audio capture.");
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQ_RECORD_AUDIO) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) launchCaptureConsent();
            else status.setText("Microphone permission is required by Android for playback capture. Permission was not granted.");
        }
    }

    private void analyzeAndSaveProfile() {
        File file = recordingFile();
        if (!file.exists() || file.length() < 16384) {
            status.setText("No usable recording found. Record at least a few seconds, stop, then analyze.");
            return;
        }
        try {
            float hz = estimateFundamental(file);
            if (hz < 65 || hz > 400) {
                status.setText("No reliable voice pitch detected. Try a clear 5–10 second speech recording with less music/background sound.");
                return;
            }
            savedHz = hz;
            savedPitch = Math.max(0.50f, Math.min(2.0f, hz / 150.0f));
            getPreferences(0).edit().putFloat("voice_pitch", savedPitch).putFloat("voice_hz", savedHz).commit();
            boolean applied = applyPitchToEngine();
            status.setText(String.format(Locale.US,
                "PROFILE SAVED: %.0f Hz. TTS pitch factor: %.2fx. %s",
                savedHz, savedPitch, applied ? "Applied to the TTS engine." : "TTS engine is not ready or rejected the setting."));
        } catch (Exception e) {
            status.setText("Pitch analysis failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // Signed 16-bit little-endian stereo PCM, 44.1 kHz. Estimate voiced-window F0
    // with normalized autocorrelation and use the median to reject outlier windows.
    private float estimateFundamental(File file) throws IOException {
        int maxBytes = (int)Math.min(file.length(), 16L * 1024L * 1024L);
        byte[] bytes = new byte[maxBytes];
        try (FileInputStream in = new FileInputStream(file)) {
            int pos = 0, n;
            while (pos < bytes.length && (n = in.read(bytes, pos, bytes.length - pos)) > 0) pos += n;
        }
        int frames = bytes.length / 4;
        int window = 4096;
        int hop = 2048;
        int minLag = SAMPLE_RATE / 400;
        int maxLag = SAMPLE_RATE / 65;
        float[] estimates = new float[Math.max(1, frames / hop)];
        int used = 0;
        double[] x = new double[window];

        for (int start = 0; start + window <= frames; start += hop) {
            double mean = 0;
            for (int i = 0; i < window; i++) {
                int p = (start + i) * 4;
                short sample = (short)((bytes[p] & 255) | (bytes[p + 1] << 8));
                x[i] = sample;
                mean += sample;
            }
            mean /= window;
            double energy = 0;
            for (int i = 0; i < window; i++) {
                x[i] = (x[i] - mean) * (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (window - 1)));
                energy += x[i] * x[i];
            }
            double rms = Math.sqrt(energy / window);
            if (rms < 120) continue;

            int bestLag = -1;
            double best = 0;
            for (int lag = minLag; lag <= maxLag; lag++) {
                double cross = 0, e1 = 0, e2 = 0;
                for (int i = 0; i < window - lag; i += 2) {
                    double a = x[i], b = x[i + lag];
                    cross += a * b;
                    e1 += a * a;
                    e2 += b * b;
                }
                double norm = cross / Math.sqrt((e1 + 1.0) * (e2 + 1.0));
                if (norm > best) { best = norm; bestLag = lag; }
            }
            if (bestLag > 0 && best > 0.28) estimates[used++] = SAMPLE_RATE / (float)bestLag;
        }
        if (used == 0) return 0;
        Arrays.sort(estimates, 0, used);
        return estimates[used / 2];
    }

    private boolean applyPitchToEngine() {
        if (tts == null || !ttsReady) return false;
        float factor = Math.max(0.50f, Math.min(2.0f, savedPitch));
        int pitchResult = tts.setPitch(factor);
        int rateResult = tts.setSpeechRate(0.84f);
        return pitchResult == TextToSpeech.SUCCESS && rateResult == TextToSpeech.SUCCESS;
    }

    // A generic older-male presentation preset; this is not a clone of any real person.
    private void applyOlderMaleStyle() {
        savedPitch = 0.78f;
        savedHz = 117.0f;
        getPreferences(0).edit().putFloat("voice_pitch", savedPitch).putFloat("voice_hz", savedHz).commit();
        boolean applied = applyPitchToEngine();
        status.setText(applied
            ? "Warm older-male style applied: lower pitch and slower pacing. The installed TTS voice still determines the actual voice and accent."
            : "Preset saved. Initialize or enable a TTS engine in Android settings, then try again.");
    }

    private void applySavedProfile() {
        savedPitch = getPreferences(0).getFloat("voice_pitch", savedPitch);
        savedHz = getPreferences(0).getFloat("voice_hz", savedHz);
        boolean applied = applyPitchToEngine();
        status.setText(String.format(Locale.US,
            "Saved profile loaded: %.0f Hz (TTS pitch %.2fx). %s",
            savedHz, savedPitch, applied ? "Pitch setting accepted by TTS." : "TTS is not ready or rejected pitch."));
    }

    private void speakText() {
        if (tts == null || !ttsReady) {
            status.setText("Text-to-speech is not ready. Check Android Text-to-speech settings.");
            return;
        }
        String text = words.getText().toString().trim();
        if (text.isEmpty()) { status.setText("Type something to speak first."); return; }
        if (!applyPitchToEngine()) {
            status.setText("Your TTS engine rejected the saved pitch setting. Try changing the installed TTS voice/engine in Android settings.");
            return;
        }
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
        int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "soundpad-tts");
        status.setText(result == TextToSpeech.SUCCESS
            ? String.format(Locale.US, "Speaking with the selected TTS engine at natural pitch and normal speed.", savedPitch)
            : "Could not start speech. Check the device TTS engine.");
    }

    private void playRecording() {
        final File file = recordingFile();
        if (!file.exists() || file.length() < 4) {
            status.setText("No recording file found yet. Record internal audio and press Stop recording first.");
            return;
        }
        stopPlayback();
        playbackRunning = true;
        playbackThread = new Thread(() -> {
            AudioTrack track = null;
            try (FileInputStream in = new FileInputStream(file)) {
                int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                int bufferSize = Math.max(min, 16384);
                track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(bufferSize).setTransferMode(AudioTrack.MODE_STREAM).build();
                playbackTrack = track;
                track.play();
                byte[] buffer = new byte[8192];
                int n;
                while (playbackRunning && (n = in.read(buffer)) != -1) {
                    int offset = 0;
                    while (playbackRunning && offset < n) {
                        int wrote = track.write(buffer, offset, n - offset);
                        if (wrote < 0) throw new IOException("AudioTrack write error " + wrote);
                        offset += wrote;
                    }
                }
                runOnUiThread(() -> status.setText("Recording playback finished."));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Playback failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            } finally {
                playbackRunning = false;
                playbackTrack = null;
                if (track != null) {
                    try { track.stop(); } catch (Exception ignored) {}
                    track.release();
                }
            }
        }, "SoundPad-playback");
        playbackThread.start();
        status.setText("Playing saved internal audio…");
    }

    private void stopPlayback() {
        playbackRunning = false;
        AudioTrack track = playbackTrack;
        if (track != null) {
            try { track.pause(); track.flush(); } catch (Exception ignored) {}
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_CAPTURE && result == Activity.RESULT_OK && data != null) {
            Intent service = new Intent(this, CaptureService.class).setAction("START")
                .putExtra("code", result).putExtra("data", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            status.setText("Capture started. Play permitted audio in another app, then return and stop recording.");
        } else if (request == REQ_CAPTURE) status.setText("Capture permission was cancelled.");
    }

    @Override protected void onDestroy() {
        stopPlayback();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
