package com.alxt.SoundPad;

import android.app.*;
import android.content.*;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 8;
    private TextToSpeech tts;
    private EditText words;
    private TextView status;
    private float savedPitch = 1.0f;
    private boolean ttsReady = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        savedPitch = getPreferences(0).getFloat("voice_pitch", 1.0f);
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

        addButton(root, "Start internal audio recording", v -> {
            MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            Intent captureIntent = manager.createScreenCaptureIntent();
            startActivityForResult(captureIntent, REQ_CAPTURE);
        });
        addButton(root, "Stop recording", v ->
            startService(new Intent(this, CaptureService.class).setAction("STOP")));

        addButton(root, "Analyze recording & save voice pitch profile", v -> analyzeAndSaveProfile());
        addButton(root, "Use saved voice pitch profile", v -> applySavedProfile());

        TextView hint = new TextView(this);
        hint.setText("Text to speak (short or long):");
        root.addView(hint);
        words = new EditText(this);
        words.setHint("Type your text here…");
        words.setMinLines(3);
        words.setGravity(48);
        root.addView(words);

        addButton(root, "Speak with saved profile (fast)", v -> speakText());
        addButton(root, "Stop speech", v -> { if (tts != null) tts.stop(); });

        TextView note = new TextView(this);
        note.setText("\nSaved profile adjusts the device TTS pitch approximately. It does not reproduce a person's exact voice. Full voice cloning requires a separate trained speech model. Recording is saved in the app's private files.");
        root.addView(note);
        setContentView(scroll);

        tts = new TextToSpeech(this, result -> {
            if (result == TextToSpeech.SUCCESS) {
                int lang = tts.setLanguage(Locale.getDefault());
                ttsReady = lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED;
                applySavedProfile();
                status.setText(ttsReady ? "TTS ready. Saved pitch profile: " + savedPitch : "TTS language unavailable on this device.");
            } else status.setText("TTS initialization failed.");
        });
    }

    private void addButton(LinearLayout parent, String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(click);
        parent.addView(b);
    }

    private File recordingFile() { return new File(getExternalFilesDir(null), "capture.pcm"); }

    private void analyzeAndSaveProfile() {
        File file = recordingFile();
        if (!file.exists() || file.length() < 4096) {
            status.setText("No usable capture found yet. Record some speech first, then stop recording.");
            return;
        }
        try {
            float hz = estimateFundamental(file);
            if (hz < 65 || hz > 400) {
                status.setText("Could not detect a reliable speaking pitch. Try a clearer, longer voice recording.");
                return;
            }
            savedPitch = Math.max(0.70f, Math.min(1.40f, hz / 150.0f));
            getPreferences(0).edit().putFloat("voice_pitch", savedPitch).putFloat("voice_hz", hz).apply();
            applySavedProfile();
            status.setText(String.format(Locale.US, "Saved voice pitch profile: %.0f Hz (TTS pitch %.2fx). This is an approximate pitch match.", hz, savedPitch));
        } catch (Exception e) {
            status.setText("Could not analyze recording: " + e.getMessage());
        }
    }

    // CaptureService writes signed 16-bit little-endian stereo PCM at 44.1 kHz.
    // Estimate F0 by autocorrelation over short mono windows.
    private float estimateFundamental(File file) throws IOException {
        byte[] bytes = new byte[(int)Math.min(file.length(), 4 * 1024 * 1024)];
        try (FileInputStream in = new FileInputStream(file)) {
            int pos = 0, n;
            while (pos < bytes.length && (n = in.read(bytes, pos, bytes.length - pos)) > 0) pos += n;
        }
        int frames = bytes.length / 4;
        int window = 4096;
        int minLag = 110, maxLag = 600;
        double total = 0; int count = 0;
        for (int start = 0; start + window * 4 <= bytes.length; start += window * 4) {
            short[] x = new short[window];
            double mean = 0;
            for (int i=0;i<window;i++) {
                int p = start + i*4;
                x[i] = (short)((bytes[p] & 255) | (bytes[p+1] << 8));
                mean += x[i];
            }
            mean /= window;
            for (int i=0;i<window;i++) x[i] -= (short)mean;
            long best = 0; int bestLag = 0;
            for (int lag=minLag;lag<=maxLag;lag++) {
                long corr = 0;
                for (int i=0;i<window-lag;i+=2) corr += (long)x[i]*x[i+lag];
                if (corr > best) { best=corr; bestLag=lag; }
            }
            if (bestLag > 0 && best > 1000000L) { total += 44100.0 / bestLag; count++; }
        }
        return count == 0 ? 0 : (float)(total / count);
    }

    private void applySavedProfile() {
        if (tts != null && ttsReady) {
            tts.setPitch(savedPitch);
            tts.setSpeechRate(1.20f);
        }
    }

    private void speakText() {
        if (tts == null || !ttsReady) { status.setText("Text-to-speech is not ready yet."); return; }
        String text = words.getText().toString().trim();
        if (text.isEmpty()) { status.setText("Type something to speak first."); return; }
        applySavedProfile();
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
        int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "soundpad-tts");
        if (result == TextToSpeech.ERROR) status.setText("Could not start speech. Check the device TTS engine.");
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_CAPTURE && result == Activity.RESULT_OK && data != null) {
            Intent service = new Intent(this, CaptureService.class).setAction("START")
                .putExtra("code", result).putExtra("data", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            status.setText("Capture started. Play permitted speech in another app, then return and stop recording.");
        } else if (request == REQ_CAPTURE) status.setText("Capture permission was cancelled.");
    }

    @Override protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
