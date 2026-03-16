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
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
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
import android.widget.Switch;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
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
    private long mTriggerFrameNumber = -1;

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
    private Switch swFormat;

    private String cameraId;
    protected CameraDevice cameraDevice;
    protected CameraCaptureSession cameraCaptureSessions;
    protected CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimension;
    private int sensorOrientation;
    private ImageReader imageReader;
    private boolean mFlashSupported;
    private Surface previewSurface;
    private boolean isYuvMode = false;

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
        swFormat = findViewById(R.id.sw_format);

        textureView.setSurfaceTextureListener(textureListener);
        btnCapture.setOnClickListener(v -> takePicture());
        ivThumbnail.setOnClickListener(v -> {
            if (!photoPaths.isEmpty()) {
                Intent intent = new Intent(MainActivity.this, PhotoActivity.class);
                intent.putStringArrayListExtra("photo_paths", photoPaths);
                startActivity(intent);
            }
        });

        swFormat.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isYuvMode = isChecked;
            reopenCamera();
        });
    }

    private void reopenCamera() {
        if (cameraDevice != null) {
            closeCamera();
            openCamera();
        }
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
        @Override public void onDisconnected(@NonNull CameraDevice camera) { cameraDevice.close(); cameraDevice = null; }
        @Override public void onError(@NonNull CameraDevice camera, int error) {
            if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
        }
    };

    private CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        private void process(CaptureResult result) {
            switch (mState) {
                case STATE_PREVIEW: break;
                case STATE_WAITING_LOCK: {
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
            mTriggerFrameNumber = -1;
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            
            mState = STATE_WAITING_LOCK;
            
            cameraCaptureSessions.capture(captureRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    mTriggerFrameNumber = result.getFrameNumber();
                }
            }, mBackgroundHandler);
            
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

            // 核心修复：锁定 AE 确保连拍过程中闪光灯和曝光参数保持一致
            captureBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true);
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, 0);

            if (isYuvMode) {
                // YUV 模式：连拍 3 张
                List<CaptureRequest> burstRequests = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    burstRequests.add(captureBuilder.build());
                }
                cameraCaptureSessions.captureBurst(burstRequests, new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureSequenceCompleted(@NonNull CameraCaptureSession session, int sequenceId, long frameNumber) {
                        unlockFocus();
                    }
                }, mBackgroundHandler);
            } else {
                // JPEG 模式：单拍 1 张
                cameraCaptureSessions.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                        unlockFocus();
                    }
                }, mBackgroundHandler);
            }
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private void unlockFocus() {
        try {
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
            }
            
            // 恢复预览前必须解锁 AE
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            cameraCaptureSessions.capture(captureRequestBuilder.build(), null, mBackgroundHandler);

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
                options.inSampleSize = 8;
                
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

            int format = isYuvMode ? ImageFormat.YUV_420_888 : ImageFormat.JPEG;
            Size[] outputSizes = map.getOutputSizes(format);
            int width = 640, height = 480;
            if (outputSizes != null && outputSizes.length > 0) {
                width = outputSizes[0].getWidth(); height = outputSizes[0].getHeight();
            }
            // 修复：如果处于连拍模式，增加 maxImages 缓存容量，防止丢帧
            int maxImages = isYuvMode ? 6 : 2;
            imageReader = ImageReader.newInstance(width, height, format, maxImages);
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
            // 核心修复：使用 acquireNextImage 而不是 acquireLatestImage
            // acquireLatestImage 会丢弃旧帧，导致连拍时只能拿到最后一张或中间丢帧。
            try (Image image = reader.acquireNextImage()) {
                if (image == null) return;
                
                long totalPipeStart = System.currentTimeMillis();
                int rotation = getWindowManager().getDefaultDisplay().getRotation();
                int orientation = (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360;

                Bitmap bitmap;
                if (image.getFormat() == ImageFormat.JPEG) {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                } else if (image.getFormat() == ImageFormat.YUV_420_888) {
                    bitmap = yuvToBitmapOptimized(image);
                } else {
                    return;
                }
                
                byte[] finalJpeg = processBitmapAndSave(bitmap, orientation, "Brandon");
                saveToGallery(finalJpeg, "jpg");
                
                Log.d(TAG, "Total Image Pipeline took: " + (System.currentTimeMillis() - totalPipeStart) + " ms");
            } catch (IOException e) { 
                Log.e(TAG, "Error processing image", e); 
            }
        }
    };

    // 优化后的 YUV 转 Bitmap 逻辑
    private Bitmap yuvToBitmapOptimized(Image image) {
        long start = System.currentTimeMillis();
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] nv21 = yuv420ToNv21Optimized(image);
        
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 适当降低质量（如 95）能显著提升压缩速度，且肉眼几乎无损
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 95, out);
        byte[] jpegBytes = out.toByteArray();
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        
        Log.d(TAG, "YUV -> Bitmap (inc. NV21 copy) took: " + (System.currentTimeMillis() - start) + " ms");
        return bitmap;
    }

    // 修复后的内存拷贝逻辑
    private byte[] yuv420ToNv21Optimized(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int ySize = width * height;
        int uvSize = width * height / 2;
        byte[] nv21 = new byte[ySize + uvSize];

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        // Y Plane 批量拷贝
        int yRowStride = planes[0].getRowStride();
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize);
        } else {
            for (int i = 0; i < height; i++) {
                yBuffer.position(i * yRowStride);
                yBuffer.get(nv21, i * width, width);
            }
        }

        // UV Plane 优化拷贝
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();
        
        if (uvPixelStride == 2) {
            // 情况 A：UV 交错布局（常见情况）
            if (uvRowStride == width) {
                int remaining = vBuffer.remaining();
                if (remaining >= uvSize) {
                    vBuffer.get(nv21, ySize, uvSize);
                } else {
                    // 修复 BufferUnderflowException: 如果差一个字节，手动补齐
                    vBuffer.get(nv21, ySize, remaining);
                    nv21[ySize + remaining] = uBuffer.get(uBuffer.limit() - 1);
                }
            } else {
                // Stride 不匹配时，按行批量拷贝（比逐字节循环快得多）
                for (int i = 0; i < height / 2; i++) {
                    vBuffer.position(i * uvRowStride);
                    int bytesToCopy = Math.min(width, vBuffer.remaining());
                    vBuffer.get(nv21, ySize + i * width, bytesToCopy);
                    if (bytesToCopy < width && i == height / 2 - 1) {
                         nv21[ySize + i * width + bytesToCopy] = uBuffer.get(uBuffer.limit() - 1);
                    }
                }
            }
        } else {
            // 情况 B：非交错布局，回退到手动合并
            int uvPos = ySize;
            for (int i = 0; i < height / 2; i++) {
                int vOff = i * planes[2].getRowStride();
                int uOff = i * planes[1].getRowStride();
                for (int j = 0; j < width / 2; j++) {
                    nv21[uvPos++] = vBuffer.get(vOff + j * planes[2].getPixelStride());
                    nv21[uvPos++] = uBuffer.get(uOff + j * planes[1].getPixelStride());
                }
            }
        }
        return nv21;
    }

    // 合并旋转、水印 and 压缩，只进行一次位图操作和压缩
    private byte[] processBitmapAndSave(Bitmap source, int orientation, String text) {
        long start = System.currentTimeMillis();
        int width = source.getWidth();
        int height = source.getHeight();
        
        int targetWidth = (orientation == 90 || orientation == 270) ? height : width;
        int targetHeight = (orientation == 90 || orientation == 270) ? width : height;
        
        Bitmap result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        
        // 1. 旋转
        if (orientation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(orientation);
            if (orientation == 90) matrix.postTranslate(height, 0);
            else if (orientation == 180) matrix.postTranslate(width, height);
            else if (orientation == 270) matrix.postTranslate(0, width);
            canvas.drawBitmap(source, matrix, null);
        } else {
            canvas.drawBitmap(source, 0, 0, null);
        }
        
        // 2. 水印
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setAlpha(180);
        paint.setTextSize(targetWidth / 20f);
        paint.setShadowLayer(5f, 2f, 2f, Color.BLACK);
        float padding = targetWidth / 40f;
        canvas.drawText(text, padding, targetHeight - padding, paint);
        
        // 3. 最终压缩
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        result.compress(Bitmap.CompressFormat.JPEG, 90, stream);
        
        source.recycle();
        result.recycle();
        
        Log.d(TAG, "Bitmap Trans/Watermark/Compress took: " + (System.currentTimeMillis() - start) + " ms");
        return stream.toByteArray();
    }

    private void saveToGallery(byte[] bytes, String extension) throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_" + timeStamp + "." + extension);
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
        if (null != cameraCaptureSessions) {
            cameraCaptureSessions.close();
            cameraCaptureSessions = null;
        }
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (null != imageReader) {
            imageReader.close();
            imageReader = null;
        }
        if (null != previewSurface) {
            previewSurface.release();
            previewSurface = null;
        }
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
