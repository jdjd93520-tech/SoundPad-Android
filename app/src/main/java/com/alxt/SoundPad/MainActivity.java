package com.alxt.SoundPad;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
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
import java.util.ArrayList;
import android.provider.Settings;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 8;
    private static final int REQ_RECORD_AUDIO = 9;
    private static final int REQ_SCREEN_CAPTURE = 10;
    private static final int REQ_AUDIO_FILE = 11;
    private static final int REQ_MIC_TEST = 12;
    private static final int REQ_SOUNDBOARD_FILE = 13;
    private static final int SHIZUKU_PERMISSION_REQUEST = 41;
    private final ArrayList<Uri> soundboardSounds = new ArrayList<>();
    private LinearLayout soundboardContainer;
    private EditText soundSearch;
    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener = (requestCode, grantResult) -> runOnUiThread(this::updateShizukuStatus);
    private int pendingMicDeviceId = 0;
    private Uri selectedAudioUri;
    private static final int SAMPLE_RATE = 44100;

    private TextToSpeech tts;
    private EditText words;
    private TextView status;
    private Spinner fpsSpinner;
    private CheckBox hudToggle;
    private float savedPitch = 1.0f;
    private float savedHz = 150.0f;
    private boolean ttsReady = false;
    private volatile boolean playbackRunning = false;
    private volatile AudioTrack playbackTrack;
    private Thread playbackThread;
    private volatile boolean micMonitorRunning = false;
    private volatile AudioRecord micMonitorRecorder;
    private volatile AudioTrack micMonitorTrack;
    private Thread micMonitorThread;

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

        TextView shizukuTitle = new TextView(this);
        shizukuTitle.setText("\\nAdvanced compatibility");
        shizukuTitle.setTextSize(20);
        root.addView(shizukuTitle);
        TextView shizukuStatus = new TextView(this);
        shizukuStatus.setText("Shizuku: checking…");
        root.addView(shizukuStatus);
        addButton(root, "CHECK / REQUEST SHIZUKU ACCESS", v -> {
            try {
                if (!Shizuku.pingBinder()) {
                    shizukuStatus.setText("Shizuku is not running. Start its service, then check again.");
                } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    shizukuStatus.setText("Shizuku permission granted. This does not guarantee system audio-injection privileges.");
                } else {
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST);
                    shizukuStatus.setText("Permission request sent to Shizuku.");
                }
            } catch (Exception e) {
                shizukuStatus.setText("Shizuku unavailable: " + e.getClass().getSimpleName());
            }
        });
        addButton(root, "OPEN SHIZUKU APP", v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))); }
            catch (Exception e) { shizukuStatus.setText("Open the Shizuku app manually."); }
        });
        addButton(root, "REFRESH SHIZUKU STATUS", v -> updateShizukuStatus());
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);

        TextView bridgeTitle = new TextView(this);
        bridgeTitle.setText("\\nMicrophone & Audio Routing");
        bridgeTitle.setTextSize(20);
        root.addView(bridgeTitle);
        TextView routeHint = new TextView(this);
        routeHint.setText("Choose where SoundPad plays audio. Available routes depend on your phone and connected devices:");
        root.addView(routeHint);
        TextView outputLabel = new TextView(this);
        outputLabel.setText("PLAYBACK OUTPUT (speaker / headset / Bluetooth / USB)");
        root.addView(outputLabel);
        Spinner outputSpinner = new Spinner(this);
        root.addView(outputSpinner);
        java.util.ArrayList<android.media.AudioDeviceInfo> outputDevices = new java.util.ArrayList<>();
        java.util.ArrayList<String> outputLabels = new java.util.ArrayList<>();
        outputLabels.add("System default output");
        if (Build.VERSION.SDK_INT >= 23) {
            android.media.AudioManager am = (android.media.AudioManager)getSystemService(AUDIO_SERVICE);
            for (android.media.AudioDeviceInfo device : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
                outputDevices.add(device);
                outputLabels.add(audioDeviceLabel(device));
            }
        }
        outputSpinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, outputLabels));

        TextView inputLabel = new TextView(this);
        inputLabel.setText("MICROPHONE INPUT (recording source — not a playback output)");
        root.addView(inputLabel);
        Spinner inputSpinner = new Spinner(this);
        root.addView(inputSpinner);
        java.util.ArrayList<android.media.AudioDeviceInfo> inputDevices = new java.util.ArrayList<>();
        java.util.ArrayList<String> inputLabels = new java.util.ArrayList<>();
        inputLabels.add("Android default microphone");
        refreshInputDevices(inputDevices, inputLabels);
        inputSpinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, inputLabels));

        addButton(root, "REFRESH ALL AUDIO DEVICES", v -> {
            outputDevices.clear();
            outputLabels.clear();
            outputLabels.add("System default output");
            if (Build.VERSION.SDK_INT >= 23) {
                android.media.AudioManager am = (android.media.AudioManager)getSystemService(AUDIO_SERVICE);
                for (android.media.AudioDeviceInfo device : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
                    outputDevices.add(device);
                    outputLabels.add(audioDeviceLabel(device));
                }
            }
            outputSpinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, outputLabels));
            inputDevices.clear();
            inputLabels.clear();
            inputLabels.add("Android default microphone");
            refreshInputDevices(inputDevices, inputLabels);
            inputSpinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, inputLabels));
            status.setText("Audio devices refreshed. Connect your headset or Bluetooth/USB device first.");
        });
        addButton(root, "APPLY PLAYBACK OUTPUT", v -> {
            int position = outputSpinner.getSelectedItemPosition();
            int deviceId = position <= 0 || position - 1 >= outputDevices.size() ? 0 : outputDevices.get(position - 1).getId();
            Intent route = new Intent(this, AudioBridgeService.class).setAction(AudioBridgeService.SET_OUTPUT_DEVICE)
                .putExtra(AudioBridgeService.EXTRA_OUTPUT_DEVICE_ID, deviceId);
            startService(route);
            status.setText(deviceId == 0 ? "Playback output set to Android system default." : "Playback output requested: " + outputLabels.get(position));
        });
        addButton(root, "TEST SELECTED MICROPHONE INPUT", v -> {
            int position = inputSpinner.getSelectedItemPosition();
            pendingMicDeviceId = position <= 0 || position - 1 >= inputDevices.size() ? 0 : inputDevices.get(position - 1).getId();
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_TEST);
            } else {
                testMicrophoneInput(pendingMicDeviceId);
            }
        });
        addButton(root, "START MIC INPUT → SYSTEM DEFAULT SPEAKER MONITOR", v -> {
            int position = inputSpinner.getSelectedItemPosition();
            int deviceId = position <= 0 || position - 1 >= inputDevices.size() ? 0 : inputDevices.get(position - 1).getId();
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                pendingMicDeviceId = deviceId;
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_TEST);
                status.setText("Grant microphone permission, then press the monitor button again.");
            } else {
                startMicMonitor(deviceId);
            }
        });
        addButton(root, "STOP MIC MONITOR", v -> stopMicMonitor());
        addButton(root, "USE ANDROID COMMUNICATION AUDIO MODE", v -> {
            startService(new Intent(this, AudioBridgeService.class).setAction(AudioBridgeService.ROUTE_CALL_AUDIO));
            status.setText("Communication-audio routing requested. Android decides the available route; no virtual mic is created.");
        });
        addButton(root, "CHOOSE AUDIO FILE", v -> {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("audio/*");
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(picker, REQ_AUDIO_FILE);
        });
        addButton(root, "▶ PLAY FILE THROUGH SELECTED OUTPUT", v -> {
            if (selectedAudioUri == null) { status.setText("Choose an audio file first."); return; }
            int position = outputSpinner.getSelectedItemPosition();
            int deviceId = position <= 0 || position - 1 >= outputDevices.size() ? 0 : outputDevices.get(position - 1).getId();
            Intent play = new Intent(this, AudioBridgeService.class).setAction(AudioBridgeService.PLAY).setData(selectedAudioUri)
                .putExtra(AudioBridgeService.EXTRA_OUTPUT_DEVICE_ID, deviceId);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(play); else startService(play);
            status.setText("Starting selected audio file on " + (deviceId == 0 ? "system default output" : outputLabels.get(position)) + ".");
        });
        addButton(root, "PAUSE PLAYBACK", v -> startService(new Intent(this, AudioBridgeService.class).setAction(AudioBridgeService.PAUSE)));
        addButton(root, "RESUME PLAYBACK", v -> startService(new Intent(this, AudioBridgeService.class).setAction(AudioBridgeService.RESUME)));
        addButton(root, "STOP PLAYBACK / RESET ROUTING", v -> startService(new Intent(this, AudioBridgeService.class).setAction(AudioBridgeService.STOP)));
        addButton(root, "OPEN ANDROID SOUND SETTINGS", v -> {
            try { startActivity(new Intent("android.settings.SOUND_SETTINGS")); }
            catch (Exception e) { status.setText("Open Android Settings to manage connected audio devices."); }
        });
        TextView bridgeNote = new TextView(this);
        bridgeNote.setText("Important: Android exposes playback outputs (speaker, wired headset, Bluetooth and USB when connected) separately from microphone inputs. An ordinary no-root app cannot select a microphone as a speaker output or create a virtual mic that another game/app can use. This screen routes SoundPad playback to a supported output; for mic loopback you still need compatible physical audio hardware or an Android/device feature that explicitly supports it. The communication mode is only a routing request.");
        root.addView(bridgeNote);

        TextView screenTitle = new TextView(this);
        screenTitle.setText("\\nScreen recording");
        screenTitle.setTextSize(20);
        root.addView(screenTitle);
        TextView fpsLabel = new TextView(this);
        fpsLabel.setText("Recording frame-rate target:");
        root.addView(fpsLabel);
        fpsSpinner = new Spinner(this);
        fpsSpinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"60 FPS", "120 FPS"}));
        root.addView(fpsSpinner);
        hudToggle = new CheckBox(this);
        hudToggle.setText("Show small recording status overlay while recording");
        hudToggle.setChecked(false);
        root.addView(hudToggle);
        addButton(root, "Start screen recording", v -> requestScreenRecording());
        addButton(root, "Stop screen recording", v -> {
            startService(new Intent(this, ScreenRecordService.class).setAction("STOP"));
            status.setText("Stopping screen recording…");
        });
        TextView screenNote = new TextView(this);
        screenNote.setText("Screen recordings are saved as MP4 in SoundPad's Movies folder. 120 FPS is a requested target; Android and the phone's encoder may limit the actual frame rate. The optional overlay is only a recording-status indicator, not an aim-assist.");
        root.addView(screenNote);

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
        TextView boardTitle = new TextView(this);
        boardTitle.setText("\\nSoundboard");
        boardTitle.setTextSize(20);
        root.addView(boardTitle);
        soundSearch = new EditText(this);
        soundSearch.setHint("Search your soundboard…");
        root.addView(soundSearch);
        soundboardContainer = new LinearLayout(this);
        soundboardContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(soundboardContainer);
        addButton(root, "ADD AUDIO FILE TO SOUNDBOARD", v -> {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("audio/*");
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(picker, REQ_SOUNDBOARD_FILE);
        });
        addButton(root, "SHOW FLOATING SOUNDBOARD", v -> {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                status.setText("Allow SoundPad to display over other apps, then press SHOW FLOATING SOUNDBOARD again.");
                return;
            }
            Intent overlay = new Intent(this, FloatingSoundboardService.class).setAction(FloatingSoundboardService.START);
            if (selectedAudioUri != null) overlay.putExtra(FloatingSoundboardService.EXTRA_URI, selectedAudioUri.toString());
            startService(overlay);
            status.setText("Floating controls started. Choose an audio file first for the PLAY button.");
        });
        addButton(root, "HIDE FLOATING SOUNDBOARD", v ->
            startService(new Intent(this, FloatingSoundboardService.class).setAction(FloatingSoundboardService.STOP)));
        loadSoundboard();
        soundSearch.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { renderSoundboard(s.toString()); }
            public void afterTextChanged(android.text.Editable e) {}
        });

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

    private void updateShizukuStatus() {
        try {
            if (!Shizuku.pingBinder()) {
                status.setText("Shizuku is not running. Start Shizuku and refresh status.");
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                status.setText("Shizuku permission granted. Android may still restrict privileged audio-policy operations; virtual microphone routing is not guaranteed.");
            } else {
                status.setText("Shizuku is running, but SoundPad permission has not been granted.");
            }
        } catch (Exception e) {
            status.setText("Shizuku status unavailable: " + e.getClass().getSimpleName());
        }
    }

    private void loadSoundboard() {
        soundboardSounds.clear();
        java.util.Set<String> saved = getPreferences(0).getStringSet("soundboard_uris", new java.util.HashSet<>());
        for (String value : new java.util.ArrayList<>(saved)) {
            try { soundboardSounds.add(Uri.parse(value)); } catch (Exception ignored) {}
        }
        renderSoundboard("");
    }

    private void saveSoundboard() {
        java.util.HashSet<String> values = new java.util.HashSet<>();
        for (Uri uri : soundboardSounds) values.add(uri.toString());
        getPreferences(0).edit().putStringSet("soundboard_uris", values).apply();
    }

    private void renderSoundboard(String query) {
        if (soundboardContainer == null) return;
        soundboardContainer.removeAllViews();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (Uri uri : soundboardSounds) {
            String label = uri.getLastPathSegment();
            if (label == null || label.isEmpty()) label = "Audio clip " + (shown + 1);
            if (!needle.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(needle)) continue;
            final Uri clip = uri;
            addButton(soundboardContainer, "▶ " + label, v -> playSoundboardClip(clip));
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText(needle.isEmpty() ? "No sounds yet. Add audio files to build your soundboard." : "No matching sound clips.");
            soundboardContainer.addView(empty);
        }
    }

    private void playSoundboardClip(Uri uri) {
        Intent play = new Intent(this, AudioBridgeService.class).setAction(AudioBridgeService.PLAY).setData(uri);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(play); else startService(play);
        status.setText("Playing selected sound through the phone's current audio output. This is not a system microphone feed.");
    }

    private void refreshInputDevices(java.util.ArrayList<android.media.AudioDeviceInfo> devices, java.util.ArrayList<String> labels) {
        if (Build.VERSION.SDK_INT >= 23) {
            android.media.AudioManager am = (android.media.AudioManager)getSystemService(AUDIO_SERVICE);
            if (am != null) for (android.media.AudioDeviceInfo device : am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)) {
                devices.add(device);
                labels.add(audioInputDeviceLabel(device));
            }
        }
    }

    private String audioInputDeviceLabel(android.media.AudioDeviceInfo device) {
        String type;
        switch (device.getType()) {
            case android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC: type = "Built-in microphone"; break;
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET: type = "Wired headset microphone"; break;
            case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO: type = "Bluetooth call microphone"; break;
            case android.media.AudioDeviceInfo.TYPE_USB_DEVICE:
            case android.media.AudioDeviceInfo.TYPE_USB_HEADSET: type = "USB microphone"; break;
            default: type = "Microphone input (type " + device.getType() + ")"; break;
        }
        CharSequence name = device.getProductName();
        return type + (name == null || name.length() == 0 ? "" : " — " + name);
    }

    private void startMicMonitor(final int deviceId) {
        stopMicMonitor();
        micMonitorRunning = true;
        micMonitorThread = new Thread(() -> {
            AudioRecord recorder = null;
            AudioTrack track = null;
            try {
                int inputMin = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                int outputMin = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (inputMin <= 0 || outputMin <= 0) throw new IllegalStateException("This device does not support the required audio format.");
                recorder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(Math.max(inputMin, 8192)).build();
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Could not initialize microphone.");
                if (Build.VERSION.SDK_INT >= 23 && deviceId != 0) {
                    android.media.AudioManager am = (android.media.AudioManager)getSystemService(AUDIO_SERVICE);
                    boolean found = false;
                    if (am != null) for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)) {
                        if (d.getId() == deviceId) { found = recorder.setPreferredDevice(d); break; }
                    }
                    if (!found) throw new IllegalStateException("Selected microphone is unavailable or rejected.");
                }
                track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(outputMin, 8192)).setTransferMode(AudioTrack.MODE_STREAM).build();
                micMonitorRecorder = recorder;
                micMonitorTrack = track;
                recorder.startRecording();
                track.play();
                short[] samples = new short[1024];
                runOnUiThread(() -> status.setText("Live microphone monitor active → Android system-default speaker output. Use headphones to reduce feedback."));
                while (micMonitorRunning) {
                    int n = recorder.read(samples, 0, samples.length);
                    if (n < 0) throw new IllegalStateException("Microphone read failed: " + n);
                    if (n > 0) {
                        int offset = 0;
                        while (micMonitorRunning && offset < n) {
                            int wrote = track.write(samples, offset, n - offset);
                            if (wrote < 0) throw new IllegalStateException("Speaker playback failed: " + wrote);
                            offset += wrote;
                        }
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Mic monitor stopped: " + e.getMessage()));
            } finally {
                micMonitorRunning = false;
                micMonitorRecorder = null;
                micMonitorTrack = null;
                if (recorder != null) { try { recorder.stop(); } catch (Exception ignored) {} recorder.release(); }
                if (track != null) { try { track.stop(); } catch (Exception ignored) {} track.release(); }
            }
        }, "SoundPad-mic-monitor");
        micMonitorThread.start();
    }

    private void stopMicMonitor() {
        micMonitorRunning = false;
        AudioRecord recorder = micMonitorRecorder;
        AudioTrack track = micMonitorTrack;
        if (recorder != null) { try { recorder.stop(); } catch (Exception ignored) {} }
        if (track != null) { try { track.pause(); track.flush(); } catch (Exception ignored) {} }
        status.setText("Microphone monitor stopped.");
    }

    private void testMicrophoneInput(final int deviceId) {
        status.setText("Testing microphone input for up to 3 seconds…");
        new Thread(() -> {
            AudioRecord recorder = null;
            try {
                int min = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) throw new IllegalStateException("This device does not support the requested microphone format.");
                recorder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, 8192)).build();
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Microphone could not be initialized.");
                if (Build.VERSION.SDK_INT >= 23 && deviceId != 0) {
                    android.media.AudioManager am = (android.media.AudioManager)getSystemService(AUDIO_SERVICE);
                    boolean found = false;
                    if (am != null) for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)) {
                        if (d.getId() == deviceId) { found = recorder.setPreferredDevice(d); break; }
                    }
                    if (!found) throw new IllegalStateException("Selected microphone is unavailable or Android rejected it.");
                }
                recorder.startRecording();
                short[] samples = new short[2048];
                long end = android.os.SystemClock.elapsedRealtime() + 3000;
                long total = 0;
                int reads = 0;
                while (android.os.SystemClock.elapsedRealtime() < end) {
                    int n = recorder.read(samples, 0, samples.length);
                    if (n < 0) throw new IllegalStateException("Microphone read failed: " + n);
                    for (int i = 0; i < n; i++) total += (long)samples[i] * samples[i];
                    reads += n;
                }
                final double rms = reads == 0 ? 0 : Math.sqrt((double)total / reads);
                runOnUiThread(() -> status.setText(String.format(Locale.US, "Microphone test finished. Input RMS level: %.0f / 32768. This tests recording input only; it does not route playback into another app's mic.", rms)));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Microphone test failed: " + e.getMessage()));
            } finally {
                if (recorder != null) { try { recorder.stop(); } catch (Exception ignored) {} recorder.release(); }
            }
        }, "SoundPad-mic-test").start();
    }

    private String audioDeviceLabel(android.media.AudioDeviceInfo device) {
        String type;
        switch (device.getType()) {
            case android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: type = "Phone speaker"; break;
            case android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: type = "Phone earpiece"; break;
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES: type = "Wired headphones"; break;
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET: type = "Wired headset"; break;
            case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: type = "Bluetooth audio"; break;
            case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO: type = "Bluetooth call audio"; break;
            case android.media.AudioDeviceInfo.TYPE_USB_DEVICE:
            case android.media.AudioDeviceInfo.TYPE_USB_HEADSET: type = "USB audio"; break;
            case android.media.AudioDeviceInfo.TYPE_HDMI: type = "HDMI audio"; break;
            default: type = "Audio output (type " + device.getType() + ")"; break;
        }
        CharSequence name = device.getProductName();
        return type + (name == null || name.length() == 0 ? "" : " — " + name);
    }

    private void addButton(LinearLayout parent, String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(click);
        parent.addView(b);
    }

    private File recordingFile() { return new File(getFilesDir(), "capture.pcm"); }

    private void requestScreenRecording() {
        if (hudToggle != null && hudToggle.isChecked() && Build.VERSION.SDK_INT >= 23
                && !android.provider.Settings.canDrawOverlays(this)) {
            status.setText("Allow SoundPad to display the optional recording-status overlay, then return and tap Start screen recording again.");
            Intent settings = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + getPackageName()));
            startActivity(settings);
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) { status.setText("Screen recording is not supported on this device."); return; }
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE);
    }

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
        if (request == REQ_MIC_TEST) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) testMicrophoneInput(pendingMicDeviceId);
            else status.setText("Microphone permission was not granted.");
        } else if (request == REQ_RECORD_AUDIO) {
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
        int rateResult = tts.setSpeechRate(1.0f);
        return pitchResult == TextToSpeech.SUCCESS && rateResult == TextToSpeech.SUCCESS;
    }

    // Natural, neutral TTS settings; actual voice comes from the selected Android TTS engine.
    private void applyNaturalGoogleStyle() {
        savedPitch = 1.0f;
        savedHz = 150.0f;
        getPreferences(0).edit().putFloat("voice_pitch", savedPitch).putFloat("voice_hz", savedHz).commit();
        boolean applied = applyPitchToEngine();
        status.setText(applied
            ? "Natural voice settings applied. For Google's voice, select Speech Recognition & Synthesis from Google in Android TTS settings."
            : "Natural settings saved. Enable a TTS engine in Android settings, then try again.");
    }

    private void openTtsSettings() {
        try {
            startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
        } catch (Exception e) {
            try { startActivity(new Intent("android.settings.SETTINGS")); }
            catch (Exception ignored) { status.setText("Open Android Settings > Accessibility > Text-to-speech output."); }
        }
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
        if (request == REQ_SOUNDBOARD_FILE && result == Activity.RESULT_OK && data != null) {
            try {
                Uri uri = data.getData();
                if (uri != null) {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    if (!soundboardSounds.contains(uri)) soundboardSounds.add(uri);
                }
            } catch (Exception ignored) {}
            saveSoundboard();
            renderSoundboard(soundSearch == null ? "" : soundSearch.getText().toString());
            status.setText("Sound added to your local soundboard.");
        } else if (request == REQ_AUDIO_FILE && result == Activity.RESULT_OK && data != null) {
            selectedAudioUri = data.getData();
            try { getContentResolver().takePersistableUriPermission(selectedAudioUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            status.setText("Audio file selected. Play sends it to the current phone/Bluetooth output.");
        } else if (request == REQ_AUDIO_FILE) {
            status.setText("Audio file selection cancelled.");
        } else if (request == REQ_SCREEN_CAPTURE && result == Activity.RESULT_OK && data != null) {
            int fps = fpsSpinner != null && fpsSpinner.getSelectedItemPosition() == 1 ? 120 : 60;
            Intent service = new Intent(this, ScreenRecordService.class).setAction("START")
                .putExtra("code", result).putExtra("data", data)
                .putExtra("fps", fps).putExtra("hud", hudToggle != null && hudToggle.isChecked());
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            status.setText("Screen recording started. Requested " + fps + " FPS. Android may cap the actual frame rate.");
        } else if (request == REQ_SCREEN_CAPTURE) {
            status.setText("Screen recording permission was cancelled.");
        } else if (request == REQ_CAPTURE && result == Activity.RESULT_OK && data != null) {
            Intent service = new Intent(this, CaptureService.class).setAction("START")
                .putExtra("code", result).putExtra("data", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            status.setText("Capture started. Play permitted audio in another app, then return and stop recording.");
        } else if (request == REQ_CAPTURE) status.setText("Capture permission was cancelled.");
    }

    @Override protected void onDestroy() {
        stopPlayback();
        stopMicMonitor();
        try { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener); } catch (Exception ignored) {}
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
