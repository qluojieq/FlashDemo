package com.brandon.flashdemo;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 200;

    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAITING_LOCK = 1;
    private static final int STATE_PICTURE_TAKEN = 2;

    private int mState = STATE_PREVIEW;
    private long mTriggerFrameNumber = -1; // 用于过滤陈旧帧

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private TextureView textureView;
    private ImageView ivThumbnail;
    private Button btnCapture;

    private String cameraId;
    protected CameraDevice cameraDevice;
    protected CameraCaptureSession cameraCaptureSessions;
    protected CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimension;
    private int sensorOrientation;
    private ImageReader imageReader;
    private boolean mFlashSupported;
    private Surface previewSurface;

    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;

    private final ArrayList<String> photoPaths = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);
        hideSystemUI();

        textureView = findViewById(R.id.textureView);
        ivThumbnail = findViewById(R.id.iv_thumbnail);
        btnCapture = findViewById(R.id.btn_capture);

        textureView.setSurfaceTextureListener(textureListener);
        btnCapture.setOnClickListener(v -> takePicture());
        ivThumbnail.setOnClickListener(v -> {
            if (!photoPaths.isEmpty()) {
                Intent intent = new Intent(MainActivity.this, PhotoActivity.class);
                intent.putStringArrayListExtra("photo_paths", photoPaths);
                startActivity(intent);
            }
        });
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            openCamera();
        }
        @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}
        @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) { return false; }
        @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
    };

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreview();
        }
        @Override public void onDisconnected(@NonNull CameraDevice camera) { cameraDevice.close(); }
        @Override public void onError(@NonNull CameraDevice camera, int error) {
            if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
        }
    };

    private CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        private void process(CaptureResult result) {
            switch (mState) {
                case STATE_PREVIEW: break;
                case STATE_WAITING_LOCK: {
                    // 核心：忽略触发之前的陈旧结果
                    if (mTriggerFrameNumber == -1 || result.getFrameNumber() < mTriggerFrameNumber) {
                        return;
                    }

                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    
                    boolean afReady = afState == null || 
                                     afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                                     afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED;
                                     
                    boolean aeReady = aeState == null || 
                                     aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                                     aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                                     aeState == CaptureResult.CONTROL_AE_STATE_LOCKED;

                    if (afReady && aeReady) {
                        mState = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    }
                    break;
                }
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            process(result);
        }
    };

    protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    protected void stopBackgroundThread() {
        if (mBackgroundThread != null) {
            mBackgroundThread.quitSafely();
            try { mBackgroundThread.join(); mBackgroundThread = null; mBackgroundHandler = null; } catch (InterruptedException e) { e.printStackTrace(); }
        }
    }

    private void lockFocus() {
        try {
            mTriggerFrameNumber = -1; // 重置
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            
            mState = STATE_WAITING_LOCK;
            
            // 使用一个临时的回调来捕获 Trigger 请求的帧序号
            cameraCaptureSessions.capture(captureRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    mTriggerFrameNumber = result.getFrameNumber();
                }
            }, mBackgroundHandler);
            
            // 立即重置触发器，并更新重复请求
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    protected void takePicture() {
        if (mState != STATE_PREVIEW) return;
        lockFocus();
    }

    private void captureStillPicture() {
        try {
            if (null == cameraDevice) return;
            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            if (previewSurface != null) {
                captureBuilder.addTarget(previewSurface);
            }
            
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            setAutoFlash(captureBuilder);

            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360);

            CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    unlockFocus();
                }
            };
            
            cameraCaptureSessions.capture(captureBuilder.build(), captureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private void unlockFocus() {
        try {
            // 1. 取消触发器
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
            }
            
            // 2. 强制切换 AE 模式以重置 HAL 内部状态机
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            cameraCaptureSessions.capture(captureRequestBuilder.build(), null, mBackgroundHandler);

            // 3. 恢复自动闪光和 IDLE 状态
            setAutoFlash(captureRequestBuilder);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
            
            mState = STATE_PREVIEW;
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private void updateThumbnailAsync(String pathOrUri) {
        mBackgroundHandler.post(() -> {
            try {
                Uri uri = Uri.parse(pathOrUri);
                Bitmap bitmap;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 8; // Scale down for thumbnail
                
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    bitmap = BitmapFactory.decodeStream(is, null, options);
                }
                
                if (bitmap != null) {
                    runOnUiThread(() -> ivThumbnail.setImageBitmap(bitmap));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error updating thumbnail", e);
            }
        });
    }

    protected void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            previewSurface = new Surface(texture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(previewSurface);
            setAutoFlash(captureRequestBuilder);

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    if (null == cameraDevice) return;
                    cameraCaptureSessions = cameraCaptureSession;
                    updatePreview();
                }
                @Override public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(MainActivity.this, "Configuration failed", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
            
            Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            mFlashSupported = available == null ? false : available;

            Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
            int width = 640, height = 480;
            if (jpegSizes != null && jpegSizes.length > 0) {
                width = jpegSizes[0].getWidth(); height = jpegSizes[0].getHeight();
            }
            imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(readerListener, mBackgroundHandler);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraId, stateCallback, null);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private final ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            try (Image image = reader.acquireLatestImage()) {
                if (image == null) return;
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                
                // Add watermark "flash demo"
                byte[] watermarkedBytes = addWatermark(bytes, "flash demo");
                saveToGallery(watermarkedBytes);
            } catch (IOException e) { Log.e(TAG, "Error saving image", e); }
        }
    };

    private byte[] addWatermark(byte[] bytes, String text) {
        long startTime = System.currentTimeMillis();
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        Bitmap mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(mutableBitmap);
        
        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setAlpha(180); // Semi-transparent
        paint.setTextSize(mutableBitmap.getWidth() / 20f); // Responsive text size
        paint.setAntiAlias(true);
        paint.setShadowLayer(5.0f, 2.0f, 2.0f, Color.BLACK); // Add shadow for visibility
        
        // Draw at bottom-left corner with some padding
        float padding = mutableBitmap.getWidth() / 40f;
        canvas.drawText(text, padding, mutableBitmap.getHeight() - padding, paint);
        
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream);
        
        bitmap.recycle();
        mutableBitmap.recycle();
        
        byte[] result = stream.toByteArray();
        long endTime = System.currentTimeMillis();
        Log.d(TAG, "Watermark processing took: " + (endTime - startTime) + " ms");
        return result;
    }

    private void saveToGallery(byte[] bytes) throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_" + timeStamp + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FlashDemo");
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri != null) {
            try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(bytes); }
            String uriString = uri.toString();
            runOnUiThread(() -> {
                photoPaths.add(uriString);
                updateThumbnailAsync(uriString);
                Toast.makeText(MainActivity.this, "Photo saved", Toast.LENGTH_SHORT).show();
            });
        }
    }

    protected void updatePreview() {
        if (null == cameraDevice) return;
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        setAutoFlash(captureRequestBuilder);
        try {
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private void setAutoFlash(CaptureRequest.Builder requestBuilder) {
        if (mFlashSupported) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
    }

    private void closeCamera() {
        if (null != cameraDevice) { cameraDevice.close(); cameraDevice = null; }
        if (null != imageReader) { imageReader.close(); imageReader = null; }
        if (null != previewSurface) { previewSurface.release(); previewSurface = null; }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_DENIED) {
            finish();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (textureView.isAvailable()) openCamera();
        else textureView.setSurfaceTextureListener(textureListener);
    }

    @Override protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }
}
