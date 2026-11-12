package com.alxt.SoundPad;

import android.app.*;
import android.content.Intent;
import android.graphics.PixelFormat;
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
    public static final String START="com.alxt.SoundPad.FLOAT_START", STOP="com.alxt.SoundPad.FLOAT_STOP";
    private static final String CHANNEL="soundpad_overlay";
    private WindowManager windowManager;
    private LinearLayout panel;
    private WindowManager.LayoutParams params;
    private boolean locked=true;
    private int startX,startY,touchX,touchY;

    @Override public void onCreate(){
        super.onCreate();
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager nm=getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,"SoundPad floating controls",NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder nb=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        nb.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("SoundPad floating controls").setContentText("Internal audio controls active").setOngoing(true);
        startForeground(7302,nb.build());
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){removePanel();stopForeground(true);stopSelf();return START_NOT_STICKY;}
        if(intent!=null&&MainActivity.ACTION_FLOAT_SET_LOCK.equals(intent.getAction())){
            locked=intent.getBooleanExtra("floating_panel_locked",true);
            getSharedPreferences("soundpad_audio_settings",MODE_PRIVATE).edit().putBoolean("floating_panel_locked",locked).apply();
            return START_NOT_STICKY;
        }
        if(intent!=null)locked=intent.getBooleanExtra("floating_panel_locked",
            getSharedPreferences("soundpad_audio_settings",MODE_PRIVATE).getBoolean("floating_panel_locked",true));
        if(Build.VERSION.SDK_INT>=23&&!Settings.canDrawOverlays(this)){Toast.makeText(this,"Allow SoundPad to display over other apps first.",Toast.LENGTH_LONG).show();stopForeground(true);stopSelf();return START_NOT_STICKY;}
        if(panel==null)showPanel();
        return START_STICKY;
    }
    private void showPanel(){
        windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);
        panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(8,8,8,8);panel.setBackgroundColor(0xee20242b);
        panel.addView(button("START RECORDING INTERNAL AUDIO",v->launchMain(MainActivity.ACTION_FLOAT_START_RECORD)));
        panel.addView(button("PLAY INTERNAL AUDIO RECORDING",v->launchMain(MainActivity.ACTION_FLOAT_PLAY_RECORD)));
        panel.addView(button("STOP INTERNAL AUDIO RECORDING",v->stopService(new Intent(this,CaptureService.class).setAction("STOP"))));
        LinearLayout bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.addView(button("X",v->{removePanel();stopForeground(true);stopSelf();}));
        panel.addView(bottom);
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
    private void launchMain(String action){
        Intent i=new Intent(this,MainActivity.class).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try{startActivity(i);}catch(Exception e){Toast.makeText(this,"Open SoundPad to use this control.",Toast.LENGTH_SHORT).show();}
    }
    private Button button(String label,View.OnClickListener listener){Button b=new Button(this);b.setText(label);b.setTextSize(11);b.setOnClickListener(listener);return b;}
    private void removePanel(){if(panel!=null&&windowManager!=null){try{windowManager.removeView(panel);}catch(Exception ignored){}panel=null;}}
    @Override public void onDestroy(){removePanel();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}