package com.alxt.SoundPad;

import android.app.Activity;
import android.content.Intent;
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
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 10, RATE = 44100;
    public static final String ACTION_FLOAT_START_RECORD = "com.alxt.SoundPad.FLOAT_START_RECORD";
    public static final String ACTION_FLOAT_PLAY_RECORD = "com.alxt.SoundPad.FLOAT_PLAY_RECORD";
    private android.speech.tts.TextToSpeech tts;
    private EditText words, link;
    private TextView status;
    private volatile AudioTrack playback;
    private volatile boolean playbackRunning;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,22,24,22);
        ScrollView scroll = new ScrollView(this); scroll.addView(root);
        TextView title = new TextView(this); title.setText("SoundPad"); title.setTextSize(28); root.addView(title);
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
        TextView captureInfo=new TextView(this); captureInfo.setText("Android asks for screen-capture permission. Some apps block audio capture. Recording continues in its foreground service while active."); root.addView(captureInfo);

        section(root,"Floating controls");
        button(root,"START FLOATING DISPLAY",v -> startFloating());
        button(root,"STOP FLOATING DISPLAY",v -> { stopService(new Intent(this,FloatingSoundboardService.class).setAction(FloatingSoundboardService.STOP)); status.setText("Floating display stopped."); });
        TextView floatInfo=new TextView(this); floatInfo.setText("Tap UNLOCK on the floating panel to move it, then LOCK to keep it in place. PLAY uses the last sound selected in SoundPad."); root.addView(floatInfo);

        section(root,"Open audio link");
        link=new EditText(this); link.setHint("Paste a YouTube, SoundCloud or direct audio link"); link.setSingleLine(true); root.addView(link);
        button(root,"OPEN LINK",v -> openLink());
        TextView linkInfo=new TextView(this); linkInfo.setText("Links open in the official app or browser. SoundPad does not extract or download streams from YouTube or SoundCloud."); root.addView(linkInfo);

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
        if(req==REQ_CAPTURE&&result==RESULT_OK&&data!=null){
            Intent i=new Intent(this,CaptureService.class).setAction("START").putExtra("code",result).putExtra("data",data);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
            status.setText("Internal audio recording started.");
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
                playback=track;track.play();byte[] b=new byte[16384];int n;while(playbackRunning&&(n=in.read(b))>0)track.write(b,0,n);
            }catch(Exception e){runOnUiThread(()->status.setText("Playback failed: "+e.getMessage()));}
            finally{if(track!=null){try{track.stop();}catch(Exception ignored){}track.release();}playback=null;playbackRunning=false;}
        },"SoundPad-recording-playback").start();status.setText("Playing internal audio recording.");
    }
    private void stopPlayback(){playbackRunning=false;AudioTrack t=playback;playback=null;if(t!=null){try{t.stop();}catch(Exception ignored){}t.release();}status.setText("Recording playback stopped.");}
    private void saveRecording(){
        File f=captureFile();if(!f.exists()||f.length()==0){status.setText("No recording available to save.");return;}
        try{
            ContentValuesHolder unused=null;
            android.content.ContentValues v=new android.content.ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,"SoundPad-"+System.currentTimeMillis()+".wav");v.put(MediaStore.MediaColumns.MIME_TYPE,"audio/wav");
            if(Build.VERSION.SDK_INT>=29)v.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/");
            Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(u==null)throw new IOException("Could not create download file");
            try(OutputStream out=getContentResolver().openOutputStream(u);FileInputStream in=new FileInputStream(f)){writeWavHeader(out,f.length());byte[] b=new byte[16384];int n;while((n=in.read(b))>0)out.write(b,0,n);}
            status.setText("Saved WAV recording to Downloads.");
        }catch(Exception e){status.setText("Save failed: "+e.getMessage());}
    }
    private static class ContentValuesHolder {}
    private void writeWavHeader(OutputStream out,long size)throws IOException{
        ByteBuffer h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);h.put(new byte[]{'R','I','F','F'}).putInt((int)(size+36)).put(new byte[]{'W','A','V','E','f','m','t',' '});
        h.putInt(16).putShort((short)1).putShort((short)2).putInt(RATE).putInt(RATE*4).putShort((short)4).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt((int)size);out.write(h.array());
    }
    @Override protected void onDestroy(){stopPlayback();if(tts!=null){tts.stop();tts.shutdown();}super.onDestroy();}
}
