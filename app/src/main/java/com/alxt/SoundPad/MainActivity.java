package com.alxt.SoundPad;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.*;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.widget.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import android.app.ActivityOptions;
import android.graphics.Rect;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 10, REQ_MIC = 11;
    private static final int RATE = 44100;
    private TextToSpeech tts;
    private EditText words;
    private TextView status;
    private Spinner inputSpinner, outputSpinner;
    private final ArrayList<AudioDeviceInfo> inputs = new ArrayList<>(), outputs = new ArrayList<>();
    private volatile AudioTrack playback;
    private Thread playbackThread;
    private volatile AudioRecord monitorRecord;
    private volatile AudioTrack monitorTrack;
    private Thread monitorThread;
    private final AtomicBoolean monitorRunning = new AtomicBoolean(false);
    private volatile boolean playbackRunning = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 22, 24, 22);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        TextView title = new TextView(this);
        title.setText("SoundPad");
        title.setTextSize(28);
        root.addView(title);
        status = new TextView(this);
        status.setText("Ready");
        status.setTextSize(15);
        root.addView(status);

        section(root, "Text to speak");
        words = new EditText(this);
        words.setHint("Type text here…");
        words.setMinLines(3);
        words.setGravity(Gravity.TOP);
        root.addView(words);
        button(root, "SPEAK TEXT", v -> {
            String text = words.getText().toString().trim();
            if (text.isEmpty()) { status.setText("Type something to speak."); return; }
            if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "soundpad-utterance");
        });
        button(root, "STOP SPEAKING", v -> { if (tts != null) tts.stop(); });

        section(root, "Internal audio recording");
        button(root, "START INTERNAL AUDIO RECORDING", v -> requestInternalCapture());
        button(root, "STOP INTERNAL AUDIO RECORDING", v -> {
            stopService(new Intent(this, CaptureService.class).setAction("STOP"));
            status.setText("Stopping and finalizing internal audio recording…");
        });
        button(root, "PLAY INTERNAL AUDIO RECORDING", v -> playInternalRecording());
        button(root, "STOP RECORDING PLAYBACK", v -> stopInternalPlayback());
        button(root, "SAVE RECORDING TO DOWNLOADS", v -> saveRecording());
        TextView info = new TextView(this);
        info.setText("Internal capture records only audio that Android and the source app allow to be captured. Recording requires the Android screen-capture permission prompt.");
        root.addView(info);

        section(root, "Freeform and browser");
        button(root, "OPEN FULL SOUNDPAD IN FREEFORM WINDOW", v -> openFreeformWindow());
        button(root, "OPEN BUILT-IN BROWSER", v -> startActivity(new Intent(this, BrowserActivity.class)));
        button(root, "OPEN OTHER APPS IN FREEFORM", v -> startActivity(new Intent(this, AppLauncherActivity.class)));
        TextView freeformInfo = new TextView(this);
        freeformInfo.setText("Freeform launch requires Shizuku permission and Android/device support for freeform windows. The system controls moving, resizing, minimizing and restoring the activity; unsupported devices may open it normally.");
        root.addView(freeformInfo);

        section(root, "Audio devices");
        TextView inLabel = new TextView(this); inLabel.setText("Microphone input"); root.addView(inLabel);
        inputSpinner = new Spinner(this); root.addView(inputSpinner);
        TextView outLabel = new TextView(this); outLabel.setText("Playback output for microphone monitor and recording playback"); root.addView(outLabel);
        outputSpinner = new Spinner(this); root.addView(outputSpinner);
        refreshDevices();
        button(root, "REFRESH AUDIO DEVICES", v -> refreshDevices());
        button(root, "START MICROPHONE MONITOR", v -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            } else startMicMonitor();
        });
        button(root, "STOP MICROPHONE MONITOR", v -> stopMicMonitor());
        TextView deviceInfo = new TextView(this);
        deviceInfo.setText("The input selector chooses a physical microphone for SoundPad's monitor. The output selector chooses a speaker/headset for SoundPad playback. Android does not let a normal app turn that input into a universal virtual microphone for other apps.");
        root.addView(deviceInfo);
        setContentView(scroll);

        tts = new TextToSpeech(this, result -> {
            if (result == TextToSpeech.SUCCESS) {
                int language = tts.setLanguage(Locale.getDefault());
                if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED)
                    language = tts.setLanguage(Locale.US);
                status.setText(language >= TextToSpeech.LANG_AVAILABLE ? "Text-to-speech ready." : "Install a supported Android TTS voice.");
            } else status.setText("Text-to-speech could not initialize.");
        });
    }

    private void openFreeformWindow() {
        if (Build.VERSION.SDK_INT < 24) {
            status.setText("Freeform window launch requires Android 7.0 or newer and device support.");
            return;
        }
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = Math.max(320, (int)(metrics.widthPixels * 0.82f));
        int height = Math.max(480, (int)(metrics.heightPixels * 0.82f));
        int left = Math.max(0, (metrics.widthPixels - width) / 2);
        int top = Math.max(0, (metrics.heightPixels - height) / 8);
        Rect bounds = new Rect(left, top, left + width, top + height);
        try {
            Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchBounds(bounds);
            startActivity(intent, options.toBundle());
            status.setText("Freeform window requested. If your Android build supports freeform, use its title bar to move, resize, minimize and restore SoundPad.");
        } catch (Exception e) {
            status.setText("Could not request a freeform window: " + e.getMessage());
        }
    }

    private void section(LinearLayout root, String text) {
        TextView v = new TextView(this); v.setText("\n" + text); v.setTextSize(21); v.setPadding(0, 18, 0, 8); root.addView(v);
    }
    private void button(LinearLayout root, String text, android.view.View.OnClickListener action) {
        Button b = new Button(this); b.setText(text); root.addView(b, new LinearLayout.LayoutParams(-1, -2));
        b.setOnClickListener(action);
    }
    private void refreshDevices() {
        inputs.clear(); outputs.clear();
        ArrayList<String> inNames = new ArrayList<>(), outNames = new ArrayList<>();
        inNames.add("Android default microphone");
        outNames.add("Android default output");
        AudioManager manager = (AudioManager)getSystemService(AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= 23 && manager != null) {
            for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                inputs.add(d); inNames.add(deviceName(d));
            }
            for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                outputs.add(d); outNames.add(deviceName(d));
            }
        }
        inputSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, inNames));
        outputSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, outNames));
        status.setText("Audio devices refreshed.");
    }
    private String deviceName(AudioDeviceInfo d) {
        String name = d.getProductName() == null ? "Audio device" : d.getProductName().toString();
        return name + " — " + d.getType();
    }
    private AudioDeviceInfo selectedInput() {
        int p = inputSpinner.getSelectedItemPosition();
        return p <= 0 || p > inputs.size() ? null : inputs.get(p - 1);
    }
    private AudioDeviceInfo selectedOutput() {
        int p = outputSpinner.getSelectedItemPosition();
        return p <= 0 || p > outputs.size() ? null : outputs.get(p - 1);
    }
    private void requestInternalCapture() {
        if (Build.VERSION.SDK_INT < 29) { status.setText("Internal audio capture requires Android 10 or newer."); return; }
        MediaProjectionManager m = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), REQ_CAPTURE);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_CAPTURE && result == RESULT_OK && data != null) {
            Intent service = new Intent(this, CaptureService.class).setAction("START")
                .putExtra("code", result).putExtra("data", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            status.setText("Internal audio recording started.");
        } else if (request == REQ_CAPTURE) status.setText("Recording permission was cancelled.");
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQ_MIC && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startMicMonitor();
        else if (request == REQ_MIC) status.setText("Microphone permission denied.");
    }
    private void startMicMonitor() {
        stopMicMonitor();
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            AudioRecord input = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(Math.max(min * 2, 8192)).build();
            AudioDeviceInfo in = selectedInput();
            if (in != null && !input.setPreferredDevice(in)) status.setText("Android rejected the selected microphone; using its default input.");
            int outMin = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack output = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(Math.max(outMin * 2, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM).build();
            AudioDeviceInfo out = selectedOutput();
            if (out != null && !output.setPreferredDevice(out)) status.setText("Android rejected the selected output; using its default output.");
            monitorRecord = input; monitorTrack = output; monitorRunning.set(true);
            input.startRecording(); output.play();
            monitorThread = new Thread(() -> {
                byte[] buffer = new byte[4096];
                while (monitorRunning.get()) {
                    int n = input.read(buffer, 0, buffer.length);
                    if (n > 0) output.write(buffer, 0, n);
                }
            }, "SoundPad-mic-monitor");
            monitorThread.start(); status.setText("Microphone monitor running.");
        } catch (Exception e) { stopMicMonitor(); status.setText("Could not start microphone monitor: " + e.getMessage()); }
    }
    private void stopMicMonitor() {
        monitorRunning.set(false);
        AudioRecord r = monitorRecord; monitorRecord = null;
        AudioTrack t = monitorTrack; monitorTrack = null;
        if (r != null) { try { r.stop(); } catch (Exception ignored) {} try { r.release(); } catch (Exception ignored) {} }
        if (t != null) { try { t.stop(); } catch (Exception ignored) {} try { t.release(); } catch (Exception ignored) {} }
        status.setText("Microphone monitor stopped.");
    }
    private File captureFile() { return new File(getFilesDir(), "capture.pcm"); }
    private void playInternalRecording() {
        stopInternalPlayback();
        File file = captureFile();
        if (!file.exists() || file.length() == 0) { status.setText("No recording found. Record and stop first."); return; }
        playbackRunning = true;
        playbackThread = new Thread(() -> {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack track = null;
            try (FileInputStream in = new FileInputStream(file)) {
                track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(Math.max(min * 2, 32768))
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
                AudioDeviceInfo out = selectedOutput();
                if (out != null) track.setPreferredDevice(out);
                playback = track; track.play();
                byte[] buf = new byte[16384]; int n;
                while (playbackRunning && (n = in.read(buf)) > 0) track.write(buf, 0, n);
            } catch (Exception e) { runOnUiThread(() -> status.setText("Playback failed: " + e.getMessage())); }
            finally { if (track != null) { try { track.stop(); } catch (Exception ignored) {} track.release(); } playback = null; playbackRunning = false; }
        }, "SoundPad-internal-playback");
        playbackThread.start(); status.setText("Playing internal audio recording.");
    }
    private void stopInternalPlayback() {
        playbackRunning = false;
        AudioTrack t = playback; playback = null;
        if (t != null) { try { t.stop(); } catch (Exception ignored) {} try { t.release(); } catch (Exception ignored) {} }
        status.setText("Recording playback stopped.");
    }
    private void saveRecording() {
        File pcm = captureFile();
        if (!pcm.exists() || pcm.length() == 0) { status.setText("No recording available to save."); return; }
        // Export a standards-compliant WAV file; the app does not bundle an MP3 encoder.
        try {
            String name = "SoundPad-" + System.currentTimeMillis() + ".wav";
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav");
            if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Could not create download file");
            try (OutputStream out = getContentResolver().openOutputStream(uri); FileInputStream in = new FileInputStream(pcm)) {
                writeWavHeader(out, pcm.length());
                byte[] b = new byte[16384]; int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            status.setText("Saved WAV recording to Downloads. MP3 export is not included in this build.");
        } catch (Exception e) { status.setText("Save failed: " + e.getMessage()); }
    }
    private void writeWavHeader(OutputStream out, long dataSize) throws IOException {
        long total = dataSize + 36;
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put(new byte[]{'R','I','F','F'}).putInt((int)total).put(new byte[]{'W','A','V','E','f','m','t',' '});
        h.putInt(16).putShort((short)1).putShort((short)2).putInt(RATE).putInt(RATE * 4).putShort((short)4).putShort((short)16);
        h.put(new byte[]{'d','a','t','a'}).putInt((int)dataSize); out.write(h.array());
    }
    @Override protected void onDestroy() {
        stopInternalPlayback(); stopMicMonitor();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}