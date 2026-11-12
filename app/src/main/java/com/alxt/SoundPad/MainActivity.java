package com.alxt.SoundPad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.*;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.widget.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 10, REQ_AUDIO_FILE = 11, RATE = 44100;
    private static final String PREFS = "soundpad_audio_settings";
    private static final String PREF_MONITOR = "monitor_enabled";
    private static final String PREF_OUTPUT = "output_device_id";
    public static final String ACTION_FLOAT_START_RECORD = "com.alxt.SoundPad.FLOAT_START_RECORD";
    public static final String ACTION_FLOAT_PLAY_RECORD = "com.alxt.SoundPad.FLOAT_PLAY_RECORD";
    private android.speech.tts.TextToSpeech tts;
    private EditText words, link;
    private TextView status, routeStatus;
    private Switch monitorSwitch;
    private volatile AudioTrack playback;
    private volatile boolean playbackRunning;
    private SharedPreferences prefs;
    private AudioManager audioManager;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,22,24,22);
        ScrollView scroll = new ScrollView(this); scroll.addView(root);
        TextView title = new TextView(this); title.setText("SoundPad 3.1"); title.setTextSize(28); root.addView(title);
        status = new TextView(this); status.setText("Ready"); root.addView(status);

        section(root,"Text to speech");
        words = new EditText(this); words.setHint("Type text here…"); words.setMinLines(3); words.setGravity(Gravity.TOP); root.addView(words);
        button(root,"SPEAK TEXT",v -> { String s=words.getText().toString().trim(); if(s.isEmpty()){status.setText("Type something to speak.");return;} if(tts!=null) tts.speak(s,android.speech.tts.TextToSpeech.QUEUE_FLUSH,null,"soundpad"); });
        button(root,"STOP SPEAKING",v -> { if(tts!=null) tts.stop(); });

        section(root,"Internal audio recording");
        button(root,"START INTERNAL AUDIO RECORDING",v -> requestCapture());
        button(root,"STOP INTERNAL AUDIO RECORDING",v -> { stopService(new Intent(this,CaptureService.class).setAction("STOP")); status.setText("Stopped internal audio recording."); });
        button(root,"PLAY INTERNAL AUDIO RECORDING",v -> playRecording());
        button(root,"STOP RECORDING PLAYBACK",v -> stopPlayback());
        button(root,"SAVE RECORDING TO DOWNLOADS",v -> saveRecording());
        TextView captureInfo=new TextView(this); captureInfo.setText("Android screen-capture permission is required. Only apps that allow playback capture can be recorded."); root.addView(captureInfo);

        section(root,"Audio relay & routing settings");
        routeStatus=new TextView(this); root.addView(routeStatus); updateRouteStatus();
        monitorSwitch=new Switch(this); monitorSwitch.setText("Monitor captured audio through selected output"); monitorSwitch.setChecked(prefs.getBoolean(PREF_MONITOR,true)); root.addView(monitorSwitch);
        monitorSwitch.setOnCheckedChangeListener((button,checked)->{
            prefs.edit().putBoolean(PREF_MONITOR,checked).apply();
            sendCaptureSetting("SET_MONITOR", "monitor_enabled", checked);
            status.setText(checked?"Live audio monitoring enabled.":"Live audio monitoring disabled.");
        });
        button(root,"CHOOSE AUDIO OUTPUT DEVICE",v->chooseOutputDevice());
        button(root,"SWAP OUTPUT DEVICE",v->cycleOutputDevice());
        button(root,"RESET TO SYSTEM AUDIO ROUTING",v->{
            prefs.edit().putInt(PREF_OUTPUT,0).apply();
            applyOutputRoute(0);
            status.setText("Using Android system-default output.");
            updateRouteStatus();
        });
        button(root,"PLAY AN AUDIO FILE THROUGH SELECTED OUTPUT",v->pickAudioFile());
        TextView routeInfo=new TextView(this);
        routeInfo.setText("Input source: Android internal app playback capture (MediaProjection). Output selection applies to SoundPad monitoring and SoundPad file playback. Android does not let an ordinary app replace PUBG's microphone input.");
        root.addView(routeInfo);

        section(root,"Floating controls");
        button(root,"START FLOATING DISPLAY",v -> startFloating());
        button(root,"STOP FLOATING DISPLAY",v -> { stopService(new Intent(this,FloatingSoundboardService.class).setAction(FloatingSoundboardService.STOP)); status.setText("Floating display stopped."); });
        TextView floatInfo=new TextView(this); floatInfo.setText("Use UNLOCK to move the panel, LOCK to keep it in place, and X to close it."); root.addView(floatInfo);

        section(root,"Open audio link");
        link=new EditText(this); link.setHint("Paste a YouTube, SoundCloud or direct audio link"); link.setSingleLine(true); root.addView(link);
        button(root,"OPEN LINK",v -> openLink());
        TextView linkInfo=new TextView(this); linkInfo.setText("Links open in the installed app or browser. SoundPad does not extract or download streams from YouTube or SoundCloud."); root.addView(linkInfo);

        setContentView(scroll);
        handleFloatingAction(getIntent());
        tts=new android.speech.tts.TextToSpeech(this,result -> runOnUiThread(() -> {
            if(result==android.speech.tts.TextToSpeech.SUCCESS && tts!=null) {
                int lang=tts.setLanguage(Locale.getDefault());
                if(lang==android.speech.tts.TextToSpeech.LANG_MISSING_DATA || lang==android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US);
                status.setText("Text-to-speech ready.");
            } else status.setText("Text-to-speech could not initialize.");
        }));
    }

    private void updateRouteStatus(){
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
    private void handleFloatingAction(Intent intent){ if(intent==null)return; String a=intent.getAction(); if(ACTION_FLOAT_START_RECORD.equals(a)){ requestCapture(); } else if(ACTION_FLOAT_PLAY_RECORD.equals(a)){ playRecording(); } }
    @Override protected void onNewIntent(Intent intent){ super.onNewIntent(intent); setIntent(intent); handleFloatingAction(intent); }
    private void section(LinearLayout root,String value){TextView t=new TextView(this);t.setText("\n"+value);t.setTextSize(21);t.setPadding(0,18,0,8);root.addView(t);}
    private void button(LinearLayout root,String label,android.view.View.OnClickListener click){Button b=new Button(this);b.setText(label);root.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(click);}
    private void startFloating(){
        if(Build.VERSION.SDK_INT>=23 && !android.provider.Settings.canDrawOverlays(this)){
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));
            status.setText("Allow display over other apps, then tap START FLOATING DISPLAY again.");return;
        }
        Intent i=new Intent(this,FloatingSoundboardService.class).setAction(FloatingSoundboardService.START);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
        status.setText("Floating controls started.");
    }
    private void openLink(){
        String s=link.getText().toString().trim();if(s.isEmpty()){status.setText("Paste a link first.");return;}
        Uri u=Uri.parse(s);String scheme=u.getScheme();
        if(!"http".equalsIgnoreCase(scheme)&&!"https".equalsIgnoreCase(scheme)){status.setText("Use a full http or https link.");return;}
        try{startActivity(new Intent(Intent.ACTION_VIEW,u));status.setText("Opened link in its app or browser.");}
        catch(Exception e){status.setText("No app can open this link.");}
    }
    private void requestCapture(){
        if(Build.VERSION.SDK_INT<29){status.setText("Internal audio capture requires Android 10 or newer.");return;}
        MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(),REQ_CAPTURE);
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
            if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
            status.setText("Internal audio capture started.");
        }else if(req==REQ_CAPTURE)status.setText("Recording permission was cancelled.");
    }
    private File captureFile(){return new File(getFilesDir(),"capture.pcm");}
    private void playRecording(){
        stopPlayback();File f=captureFile();if(!f.exists()||f.length()==0){status.setText("No recording found. Record and stop first.");return;}
        playbackRunning=true;
        new Thread(()->{
            int min=AudioTrack.getMinBufferSize(RATE,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);AudioTrack track=null;
            try(FileInputStream in=new FileInputStream(f)){
                track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(min*2,32768)).setTransferMode(AudioTrack.MODE_STREAM).build();
                int outputId=prefs.getInt(PREF_OUTPUT,0);AudioDeviceInfo preferred=findOutput(outputId);if(preferred!=null)track.setPreferredDevice(preferred);
                playback=track;track.play();byte[] b=new byte[16384];int n;while(playbackRunning&&(n=in.read(b))>0)track.write(b,0,n);
            }catch(Exception e){runOnUiThread(()->status.setText("Playback failed: "+e.getMessage()));}
            finally{if(track!=null){try{track.stop();}catch(Exception ignored){}track.release();}playback=null;playbackRunning=false;}
        },"SoundPad-recording-playback").start();status.setText("Playing internal audio recording.");
    }
    private void stopPlayback(){playbackRunning=false;AudioTrack t=playback;playback=null;if(t!=null){try{t.stop();}catch(Exception ignored){}t.release();}status.setText("Recording playback stopped.");}
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
    @Override protected void onDestroy(){stopPlayback();if(tts!=null){tts.stop();tts.shutdown();}super.onDestroy();}
}