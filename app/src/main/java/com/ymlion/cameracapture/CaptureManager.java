package com.ymlion.cameracapture;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
import static android.hardware.camera2.CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START;
import static android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
import static android.hardware.camera2.CameraMetadata.CONTROL_AF_TRIGGER_START;
import static android.hardware.camera2.CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE;
import static android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO;

/**
 * Created by YMlion on 2017/7/26.
 */

public class CaptureManager {

    private static final String TAG = "CaptureManager";
    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 180);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 0);
    }

    static {
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0);
    }

    /**
     * Whether the current camera device supports Flash or not.
     */
    private boolean mFlashSupported;

    /**
     * Orientation of the camera sensor
     */
    private int mSensorOrientation;
    private Size mPreviewSize;
    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAITING_LOCK = 1;
    private static final int STATE_WAITING_CAPTURE = 2;
    private static final int STATE_CAPTURE = 3;
    private static final int STATE_CAPTURED = 4;
    private static final int STATE_RECORDING = 5;
    private static final int STATE_RECORDED = 6;

    /**
     * @see #STATE_PREVIEW
     * @see #STATE_WAITING_LOCK
     * @see #STATE_WAITING_CAPTURE
     * @see #STATE_CAPTURE
     * @see #STATE_CAPTURED
     * @see #STATE_RECORDING
     * @see #STATE_RECORDED
     */
    private int mState = STATE_PREVIEW;

    private static CameraManager cm;
    private Context mContext;
    private SurfaceTexture mTexture;
    private CameraDevice mCameraDevice;
    private CaptureRequest.Builder mRequestBuilder;
    private CameraCaptureSession mCaptureSession;
    private Handler mThreadHandler;
    private ImageReader mImageReader;
    private String mCameraId;
    private CaptureCallback mCaptureCallback;

    private MediaRecorder mRecorder;
    private Surface mPreviewSurface;

    private File mCurrentVideo;

    private int mFacing = 1;

    private SensorManager sm;
    private SensorEventListener mSensorListener;
    private float mSensorX;
    private float mSensorY;

    public CaptureManager(Context context, SurfaceTexture surfaceTexture) {
        this.mContext = context;
        cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.mTexture = surfaceTexture;
        HandlerThread thread = new HandlerThread("CaptureManager");
        thread.start();
        mThreadHandler = new Handler(thread.getLooper());
    }

    /**
     * 设置摄像头：前置or后置
     *
     * @param facing 0：前置；1：后置
     */
    public void setFacing(int facing) {
        mFacing = facing;
    }

    /**
     * 切换摄像头
     *
     * @param textureView preview surface
     */
    public void changeCamera(TextureView textureView) {
        mFacing = 1 - mFacing;
        close();
        open(textureView.getWidth(), textureView.getHeight());
    }

    public void open(int width, int height) {
        try {
            if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            setUpCameraOutputs(width, height);
//            setupRecord();
            cm.openCamera(mCameraId, new DeviceStateCallback(), mThreadHandler);
            Sensor gravitySensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            sm.registerListener(mSensorListener = new CameraSensorListener(), gravitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public void capture() {
        lockFocus();
    }

    private void lockFocus() {
        mRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CONTROL_AF_TRIGGER_START);
        mState = STATE_WAITING_LOCK;
        try {
            mCaptureSession.capture(mRequestBuilder.build(), mCaptureCallback, mThreadHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void preCapture() {
        mRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CONTROL_AE_PRECAPTURE_TRIGGER_START);
        mState = STATE_WAITING_CAPTURE;
        try {
            mCaptureSession.capture(mRequestBuilder.build(), mCaptureCallback, mThreadHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void captureStill() {
        mState = STATE_CAPTURED;
        try {
            CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(mImageReader.getSurface());
            int orientation = getOrientation(getRotation());
            builder.set(CaptureRequest.JPEG_ORIENTATION, orientation);
            setupRequest(builder);
//            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            mCaptureSession.stopRepeating();
            mCaptureSession.capture(builder.build(), mCaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void startRecord() {
        Log.d(TAG, "startRecord");
        mState = STATE_RECORDING;
        setupRecord();
        try {
            List<Surface> surfaces = Arrays.asList(mPreviewSurface, mImageReader.getSurface(), mRecorder.getSurface());
            mCameraDevice.createCaptureSession(surfaces, new SessionStateCallback(), mThreadHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setupRecord() {
        if (mRecorder == null) {
            mRecorder = new MediaRecorder();
            mRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            mRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            if (CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_QVGA)) {
                mRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_QVGA));
            } else {
                mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            }
            mCurrentVideo = getFile(1);
            mRecorder.setOutputFile(mCurrentVideo.getAbsolutePath());
            if (mSensorOrientation == 90) {
                mRecorder.setOrientationHint(ORIENTATIONS.get(getRotation()));
            } else if (mSensorOrientation == 270) {
                mRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(getRotation()));
            }
            try {
                mRecorder.prepare();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void stopRecord() {
        Log.d(TAG, "stopRecord");
        mState = STATE_RECORDED;
        if (mRecorder != null) {
            mRecorder.stop();
            mRecorder.reset();
            mRecorder.release();
            mRecorder = null;
        }
        try {
            List<Surface> surfaces = Arrays.asList(mPreviewSurface, mImageReader.getSurface());
            mCameraDevice.createCaptureSession(surfaces, new SessionStateCallback(), mThreadHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void close() {
        if (mRecorder != null) {
            mRecorder.reset();
            mRecorder.release();
            mRecorder = null;
            if (mCurrentVideo != null && mCurrentVideo.exists() && mCurrentVideo.length() == 0) {
                mCurrentVideo.delete();
                Log.d(TAG, "close: delete empty video file");
            }
        }
        if (mCaptureSession != null) {
            mCaptureSession.close();
        }
        if (mCameraDevice != null) {
            mCameraDevice.close();
        }
        if (mImageReader != null) {
            mImageReader.close();
        }
        sm.unregisterListener(mSensorListener);
    }


    private class DeviceStateCallback extends CameraDevice.StateCallback {

        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            try {
                mCameraDevice = camera;
                Log.d(TAG, "DeviceStateCallback onOpened: " + camera.getId());

                mTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                Log.d(TAG, "onOpened: texture size : " + mPreviewSize);

                mRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mPreviewSurface = new Surface(mTexture);
                mRequestBuilder.addTarget(mPreviewSurface);
                List<Surface> surfaces = new ArrayList<>();
                surfaces.add(mPreviewSurface);
                surfaces.add(mImageReader.getSurface());
                if (mRecorder != null) {
                    surfaces.add(mRecorder.getSurface());
                }
                camera.createCaptureSession(surfaces, new SessionStateCallback(), mThreadHandler);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.d(TAG, "DeviceStateCallback onDisconnected: " + camera.getId());
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "DeviceStateCallback onError: " + error);
        }
    }

    private class SessionStateCallback extends CameraCaptureSession.StateCallback {

        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            Log.d(TAG, "SessionStateCallback onConfigured: ");
            mCaptureSession = session;
            try {
                if (mState == STATE_RECORDING) {
                    CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    builder.addTarget(mPreviewSurface);
                    builder.addTarget(mRecorder.getSurface());
                    mCaptureSession.setRepeatingRequest(builder.build(), mCaptureCallback, null);
                    mRecorder.start();
                } else {
                    setupRequest(mRequestBuilder);
                    mCaptureCallback = new CaptureCallback();
                    session.setRepeatingRequest(mRequestBuilder.build(),
                            mCaptureCallback, mThreadHandler);
                }
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.d(TAG, "SessionStateCallback onConfigureFailed");
        }
    }

    private class CaptureCallback extends CameraCaptureSession.CaptureCallback {
        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            process(result);
        }

        private void process(CaptureResult result) {
            switch (mState) {
                case STATE_WAITING_LOCK:
                    Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (af == null) {
                        mState = STATE_CAPTURED;
                        captureStill();
                    } else if (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                            af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                        Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mState = STATE_CAPTURED;
                            captureStill();
                        } else {
                            preCapture();
                        }
                    }
                    break;
                case STATE_WAITING_CAPTURE: {
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mState = STATE_CAPTURE;
                    }
                    break;
                }
                case STATE_CAPTURE: {
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_CAPTURED;
                        captureStill();
                    }
                    break;
                }
                case STATE_CAPTURED:
                    try {
                        mState = STATE_PREVIEW;
                        mCaptureSession.setRepeatingRequest(mRequestBuilder.build(),
                                mCaptureCallback, mThreadHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                    break;
                case STATE_RECORDING:
                    break;
            }
        }
    }


    private class OnImageAvailable implements ImageReader.OnImageAvailableListener {

        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireNextImage();
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            Log.d(TAG, "onImageAvailable: " + bytes.length);
            buffer.get(bytes);
            File file = getFile(0);
            OutputStream outputStream = null;
            try {
                outputStream = new BufferedOutputStream(new FileOutputStream(file));
                outputStream.write(bytes);
                outputStream.flush();
                setBitmapLocation(file.getAbsolutePath());
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                image.close();
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * 设置图片的经纬度
     *
     * @param path 图片绝对路径
     */
    public static void setBitmapLocation(String path) {
        if (Double.compare(LocationUtil.mLatitude, 0) == 0) {
            return;
        }
        try {
            // 从指定路径下读取图片，并获取其EXIF信息
            ExifInterface exifInterface = new ExifInterface(path);

            exifInterface.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, getAltitude(LocationUtil.mAltitude));
            exifInterface.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, LocationUtil.mLongitude >= 0 ? "0" : "1");
            exifInterface.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, gpsCoordinate2DMS(LocationUtil.mLongitude));
            exifInterface.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, LocationUtil.mLongitude > 0 ? "E" : "W");
            exifInterface.setAttribute(ExifInterface.TAG_GPS_LATITUDE, gpsCoordinate2DMS(LocationUtil.mLatitude));
            exifInterface.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, LocationUtil.mLatitude > 0 ? "N" : "S");
            exifInterface.saveAttributes();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @NonNull
    private static String getAltitude(double altitude) {
        int a = (int) (altitude * 100);
        String result = a + "/100";
        Log.d(TAG, "getAltitude: " + altitude + "; result : " + result);
        return result;
    }

    /**
     * gps浮点型经纬度转换为度分秒格式
     *
     * @param coordinate 经纬度
     * @return 度分秒经纬度
     */
    public static String gpsCoordinate2DMS(double coordinate) {
        int d, m, s;
        // 获取度数
        d = (int) coordinate;
        // 获取分
        double point = coordinate - d;
        double mPoint = point * 60;
        m = (int) mPoint;
        // 获取秒的小数
        double sPoint = (mPoint - m) * 60;
        // 保留六位小数
        s = (int) (sPoint * 10000);

        String dms = d + "/1," + m + "/1," + s + "/10000";
        Log.d(TAG, "gpsCoordinate2DMS: " + dms);
        return dms;
    }

    /**
     * 生成文件名
     *
     * @param type 0: image; 1: video
     */
    @NonNull
    private File getFile(int type) {
        String fileName = DateFormat.format("yyyyMMddHHmmss", System.currentTimeMillis()).toString();
        String dirType = type == 0 ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_MOVIES;
        String fileType = type == 0 ? ".jpg" : ".mp4";
        return new File(Environment.getExternalStoragePublicDirectory(dirType).getAbsolutePath()
                + "/"
                + fileName
                + fileType);
    }


    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that
     * is at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size,
     * and whose aspect ratio matches with the specified value.
     *
     * @param choices           The list of sizes that the camera supports for the intended output
     *                          class
     * @param textureViewWidth  The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param aspectRatio       The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
                                          int textureViewHeight, Size aspectRatio) {

        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth &&
                        option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }


    /**
     * Retrieves the JPEG orientation from the specified screen rotation.
     *
     * @return The JPEG orientation (one of 0, 90, 270, and 360)
     */
    private int getOrientation(int rotation) {
        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
        // We have to take that into account and rotate JPEG properly.
        // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        return (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360;
    }

    private int getRotation() {
        int rotation;
        if (Float.compare(mSensorX, 0) <= 0 && Float.compare(mSensorX + mSensorY, 0) < 0) {
            if (Float.compare(mSensorX, mSensorY) > 0) {
                rotation = Surface.ROTATION_180;
            } else {
                rotation = Surface.ROTATION_90;
            }
        } else if (Float.compare(mSensorX, 0) > 0 && Float.compare(mSensorX - mSensorY, 0) > 0) {
            if (Float.compare(mSensorX + mSensorY, 0) > 0) {
                rotation = Surface.ROTATION_270;
            } else {
                rotation = Surface.ROTATION_180;
            }
        } else {
            rotation = Surface.ROTATION_0;
        }
        return rotation;
    }

    private void setupRequest(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CONTROL_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AF_MODE, CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        if (mFlashSupported && mState == STATE_CAPTURE) {
            builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CONTROL_AE_MODE_ON_ALWAYS_FLASH);
//            builder.set(CaptureRequest.FLASH_MODE, FLASH_MODE_TORCH);
        }
//        builder.set(CaptureRequest.CONTROL_AF_MODE, CONTROL_AF_MODE_AUTO);
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    private void setUpCameraOutputs(int width, int height) {
        try {
            for (String cameraId : cm.getCameraIdList()) {
                CameraCharacteristics characteristics
                        = cm.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing != mFacing) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                // For still image captures, we use the largest available size.
                Size largest = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                        new CompareSizesByArea());
                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                        ImageFormat.JPEG, /*maxImages*/2);
                mImageReader.setOnImageAvailableListener(
                        new OnImageAvailable(), mThreadHandler);

                Log.d(TAG, "setUpCameraOutputs: image reader size : " + largest);

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                int displayRotation = ((Activity) (mContext)).getWindowManager().getDefaultDisplay().getRotation();
                //noinspection ConstantConditions
                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                Log.d(TAG, "device orientation : " + mSensorOrientation + "; display rotation : "  + displayRotation);
                boolean swappedDimensions = false;
                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                    default:
                        Log.e(TAG, "Display rotation is invalid: " + displayRotation);
                }

                int rotatedPreviewWidth = width;
                int rotatedPreviewHeight = height;

                if (swappedDimensions) {
                    rotatedPreviewWidth = height;
                    rotatedPreviewHeight = width;
                }

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                        rotatedPreviewWidth, rotatedPreviewHeight, largest);

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                /*int orientation = mContext.getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    mTextureView.setAspectRatio(
                            mPreviewSize.getWidth(), mPreviewSize.getHeight());
                } else {
                    mTextureView.setAspectRatio(
                            mPreviewSize.getHeight(), mPreviewSize.getWidth());
                }*/

                // Check if the flash is supported.
                Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                mFlashSupported = available == null ? false : available;

                mCameraId = cameraId;
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private class CameraSensorListener implements SensorEventListener {

        @Override
        public void onSensorChanged(SensorEvent event) {
//            Log.d(TAG, "onSensorChanged: " + event.values[0] + "; " + event.values[1] + "; " + event.values[2]);
            mSensorX = event.values[0];
            mSensorY = event.values[1];
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    }

}
