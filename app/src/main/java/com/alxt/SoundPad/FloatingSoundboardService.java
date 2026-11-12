package com.alxt.SoundPad;

import android.app.*;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

public class FloatingSoundboardService extends Service {
    public static final String START="com.alxt.SoundPad.FLOAT_START", STOP="com.alxt.SoundPad.FLOAT_STOP", EXTRA_URI="sound_uri";
    private static final String CHANNEL="soundpad_overlay";
    private WindowManager windowManager;
    private LinearLayout panel;
    private WindowManager.LayoutParams params;
    private Uri soundUri;
    private boolean locked=true;
    private int startX,startY,touchX,touchY;

    @Override public void onCreate(){
        super.onCreate();
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager nm=getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,"SoundPad floating controls",NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder nb=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        nb.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("SoundPad floating controls")
            .setContentText("Overlay is active").setOngoing(true);
        if(Build.VERSION.SDK_INT>=29)startForeground(7302,nb.build(),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(7302,nb.build());
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        if(STOP.equals(intent.getAction())){removePanel();stopForeground(true);stopSelf();return START_NOT_STICKY;}
        if(intent.hasExtra(EXTRA_URI)){String s=intent.getStringExtra(EXTRA_URI);if(s!=null)soundUri=Uri.parse(s);}
        if(Build.VERSION.SDK_INT>=23&&!Settings.canDrawOverlays(this)){Toast.makeText(this,"Allow SoundPad to display over other apps first.",Toast.LENGTH_LONG).show();stopForeground(true);stopSelf();return START_NOT_STICKY;}
        if(panel==null)showPanel();
        return START_STICKY;
    }
    private void showPanel(){
        windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);
        panel=new LinearLayout(this);panel.setOrientation(LinearLayout.HORIZONTAL);panel.setPadding(8,8,8,8);panel.setBackgroundColor(0xee20242b);
        panel.addView(button("PLAY",v->playSound()));
        panel.addView(button("STOP",v->startService(new Intent(this,AudioBridgeService.class).setAction(AudioBridgeService.STOP))));
        panel.addView(button("UNLOCK",v->{locked=!locked;((Button)v).setText(locked?"UNLOCK":"LOCK");Toast.makeText(this,locked?"Floating panel locked":"Unlocked — drag panel to move",Toast.LENGTH_SHORT).show();}));
        panel.addView(button("X",v->{removePanel();stopForeground(true);stopSelf();}));
        int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        params=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.START;params.x=24;params.y=180;
        panel.setOnTouchListener((view,event)->{
            if(locked)return false;
            if(event.getAction()==MotionEvent.ACTION_DOWN){startX=params.x;startY=params.y;touchX=(int)event.getRawX();touchY=(int)event.getRawY();return true;}
            if(event.getAction()==MotionEvent.ACTION_MOVE){params.x=startX+(int)event.getRawX()-touchX;params.y=startY+(int)event.getRawY()-touchY;try{windowManager.updateViewLayout(panel,params);}catch(Exception ignored){}return true;}
            return event.getAction()==MotionEvent.ACTION_UP;
        });
        try{windowManager.addView(panel,params);}catch(Exception e){panel=null;stopForeground(true);stopSelf();}
    }
    private Button button(String label,View.OnClickListener listener){Button b=new Button(this);b.setText(label);b.setTextSize(11);b.setMinWidth(48);b.setOnClickListener(listener);return b;}
    private void playSound(){
        if(soundUri==null){Toast.makeText(this,"Choose a local sound in SoundPad first.",Toast.LENGTH_LONG).show();return;}
        Intent i=new Intent(this,AudioBridgeService.class).setAction(AudioBridgeService.PLAY).setData(soundUri);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
    }
    private void removePanel(){if(panel!=null&&windowManager!=null){try{windowManager.removeView(panel);}catch(Exception ignored){}panel=null;}}
    @Override public void onDestroy(){removePanel();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
