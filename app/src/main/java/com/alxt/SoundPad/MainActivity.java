package com.alxt.SoundPad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.media.*;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.widget.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Locale;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 10, REQ_AUDIO_FILE = 11, REQ_RECORD_AUDIO = 12, RATE = 44100;
    private static final String PREFS = "soundpad_audio_settings";
    private static final String PREF_MONITOR = "monitor_enabled";
    private static final String PREF_OUTPUT = "output_device_id";
    private static final String PREF_TTS_RATE = "tts_rate";
    private static final String PREF_TTS_PITCH = "tts_pitch";
    private android.speech.tts.TextToSpeech tts;
    private EditText words, link;
    private TextView status, routeStatus;
    private Switch monitorSwitch, injectionForwardSwitch;
    private SeekBar rateSeek, pitchSeek;
    private volatile AudioTrack playback;
    private volatile boolean playbackRunning;
    private Thread playbackThread;
    private SharedPreferences prefs;
    private AudioManager audioManager;
    private static final int REQ_SHIZUKU_INJECTION = 31;
    private IAudioInjectionService injectionService;
    private boolean injectionBinding;
    private final Shizuku.OnBinderReceivedListener shizukuBinderReceived = () -> {
        if (status != null) status.setText("Shizuku connected. Tap Connect to enable microphone injection.");
    };
    private final Shizuku.OnBinderDeadListener shizukuBinderDead = () -> {
        injectionService = null;
        if (status != null) status.setText("Shizuku disconnected.");
    };
    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionResult = this::onShizukuPermissionResult;
    private final ServiceConnection injectionConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            injectionService = IAudioInjectionService.Stub.asInterface(binder);
            try { status.setText(injectionService.getStatus()); }
            catch (RemoteException e) { status.setText("Shizuku service connected; status query failed."); }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            injectionService = null;
            injectionBinding = false;
            if (status != null) status.setText("Shizuku injection service disconnected.");
        }
    };
    private final Shizuku.UserServiceArgs injectionServiceArgs =
        new Shizuku.UserServiceArgs(new ComponentName(BuildConfig.APPLICATION_ID, AudioInjectionService.class.getName()))
            .daemon(false).processNameSuffix("audio-injector")
            .debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE);

    private final android.content.BroadcastReceiver captureStatusReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) {
            String message = intent.getStringExtra("message");
            if (message != null && status != null) status.setText(message);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
        android.content.IntentFilter captureFilter = new android.content.IntentFilter("com.alxt.SoundPad.CAPTURE_STATUS");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(captureStatusReceiver, captureFilter, android.content.Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(captureStatusReceiver, captureFilter);
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceived);
        Shizuku.addBinderDeadListener(shizukuBinderDead);
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResult);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,22,24,22);
        ScrollView scroll = new ScrollView(this); scroll.addView(root);
        TextView title = new TextView(this); title.setText("SoundPad 3.9"); title.setTextSize(28); root.addView(title);
        status = new TextView(this); status.setText("Ready"); root.addView(status);

        section(root,"Text to speech");
        words = new EditText(this); words.setHint("Type text here…"); words.setMinLines(3); words.setGravity(Gravity.TOP); root.addView(words);
        button(root,"SPEAK TEXT",v -> {
            String s=words.getText().toString().trim();
            if(s.isEmpty()){status.setText("Type something to speak.");return;}
            if(tts==null){status.setText("TTS engine is still starting. Try again in a moment.");return;}
            applyTtsSettings();
            tts.speak(s,android.speech.tts.TextToSpeech.QUEUE_FLUSH,null,"soundpad");
        });
        button(root,"STOP SPEAKING",v -> { if(tts!=null) tts.stop(); });
        TextView rateLabel=new TextView(this); rateLabel.setText("Speech speed"); root.addView(rateLabel);
        rateSeek=new SeekBar(this); rateSeek.setMax(35); rateSeek.setProgress(Math.max(0,Math.min(35,(settingValue(PREF_TTS_RATE)-25)/5))); root.addView(rateSeek);
        TextView pitchLabel=new TextView(this); pitchLabel.setText("Voice pitch"); root.addView(pitchLabel);
        pitchSeek=new SeekBar(this); pitchSeek.setMax(35); pitchSeek.setProgress(Math.max(0,Math.min(35,(settingValue(PREF_TTS_PITCH)-25)/5))); root.addView(pitchSeek);
        rateSeek.setOnSeekBarChangeListener(ttsSettingListener(PREF_TTS_RATE));
        pitchSeek.setOnSeekBarChangeListener(ttsSettingListener(PREF_TTS_PITCH));
        section(root,"Internal audio recording");
        button(root,"START INTERNAL AUDIO RECORDING",v -> requestCapture());
        button(root,"STOP INTERNAL AUDIO RECORDING",v -> { startService(new Intent(this,CaptureService.class).setAction("STOP")); status.setText("Stopping recording and closing the audio file…"); });
        button(root,"PLAY INTERNAL AUDIO RECORDING",v -> playRecording());
        button(root,"STOP RECORDING PLAYBACK",v -> stopPlayback());
        button(root,"SAVE RECORDING TO DOWNLOADS",v -> saveRecording());
        TextView captureInfo=new TextView(this); captureInfo.setText("Android screen-capture permission is required. Only apps that allow playback capture can be recorded."); root.addView(captureInfo);

        section(root,"Shizuku microphone injection");
        button(root,"CONNECT SHIZUKU INJECTION",v -> connectShizukuInjection());
        button(root,"STOP SHIZUKU INJECTION",v -> stopShizukuInjection());
        TextView injectionInfo=new TextView(this);
        injectionInfo.setText("Connect Shizuku first, then start internal-audio recording. Eligible captured playback is sent to Android microphone capture paths. Android 13+ required; device/game support is not guaranteed.");
        root.addView(injectionInfo);
        injectionForwardSwitch = new Switch(this);
        injectionForwardSwitch.setText("Forward captured audio to microphone (experimental)");
        injectionForwardSwitch.setChecked(prefs.getBoolean("forward_to_mic", false));
        injectionForwardSwitch.setOnCheckedChangeListener((view, checked) ->
            prefs.edit().putBoolean("forward_to_mic", checked).apply());
        root.addView(injectionForwardSwitch);

        section(root,"Open audio link");
        link=new EditText(this); link.setHint("Paste a YouTube, SoundCloud or direct audio link"); link.setSingleLine(true); root.addView(link);
        button(root,"OPEN LINK",v -> openLink());
        TextView linkInfo=new TextView(this); linkInfo.setText("Links open in the installed app or browser. SoundPad does not extract or download streams from YouTube or SoundCloud."); root.addView(linkInfo);

        setContentView(scroll);
        initTts();
    }

    private android.widget.SeekBar.OnSeekBarChangeListener ttsSettingListener(String key){
        return new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){if(fromUser){prefs.edit().putInt(key,25+progress*5).apply();applyTtsSettings(); AudioTrack active=playback; if(active!=null && key.equals(PREF_TTS_PITCH)){try{active.setPlaybackRate(Math.max(8000,Math.min(192000,(int)(RATE*(25+progress*5)/100.0f))));}catch(Exception ignored){}}}}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        };
    }

    private int settingValue(String key){
        int value=prefs.getInt(key,100);
        // Migrate SoundPad 3.7's 0.5x–2.0x values (stored as 5–20 tenths)
        // into the expanded 0.25x–2.0x percentage scale.
        if(value>0 && value<=20){value*=10;prefs.edit().putInt(key,value).apply();}
        return Math.max(25,Math.min(200,value));
    }

    private void applyTtsSettings(){
        if(tts==null)return;
        float rate=settingValue(PREF_TTS_RATE)/100.0f;
        float pitch=settingValue(PREF_TTS_PITCH)/100.0f;
        tts.setSpeechRate(rate);tts.setPitch(pitch);
    }

    private void initTts(){
        if(tts!=null){try{tts.stop();tts.shutdown();}catch(Exception ignored){}}
        tts=new android.speech.tts.TextToSpeech(this,result->runOnUiThread(()->{
            if(result==android.speech.tts.TextToSpeech.SUCCESS){
                int lang=tts.setLanguage(Locale.getDefault());
                if(lang==android.speech.tts.TextToSpeech.LANG_MISSING_DATA||lang==android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED)tts.setLanguage(Locale.US);
                applyTtsSettings();
                status.setText("Android text-to-speech ready.");
            }else status.setText("Text-to-speech could not initialize.");
        }));
    }

    private void updateRouteStatus(){
        if(routeStatus==null)return;
        int id=prefs.getInt(PREF_OUTPUT,0);
        if(id==0){routeStatus.setText("Output: Android system default");return;}
        AudioDeviceInfo d=findOutput(id);
        routeStatus.setText(d==null?"Output: saved device unavailable (system default will be used)":"Output: "+deviceName(d));
    }
    private AudioDeviceInfo findOutput(int id){
        if(audioManager==null)return null;
        for(AudioDeviceInfo d:audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(d.getId()==id)return d;
        return null;
    }
    private String deviceName(AudioDeviceInfo d){
        String name=d.getProductName()==null?"":d.getProductName().toString();
        if(name.trim().isEmpty())name=deviceType(d.getType());
        return name+" (ID "+d.getId()+")";
    }
    private String deviceType(int type){
        switch(type){
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:return "Phone speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:return "Phone earpiece";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:return "Wired headphones";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:return "Wired headset";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:return "Bluetooth audio";
            case AudioDeviceInfo.TYPE_BLE_HEADSET:return "Bluetooth LE headset";
            case AudioDeviceInfo.TYPE_USB_DEVICE:return "USB audio";
            case AudioDeviceInfo.TYPE_USB_HEADSET:return "USB headset";
            case AudioDeviceInfo.TYPE_HDMI:return "HDMI audio";
            default:return "Audio output";
        }
    }
    private ArrayList<AudioDeviceInfo> outputDevices(){
        ArrayList<AudioDeviceInfo> list=new ArrayList<>();
        if(audioManager!=null)for(AudioDeviceInfo d:audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))list.add(d);
        return list;
    }
    private void chooseOutputDevice(){
        ArrayList<AudioDeviceInfo> devices=outputDevices();
        ArrayList<String> labels=new ArrayList<>();labels.add("System default");
        for(AudioDeviceInfo d:devices)labels.add(deviceName(d));
        new AlertDialog.Builder(this).setTitle("Choose audio output").setItems(labels.toArray(new String[0]),(dialog,which)->{
            int id=which==0?0:devices.get(which-1).getId();
            prefs.edit().putInt(PREF_OUTPUT,id).apply();
            applyOutputRoute(id);
            status.setText("Output route updated.");updateRouteStatus();
        }).setNegativeButton("Cancel",null).show();
    }
    private void cycleOutputDevice(){
        ArrayList<AudioDeviceInfo> devices=outputDevices();
        if(devices.isEmpty()){status.setText("No selectable output devices were reported by Android.");return;}
        int current=prefs.getInt(PREF_OUTPUT,0),next=0;
        if(current==0)next=devices.get(0).getId();
        else{
            int index=-1;for(int i=0;i<devices.size();i++)if(devices.get(i).getId()==current)index=i;
            next=index<0||index+1>=devices.size()?0:devices.get(index+1).getId();
        }
        prefs.edit().putInt(PREF_OUTPUT,next).apply();
        applyOutputRoute(next);
        updateRouteStatus();status.setText("Output route switched.");
    }
    private void applyOutputRoute(int id){
        sendCaptureSetting("SET_OUTPUT_DEVICE","output_device_id",id);
        Intent bridge=new Intent(this,AudioBridgeService.class).setAction(AudioBridgeService.SET_OUTPUT_DEVICE).putExtra(AudioBridgeService.EXTRA_OUTPUT_DEVICE_ID,id);
        try{startService(bridge);}catch(Exception ignored){}
    }
    private void sendCaptureSetting(String action,String key,boolean value){
        Intent i=new Intent(this,CaptureService.class).setAction(action).putExtra(key,value);
        try{startService(i);}catch(Exception ignored){}
    }
    private void sendCaptureSetting(String action,String key,int value){
        Intent i=new Intent(this,CaptureService.class).setAction(action).putExtra(key,value);
        try{startService(i);}catch(Exception ignored){}
    }
    private void pickAudioFile(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("audio/*");
        startActivityForResult(i,REQ_AUDIO_FILE);
    }
    private void section(LinearLayout root,String value){TextView t=new TextView(this);t.setText("\n"+value);t.setTextSize(21);t.setPadding(0,18,0,8);root.addView(t);}
    private void button(LinearLayout root,String label,android.view.View.OnClickListener click){Button b=new Button(this);b.setText(label);root.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(click);}
    private void openLink(){
        String s=link.getText().toString().trim();if(s.isEmpty()){status.setText("Paste a link first.");return;}
        Uri u=Uri.parse(s);String scheme=u.getScheme();
        if(!"http".equalsIgnoreCase(scheme)&&!"https".equalsIgnoreCase(scheme)){status.setText("Use a full http or https link.");return;}
        try{startActivity(new Intent(Intent.ACTION_VIEW,u));status.setText("Opened link in its app or browser.");}
        catch(Exception e){status.setText("No app can open this link.");}
    }
    // Restored from the Nov 8 working capture flow: Android requires RECORD_AUDIO
    // permission in addition to the user's MediaProjection approval.
    private void connectShizukuInjection() {
        if (Build.VERSION.SDK_INT < 33) {
            status.setText("Microphone injection requires Android 13 or newer.");
            return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                status.setText("Start Shizuku first, then connect again.");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQ_SHIZUKU_INJECTION);
                status.setText("Waiting for Shizuku permission.");
                return;
            }
            bindInjectionService();
        } catch (Throwable error) {
            status.setText("Shizuku unavailable: " + error.getClass().getSimpleName());
        }
    }

    private void onShizukuPermissionResult(int requestCode, int grantResult) {
        if (requestCode != REQ_SHIZUKU_INJECTION) return;
        if (grantResult == PackageManager.PERMISSION_GRANTED) bindInjectionService();
        else status.setText("Shizuku permission was denied.");
    }

    private void bindInjectionService() {
        if (injectionBinding) {
            status.setText("Shizuku injection service is already connecting.");
            return;
        }
        try {
            Shizuku.bindUserService(injectionServiceArgs, injectionConnection);
            injectionBinding = true;
            status.setText("Starting privileged audio injection service…");
        } catch (Throwable error) {
            status.setText("Could not start Shizuku service: " + error.getClass().getSimpleName());
        }
    }

    private void stopShizukuInjection() {
        try {
            Intent stop = new Intent(this, CaptureService.class).setAction("STOP_INJECTION");
            startService(stop);
            if (injectionService != null) injectionService.stopInjection();
            if (injectionBinding) Shizuku.unbindUserService(injectionServiceArgs, injectionConnection, true);
        } catch (Throwable ignored) {}
        injectionService = null;
        injectionBinding = false;
        status.setText("Shizuku microphone injection stopped.");
    }

    private void requestCapture(){
        if(Build.VERSION.SDK_INT<29){status.setText("Internal audio capture requires Android 10 or newer.");return;}
        if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},REQ_RECORD_AUDIO);
            return;
        }
        launchCaptureConsent();
    }
    private void launchCaptureConsent(){
        MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        if(m==null){status.setText("This Android device does not provide screen/audio capture.");return;}
        startActivityForResult(m.createScreenCaptureIntent(),REQ_CAPTURE);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==REQ_RECORD_AUDIO){
            if(results.length>0 && results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED) launchCaptureConsent();
            else status.setText("Microphone permission is required by Android for internal playback capture.");
        }
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==REQ_AUDIO_FILE&&result==RESULT_OK&&data!=null){
            Uri uri=data.getData();
            try{
                getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent play=new Intent(this,AudioBridgeService.class).setAction(AudioBridgeService.PLAY).setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(AudioBridgeService.EXTRA_OUTPUT_DEVICE_ID,prefs.getInt(PREF_OUTPUT,0));
                if(Build.VERSION.SDK_INT>=26)startForegroundService(play);else startService(play);
                status.setText("Opening audio file through selected output.");
            }catch(Exception e){status.setText("Could not open selected audio: "+e.getMessage());}
        }else if(req==REQ_CAPTURE&&result==RESULT_OK&&data!=null){
            Intent i=new Intent(this,CaptureService.class).setAction("START").putExtra("code",result).putExtra("data",data);
            ParcelFileDescriptor capturePipeWriter = null;
            boolean injectionStarted = false;
            if (injectionService != null && injectionForwardSwitch != null && injectionForwardSwitch.isChecked()) {
                ParcelFileDescriptor[] pipe = null;
                try {
                    pipe = ParcelFileDescriptor.createPipe();
                    String injectionResult = injectionService.startInjection(pipe[0]);
                    try { pipe[0].close(); } catch (Exception ignored) {}
                    if (injectionResult != null && injectionResult.startsWith("OK:")) {
                        i.putExtra("inject_pipe", pipe[1]);
                        capturePipeWriter = pipe[1];
                        injectionStarted = true;
                    } else {
                        try { pipe[0].close(); } catch (Exception ignored) {}
                        try { pipe[1].close(); } catch (Exception ignored) {}
                        status.setText(injectionResult == null ? "Injection setup failed." : injectionResult);
                    }
                } catch (Throwable error) {
                    if (pipe != null) {
                        try { pipe[0].close(); } catch (Exception ignored) {}
                        try { pipe[1].close(); } catch (Exception ignored) {}
                    }
                    status.setText("Injection unavailable: " + error.getClass().getSimpleName());
                }
            }
            try {
                if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
            } finally {
                if (capturePipeWriter != null) try { capturePipeWriter.close(); } catch (Exception ignored) {}
            }
            status.setText(injectionStarted ? "Internal audio capture and microphone injection started." : "Internal audio capture started; microphone injection is not active.");
        }else if(req==REQ_CAPTURE)status.setText("Recording permission was cancelled.");
    }
    private File captureFile(){return new File(getFilesDir(),"capture.pcm");}
    // Playback implementation restored from the Nov 8 working recording build.
    private void playRecording(){
        final File file=captureFile();
        if(!file.exists()||file.length()<4){
            status.setText("No recording file found yet. Record internal audio and press Stop recording first.");
            return;
        }
        stopPlayback();
        playbackRunning=true;
        playbackThread=new Thread(()->{
            AudioTrack track=null;
            try(FileInputStream in=new FileInputStream(file)){
                int min=AudioTrack.getMinBufferSize(RATE,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
                int bufferSize=Math.max(min,16384);
                track=new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(bufferSize).setTransferMode(AudioTrack.MODE_STREAM).build();
                if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IOException("Audio output could not initialize");
                AudioDeviceInfo preferred=findOutput(prefs.getInt(PREF_OUTPUT,0));
                if(preferred!=null)track.setPreferredDevice(preferred);
                playback=track;
                int pitchPercent=settingValue(PREF_TTS_PITCH);
                track.setPlaybackRate(Math.max(8000,Math.min(192000,(int)(RATE*pitchPercent/100.0f))));
                track.play();
                byte[] buffer=new byte[8192];
                int n;
                while(playbackRunning&&(n=in.read(buffer))!=-1){
                    int offset=0;
                    while(playbackRunning&&offset<n){
                        int wrote=track.write(buffer,offset,n-offset);
                        if(wrote<0)throw new IOException("AudioTrack write error "+wrote);
                        if(wrote==0)continue;
                        offset+=wrote;
                    }
                }
                runOnUiThread(()->status.setText("Recording playback finished."));
            }catch(Exception e){
                runOnUiThread(()->status.setText("Playback failed: "+e.getClass().getSimpleName()+": "+e.getMessage()));
            }finally{
                playbackRunning=false;
                playback=null;
                if(track!=null){try{track.stop();}catch(Exception ignored){}track.release();}
            }
        },"SoundPad-playback");
        playbackThread.start();
        status.setText("Playing saved internal audio…");
    }
    private void stopPlayback(){playbackRunning=false;AudioTrack t=playback;if(t!=null){try{t.pause();t.flush();}catch(Exception ignored){}}status.setText("Recording playback stopped.");}
    private void saveRecording(){
        File f=captureFile();if(!f.exists()||f.length()==0){status.setText("No recording available to save.");return;}
        try{
            android.content.ContentValues v=new android.content.ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,"SoundPad-"+System.currentTimeMillis()+".wav");v.put(MediaStore.MediaColumns.MIME_TYPE,"audio/wav");
            if(Build.VERSION.SDK_INT>=29)v.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/");
            Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(u==null)throw new IOException("Could not create download file");
            try(OutputStream out=getContentResolver().openOutputStream(u);FileInputStream in=new FileInputStream(f)){writeWavHeader(out,f.length());byte[] b=new byte[16384];int n;while((n=in.read(b))>0)out.write(b,0,n);}
            status.setText("Saved WAV recording to Downloads.");
        }catch(Exception e){status.setText("Save failed: "+e.getMessage());}
    }
    private void writeWavHeader(OutputStream out,long size)throws IOException{
        ByteBuffer h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);h.put(new byte[]{'R','I','F','F'}).putInt((int)(size+36)).put(new byte[]{'W','A','V','E','f','m','t',' '});
        h.putInt(16).putShort((short)1).putShort((short)2).putInt(RATE).putInt(RATE*4).putShort((short)4).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt((int)size);out.write(h.array());
    }
    @Override protected void onDestroy(){
        try { unregisterReceiver(captureStatusReceiver); } catch (Exception ignored) {}
        Shizuku.removeBinderReceivedListener(shizukuBinderReceived);
        Shizuku.removeBinderDeadListener(shizukuBinderDead);
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResult);
        stopPlayback();
        if(tts!=null){tts.stop();tts.shutdown();}
        super.onDestroy();
    }
}