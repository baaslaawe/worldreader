package info.alexbrowne.eyes;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Semaphore;

public class MainActivity extends Activity {
    private static String TAG = "MainActivity";
    private Camera mCamera;
    private CameraPreview mPreview;

    public static final int MEDIA_TYPE_IMAGE = 1;
    public static final int MEDIA_TYPE_VIDEO = 2;

    private final int CHECK_CODE = 0x1;

    private Speaker speaker;

    private boolean capturing = false;
    private Timer timer;

    private ProcessManager pm;

    private Vibrator vibrator;

    private Semaphore imageSem = new Semaphore(1);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Log.i(TAG, "onCreate");

        setContentView(R.layout.activity_main);

        Context mContext = getApplicationContext();
        this.vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        this.speaker = new Speaker(mContext);
        this.pm = new ProcessManager(speaker);
        setUpCamera();

        // Check if a TTS engine is installed
        checkTTS();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    Log.i(TAG, "Volume Up!");
                    toggleCapture();
                    // mCamera.takePicture(null, null, mPicture);
                    return true;
                } else {
                    return true;
                }
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    Log.i(TAG, "Volume Down!");
                    toggleCapture();
                    // mCamera.takePicture(null, null, mPicture);
                    return true;
                } else {
                    return true;
                }
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    public void toggleCapture() {
        if (!capturing) {
            capturing = true;
            speaker.allow(true);
            speaker.speak("Beginning capture");
            timer = new Timer();
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    try {
                        Log.d(TAG, "waiting for acquire");
                        imageSem.acquire();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (pm.isReady() && !speaker.isSpeaking()) {
                        Log.d(TAG, "READY");
                        vibrator.vibrate(100);
                        pm.setReady(false);
                        mCamera.takePicture(null, null, mPicture);
                    } else {
                        Log.d(TAG, "NOT READY");
                    }
                    Log.d(TAG, "releasing");
                    imageSem.release();
                }
            }, 0, 200);
        } else {
            timer.cancel();
            pm.cancel(true);
            capturing = false;
            speaker.allow(true);
            speaker.speak("Stopping capture");
            speaker.allow(false);
        }
    }

    private void setUpCamera() {
        // Create an instance of Camera
        mCamera = getCameraInstance();
        Camera.Parameters parameters = mCamera.getParameters();
        parameters.setJpegQuality(50);
        Camera.Size smallestSize = getSmallestPictureSize(parameters);
        Log.i(TAG, "smallestSize: " + smallestSize.width + ", " + smallestSize.height);
        parameters.setPictureSize(smallestSize.width, smallestSize.height);
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
        mCamera.setParameters(parameters);

        // Create our Preview view and set it as the content of our activity.
        mPreview = new CameraPreview(this, mCamera);
        FrameLayout preview = (FrameLayout) findViewById(R.id.camera_preview);
        preview.addView(mPreview);
        preview.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void checkTTS(){
        Intent check = new Intent();
        check.setAction(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA);
        startActivityForResult(check, CHECK_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(requestCode == CHECK_CODE){
            if(resultCode == TextToSpeech.Engine.CHECK_VOICE_DATA_PASS){
                speaker = new Speaker(this);
            }else {
                Intent install = new Intent();
                install.setAction(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                startActivity(install);
            }
        }
    }

    private Camera.Size getSmallestPictureSize(Camera.Parameters parameters) {
        Camera.Size result=null;

        for (Camera.Size size : parameters.getSupportedPictureSizes()) {
            if (result == null) {
                result=size;
            }
            else {
                int resultArea=result.width * result.height;
                int newArea=size.width * size.height;

                if (newArea < resultArea) {
                    result=size;
                }
            }
        }

        return(result);
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "onDestroy");
        super.onDestroy();
        speaker.destroy();
        pm.cancel(true);
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        Log.i(TAG, "onRestart");
        setUpCamera();
    }

    private Camera.PictureCallback mPicture = new Camera.PictureCallback() {

        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            pm.run(data);
            camera.startPreview();
        }
    };

    /** Create a File for saving an image or video */
    private static File getOutputMediaFile(int type){
        // To be safe, you should check that the SDCard is mounted
        // using Environment.getExternalStorageState() before doing this.

        File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "EyesPhotos");
        // This location works best if you want the created images to be shared
        // between applications and persist after your app has been uninstalled.

        // Create the storage directory if it does not exist
        if (! mediaStorageDir.exists()){
            if (! mediaStorageDir.mkdirs()){
                Log.d("MyCameraApp", "failed to create directory");
                return null;
            }
        }

        // Create a media file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File mediaFile;
        if (type == MEDIA_TYPE_IMAGE){
            mediaFile = new File(mediaStorageDir.getPath() + File.separator +
                    "IMG_"+ timeStamp + ".jpg");
        } else if(type == MEDIA_TYPE_VIDEO) {
            mediaFile = new File(mediaStorageDir.getPath() + File.separator +
                    "VID_"+ timeStamp + ".mp4");
        } else {
            return null;
        }

        return mediaFile;
    }

    /** A safe way to get an instance of the Camera object. */
    public static Camera getCameraInstance(){
        Camera c = null;
        try {
            c = Camera.open(); // attempt to get a Camera instance
        }
        catch (Exception e){
            Log.d(TAG, "Error getting camera instance: " + e.getMessage());
        }
        return c; // returns null if camera is unavailable
    }
}
