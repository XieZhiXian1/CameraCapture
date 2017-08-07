package com.ymlion.cameracapture;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;

// TODO: 2017/7/28  flash控制；视频压缩
public class MainActivity extends AppCompatActivity {

    private CaptureManager rm;
    private TextureView textureView;
    private TextView secondsTv;
    private CountDownTimer timer;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initView();
        LocationUtil.getLocation(this);
    }

    @Override protected void onResume() {
        super.onResume();
        Log.d("MAIN", "onResume: ");
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_DENIED) {
                requestPermissions(new String[] {
                    Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA
                }, 0);
            }
        }
    }

    private void openCamera(int width, int height) {
        rm = new CaptureManager(this, textureView.getSurfaceTexture());
        Log.d("MAIN", "openCamera: texture view size : "
            + textureView.getWidth()
            + " ; "
            + textureView.getHeight());
        rm.open(width, height);
    }

    private void initView() {
        secondsTv = (TextView) findViewById(R.id.record_seconds_tv);
        textureView = (TextureView) findViewById(R.id.textureView);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                Log.d("MAIN", "onSurfaceTextureAvailable: " + width + "; " + height);
                if (Build.VERSION.SDK_INT >= 23) {
                    if (checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                        openCamera(width, height);
                    }
                } else {
                    openCamera(width, height);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                Log.e("MAIN", "onSurfaceTextureSizeChanged: " + width + "; " + height);
            }

            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return false;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
        @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 0) {
            for (int grantResult : grantResults) {
                if (grantResult == PackageManager.PERMISSION_DENIED) {
                    finish();
                    return;
                }
            }
            openCamera(textureView.getWidth(), textureView.getHeight());
        }
    }

    @Override protected void onStop() {
        super.onStop();
        if (rm != null) {
            rm.close();
        }
    }

    public void stopRecord(View view) {
        rm.stopRecord();
        timer.cancel();
        secondsTv.setVisibility(View.GONE);
    }

    public void recordVideo(View view) {
        secondsTv.setVisibility(View.VISIBLE);
        timer = new CountDownTimer(60000, 100) {
            @Override public void onTick(long millisUntilFinished) {
                float time = (60000 - millisUntilFinished) / 1000.F;
                secondsTv.setText(String.format("%.1fs", time));
            }

            @Override public void onFinish() {

            }
        };
        timer.start();
        rm.startRecord();
    }

    public void capturePhoto(View view) {
        if (rm != null) {
            rm.capture();
        }
    }

    public void changeCamera(View view) {
        if (rm != null) {
            rm.changeCamera(textureView);
        }
    }
}
