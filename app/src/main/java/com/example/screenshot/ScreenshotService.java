package com.example.screenshot; /***
 Copyright (c) 2015 CommonsWare, LLC
 Licensed under the Apache License, Version 2.0 (the "License"); you may not
 use this file except in compliance with the License. You may obtain a copy
 of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required
 by applicable law or agreed to in writing, software distributed under the
 License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 OF ANY KIND, either express or implied. See the License for the specific
 language governing permissions and limitations under the License.

 Covered in detail in the book _The Busy Coder's Guide to Android Development_
 https://commonsware.com/Android
 */


import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.media.ToneGenerator;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import com.example.screenshot.ImageTransmogrifier;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScreenshotService extends Service {
    private WindowManager mWindowManager;
    private ImageView image;

    private static final String CHANNEL_WHATEVER="channel_whatever";
    private static final int NOTIFY_ID=9906;
    static final String EXTRA_RESULT_CODE="resultCode";
    static final String EXTRA_RESULT_INTENT="resultIntent";
    static final String ACTION_RECORD= "SS_RECORD";
    static final String ACTION_SHUTDOWN="SS_SHUTDOWN";
    static final int VIRT_DISPLAY_FLAGS=
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY |
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    private MediaProjection projection;
    private VirtualDisplay vdisplay;
    final private HandlerThread handlerThread=
            new HandlerThread(getClass().getSimpleName(),
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
    private Handler handler;
    private MediaProjectionManager mgr;
    private WindowManager wmgr;
    private ImageTransmogrifier it;
    private int resultCode;
    private Intent resultData;
    final private ToneGenerator beeper=
            new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);

    @Override
    public void onCreate() {
        super.onCreate();

        mgr=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        wmgr=(WindowManager)getSystemService(WINDOW_SERVICE);

        handlerThread.start();
        handler=new Handler(handlerThread.getLooper());
        makeView();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (i.getAction()==null) {
            resultCode=i.getIntExtra(EXTRA_RESULT_CODE, 1337);
            resultData=i.getParcelableExtra(EXTRA_RESULT_INTENT);
            foregroundify();
        }
        else if (ACTION_RECORD.equals(i.getAction())) {
            if (resultData!=null) {
                startCapture();
            }
            else {
                Intent ui=
                        new Intent(this, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                startActivity(ui);
            }
        }
        else if (ACTION_SHUTDOWN.equals(i.getAction())) {
            beeper.startTone(ToneGenerator.TONE_PROP_NACK);
            stopForeground(true);
            stopSelf();
        }

        return(START_NOT_STICKY);
    }

    @Override
    public void onDestroy() {
        stopCapture();

        super.onDestroy();
    }
    @SuppressLint("ClickableViewAccessibility")
    void makeView(){
        image = new ImageView(this);

        image.setImageResource(R.mipmap.ic_launcher);

        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        final WindowManager.LayoutParams paramsF;
        paramsF = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        paramsF.gravity = Gravity.TOP | Gravity.LEFT;
        paramsF.x = 0;
        paramsF.y = 100;
        mWindowManager.addView(image, paramsF);
        try {
            image.setOnClickListener(v -> {
                long startTime = System.nanoTime();
                startCapture();
                long endTime = System.nanoTime();
                long duration = (endTime - startTime);  // compute the duration in nanoseconds
                double durationInSeconds = (double) duration / 1_000_000_000;  // convert to seconds

                Toast.makeText(this, "Execution time: " + durationInSeconds + " seconds", Toast.LENGTH_LONG).show();
            });
            image.setOnTouchListener(new View.OnTouchListener() {
                WindowManager.LayoutParams paramsT = paramsF;
                private int initialX;
                private int initialY;
                private float initialTouchX;
                private float initialTouchY;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            initialX = paramsF.x;
                            initialY = paramsF.y;
                            initialTouchX = event.getRawX();
                            initialTouchY = event.getRawY();
                            break;
                        case MotionEvent.ACTION_UP:
                            break;
                        case MotionEvent.ACTION_MOVE:
                            paramsF.x = initialX + (int) (event.getRawX() - initialTouchX);
                            paramsF.y = initialY + (int) (event.getRawY() - initialTouchY);
                            mWindowManager.updateViewLayout(v, paramsF);
                            break;
                    }
                    return false;
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        throw new IllegalStateException("Binding not supported. Go away.");
    }

    public WindowManager getWindowManager() {
        return(wmgr);
    }

    Handler getHandler() {
        return(handler);
    }

    void processImage(final byte[] png) {
        new Thread() {
            @Override
            public void run() {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
                String timestamp = sdf.format(new Date());
                File output = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "screenshot_" + timestamp + ".png");
                try {
                    FileOutputStream fos=new FileOutputStream(output);

                    fos.write(png);
                    fos.flush();
                    fos.getFD().sync();
                    fos.close();

                    MediaScannerConnection.scanFile(ScreenshotService.this,
                            new String[] {output.getAbsolutePath()},
                            new String[] {"image/png"},
                            null);
                }
                catch (Exception e) {
                    Log.e(getClass().getSimpleName(), "Exception writing out screenshot", e);
                }
            }
        }.start();

        beeper.startTone(ToneGenerator.TONE_PROP_ACK);
        stopCapture();
    }

    private void stopCapture() {
        if (projection!=null) {
            projection.stop();
            vdisplay.release();
            projection=null;
        }
    }

    private void startCapture() {
        projection=mgr.getMediaProjection(resultCode, resultData);
        it=new ImageTransmogrifier(this);

        MediaProjection.Callback cb=new MediaProjection.Callback() {
            @Override
            public void onStop() {
                vdisplay.release();
            }
        };

        vdisplay=projection.createVirtualDisplay("andshooter",
                it.getWidth(), it.getHeight(),
                getResources().getDisplayMetrics().densityDpi,
                VIRT_DISPLAY_FLAGS, it.getSurface(), null, handler);
        projection.registerCallback(cb, handler);
    }

    @SuppressLint("ForegroundServiceType")
    private void foregroundify() {
        NotificationManager mgr=
                (NotificationManager)getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.O &&
                mgr.getNotificationChannel(CHANNEL_WHATEVER)==null) {
            mgr.createNotificationChannel(new NotificationChannel(CHANNEL_WHATEVER,
                    "Whatever", NotificationManager.IMPORTANCE_DEFAULT));
        }

        NotificationCompat.Builder b=
                new NotificationCompat.Builder(this, CHANNEL_WHATEVER);

        b.setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL);

        b.setContentTitle(getString(R.string.app_name))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setTicker(getString(R.string.app_name));

        b.addAction(R.drawable.ic_launcher_background,
                getString(R.string.start_recording),
                buildPendingIntent(ACTION_RECORD));

        b.addAction(R.drawable.ic_launcher_background,
                getString(R.string.stop_recording),
                buildPendingIntent(ACTION_SHUTDOWN));

        startForeground(NOTIFY_ID, b.build());
    }

    private PendingIntent buildPendingIntent(String action) {
        Intent i=new Intent(this, getClass());

        i.setAction(action);

        return(PendingIntent.getService(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
    }
}