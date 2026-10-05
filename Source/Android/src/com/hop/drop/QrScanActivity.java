package com.hop.drop;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Size;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.hop.drop.core.PairUri;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.HybridBinarizer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scans another device's pairing QR code and returns its hopdrop://pair URI as the "uri" extra ("fallback" when the
 * user chooses number pairing instead). The preview fills the screen without stretching. Codes are read anywhere in
 * the frame, fastest near the middle. Pinch, double-tap or the zoom chip zooms in, so the phone can stay far enough
 * from a laptop screen to focus; a tap focuses on that spot.
 */
public final class QrScanActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int CAMERA_PERMISSION = 71;
    /** Largest analysed frame: enough detail for a dense code on a small laptop screen, small enough to decode fast. */
    private static final int MAX_WIDTH = 1920, MAX_HEIGHT = 1080;
    private static final float MAX_ZOOM = 4f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final MultiFormatReader decoder = new MultiFormatReader();
    private final Matrix transform = new Matrix();
    private final Runnable continuousFocus = this::continuousFocus;
    private final Runnable hint = this::showHint;
    private TextureView preview;
    private Overlay overlay;
    private TextView message, zoomChip;
    private LinearLayout permissionPanel;
    private HandlerThread cameraThread, decodeThread;
    private Handler cameraHandler, decodeHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder request;
    private ImageReader reader;
    private Size size = new Size(1280, 720);
    private Rect activeArray;
    private int sensorOrientation = 90, maxFocusRegions, maxExposureRegions;
    private boolean continuousAvailable, autoAvailable, resumed, opening, asked;
    private float maxZoom = 1f, zoom = 1f;
    private volatile boolean finished, decoding;
    private byte[] luma;
    private int frames;
    private long lastWrongCode;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        decoder.setHints(hints);
        cameraThread = new HandlerThread("HopDrop camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        decodeThread = new HandlerThread("HopDrop QR decoder");
        decodeThread.start();
        decodeHandler = new Handler(decodeThread.getLooper());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        overlay = new Overlay(this);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView close = new ImageView(this);
        close.setImageResource(R.drawable.ic_action_remove);
        close.setContentDescription("Close");
        close.setPadding(dp(12), dp(12), dp(12), dp(12));
        close.setBackground(pill(0x66000000));
        close.setOnClickListener(view -> finish());
        top.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text("Scan the pairing code", 19, true);
        title.setPadding(dp(14), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        zoomChip = text("1×", 15, true);
        zoomChip.setGravity(Gravity.CENTER);
        zoomChip.setMinWidth(dp(64));
        zoomChip.setPadding(dp(16), dp(9), dp(16), dp(9));
        zoomChip.setBackground(pill(0x80000000));
        zoomChip.setContentDescription("Zoom");
        zoomChip.setVisibility(View.GONE);
        zoomChip.setOnClickListener(view -> toggleZoom());
        bottom.addView(zoomChip, new LinearLayout.LayoutParams(-2, -2));
        message = text("", 15, false);
        message.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(-1, -2);
        messageParams.topMargin = dp(16);
        bottom.addView(message, messageParams);
        showHint();
        TextView fallback = text("Can't scan? Pair by comparing numbers", 15, true);
        fallback.setTextColor(0xFFA8C7FF);
        fallback.setPadding(dp(16), dp(12), dp(16), dp(12));
        fallback.setOnClickListener(view -> {
            setResult(RESULT_OK, new Intent().putExtra("fallback", true));
            finish();
        });
        LinearLayout.LayoutParams fallbackParams = new LinearLayout.LayoutParams(-2, -2);
        fallbackParams.topMargin = dp(6);
        bottom.addView(fallback, fallbackParams);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        permissionPanel = permissionPanel();
        permissionPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        panelParams.leftMargin = panelParams.rightMargin = dp(24);
        root.addView(permissionPanel, panelParams);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            top.setPadding(dp(12) + insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop() + dp(8),
                    dp(16) + insets.getSystemWindowInsetRight(), dp(8));
            bottom.setPadding(dp(24) + insets.getSystemWindowInsetLeft(), dp(16),
                    dp(24) + insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom() + dp(16));
            return insets;
        });
        setContentView(root);
        listenForGestures();
        if (!cameraAllowed()) {
            asked = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (cameraAllowed()) {
            permissionPanel.setVisibility(View.GONE);
            if (preview.isAvailable()) openCamera();
        }
        overlay.startSweep();
    }

    @Override
    protected void onPause() {
        resumed = false;
        closeCamera();
        overlay.stopSweep();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        closeCamera();
        cameraThread.quitSafely();
        decodeThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != CAMERA_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            permissionPanel.setVisibility(View.GONE);
            if (resumed && preview.isAvailable()) openCamera();
        } else permissionPanel.setVisibility(View.VISIBLE);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (resumed && cameraAllowed()) openCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        updateTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        closeCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
    }

    private boolean cameraAllowed() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    // ---- Camera ----

    private void openCamera() {
        if (camera != null || opening || finished) return;
        try {
            CameraManager manager = getSystemService(CameraManager.class);
            String chosen = null;
            CameraCharacteristics info = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics candidate = manager.getCameraCharacteristics(id);
                Integer facing = candidate.get(CameraCharacteristics.LENS_FACING);
                boolean back = facing != null && facing == CameraCharacteristics.LENS_FACING_BACK;
                if (chosen == null || back) {
                    chosen = id;
                    info = candidate;
                }
                if (back) break;
            }
            if (chosen == null) throw new IllegalStateException("No camera");
            configure(info);
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onFrame, cameraHandler);
            opening = true;
            manager.openCamera(chosen, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    main.post(() -> {
                        opening = false;
                        if (!resumed || finished || reader == null || camera != null) {
                            device.close();
                            return;
                        }
                        camera = device;
                        startSession();
                    });
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    device.close();
                    main.post(() -> {
                        opening = false;
                        if (camera == device) camera = null;
                    });
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    device.close();
                    main.post(() -> {
                        opening = false;
                        if (camera == device) camera = null;
                        if (resumed) say(error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE
                                ? "Another app is using the camera. Close it, then come back here."
                                : "The camera stopped. Close this screen and try again.", true);
                    });
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException | IllegalStateException | IllegalArgumentException error) {
            opening = false;
            say("The camera couldn't start. Check that HopDrop may use the camera.", true);
        }
    }

    private void configure(CameraCharacteristics info) {
        Integer orientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
        sensorOrientation = orientation == null ? 90 : orientation;
        activeArray = info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Float digital = info.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        maxZoom = digital == null || activeArray == null ? 1f : Math.max(1f, Math.min(MAX_ZOOM, digital));
        zoom = Math.min(zoom, maxZoom);
        int[] modes = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        continuousAvailable = contains(modes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        autoAvailable = contains(modes, CaptureRequest.CONTROL_AF_MODE_AUTO);
        Integer focusRegions = info.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        Integer exposureRegions = info.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        maxFocusRegions = focusRegions == null ? 0 : focusRegions;
        maxExposureRegions = exposureRegions == null ? 0 : exposureRegions;
        size = chooseSize(info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP));
        showZoom();
    }

    /** The largest stream up to 1920×1080 that both the preview and the analysis support, preferring 16:9 (less is cropped on a tall phone screen). */
    private static Size chooseSize(StreamConfigurationMap map) {
        Size fallback = new Size(1280, 720);
        if (map == null) return fallback;
        Size[] analysis = map.getOutputSizes(ImageFormat.YUV_420_888);
        Size[] previews = map.getOutputSizes(SurfaceTexture.class);
        if (analysis == null || previews == null) return fallback;
        List<Size> shown = Arrays.asList(previews);
        Size best = null;
        for (Size candidate : analysis) {
            if (candidate.getWidth() > MAX_WIDTH || candidate.getHeight() > MAX_HEIGHT || candidate.getWidth() < 640
                    || !shown.contains(candidate)) continue;
            if (best == null || score(candidate) > score(best)) best = candidate;
        }
        return best == null ? fallback : best;
    }

    private static long score(Size candidate) {
        boolean wide = Math.abs(candidate.getWidth() * 9 - candidate.getHeight() * 16) <= candidate.getWidth() / 50;
        return (wide ? 1L << 40 : 0) + (long) candidate.getWidth() * candidate.getHeight();
    }

    private static boolean contains(int[] values, int wanted) {
        if (values == null) return false;
        for (int value : values) if (value == wanted) return true;
        return false;
    }

    private void startSession() {
        SurfaceTexture texture = preview.getSurfaceTexture();
        if (texture == null || camera == null || reader == null) return;
        try {
            texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
            updateTransform();
            Surface view = new Surface(texture);
            request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(view);
            request.addTarget(reader.getSurface());
            request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            if (continuousAvailable) {
                request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
            applyZoom();
            CameraDevice device = camera;
            device.createCaptureSession(Arrays.asList(view, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession configured) {
                    main.post(() -> {
                        if (camera != device) {
                            configured.close();
                            return;
                        }
                        session = configured;
                        repeat();
                    });
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession failed) {
                    main.post(() -> say("The camera couldn't start. Close this screen and try again.", true));
                }
            }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException error) {
            say("The camera couldn't start. Close this screen and try again.", true);
        }
    }

    private void repeat() {
        try {
            if (session != null && request != null) session.setRepeatingRequest(request.build(), null, cameraHandler);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException ignored) {
        }
    }

    private void closeCamera() {
        main.removeCallbacks(continuousFocus);
        if (session != null) {
            try {
                session.close();
            } catch (IllegalStateException ignored) {
            }
            session = null;
        }
        if (camera != null) {
            camera.close();
            camera = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        request = null;
        opening = false;
    }

    /** Fills the view with the preview, cropping the edges instead of stretching it. */
    private void updateTransform() {
        int width = preview.getWidth(), height = preview.getHeight();
        if (width == 0 || height == 0) return;
        float centerX = width / 2f, centerY = height / 2f;
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        transform.reset();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            RectF view = new RectF(0, 0, width, height);
            RectF buffer = new RectF(0, 0, size.getHeight(), size.getWidth());
            buffer.offset(centerX - buffer.centerX(), centerY - buffer.centerY());
            transform.setRectToRect(view, buffer, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) height / size.getHeight(), (float) width / size.getWidth());
            transform.postScale(scale, scale, centerX, centerY);
            transform.postRotate(90 * (rotation - 2), centerX, centerY);
        } else {
            // The camera already turns the image upright for a portrait screen; only the aspect ratio needs fixing.
            float contentWidth = size.getHeight(), contentHeight = size.getWidth();
            float scale = Math.max(width / contentWidth, height / contentHeight);
            transform.setScale(scale * contentWidth / width, scale * contentHeight / height, centerX, centerY);
            if (rotation == Surface.ROTATION_180) transform.postRotate(180, centerX, centerY);
        }
        preview.setTransform(transform);
    }

    // ---- Zoom and focus ----

    private void listenForGestures() {
        ScaleGestureDetector pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                setZoom(zoom * detector.getScaleFactor());
                return true;
            }
        });
        GestureDetector taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent event) {
                focusAt(event.getX(), event.getY());
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                toggleZoom();
                return true;
            }
        });
        overlay.setOnTouchListener((view, event) -> {
            pinch.onTouchEvent(event);
            if (!pinch.isInProgress()) taps.onTouchEvent(event);
            return true;
        });
    }

    private void toggleZoom() {
        setZoom(zoom < 1.5f ? Math.min(2f, maxZoom) : 1f);
    }

    private void setZoom(float value) {
        float next = Math.max(1f, Math.min(maxZoom, value));
        if (Math.abs(next - zoom) < 0.01f) return;
        zoom = next;
        applyZoom();
        repeat();
        showZoom();
    }

    private void applyZoom() {
        if (request != null && activeArray != null) request.set(CaptureRequest.SCALER_CROP_REGION, cropRegion());
    }

    /** The zoomed part of the sensor, in active-array coordinates ((0, 0) is the array's top-left corner). */
    private Rect cropRegion() {
        int width = Math.round(activeArray.width() / zoom), height = Math.round(activeArray.height() / zoom);
        int left = (activeArray.width() - width) / 2, top = (activeArray.height() - height) / 2;
        return new Rect(left, top, left + width, top + height);
    }

    private void showZoom() {
        zoomChip.setVisibility(maxZoom > 1.2f ? View.VISIBLE : View.GONE);
        float rounded = Math.round(zoom * 10) / 10f;
        zoomChip.setText(rounded == (int) rounded ? (int) rounded + "×" : String.format(Locale.US, "%.1f×", rounded));
    }

    private void focusAt(float x, float y) {
        overlay.focusRing(x, y);
        if (session == null || request == null || activeArray == null || !autoAvailable
                || maxFocusRegions == 0 && maxExposureRegions == 0) return;
        PointF point = viewToSensor(x, y);
        if (point == null) return;
        Rect crop = cropRegion();
        // The stream keeps its own aspect ratio, so it shows the middle of the crop region.
        float aspect = size.getWidth() / (float) size.getHeight();
        float shownWidth = Math.min(crop.width(), crop.height() * aspect), shownHeight = Math.min(crop.height(), crop.width() / aspect);
        float centerX = crop.left + (crop.width() - shownWidth) / 2 + point.x * shownWidth;
        float centerY = crop.top + (crop.height() - shownHeight) / 2 + point.y * shownHeight;
        int half = Math.max(16, Math.round(Math.min(shownWidth, shownHeight) * 0.08f));
        int left = clamp(Math.round(centerX) - half, 0, activeArray.width() - 1);
        int top = clamp(Math.round(centerY) - half, 0, activeArray.height() - 1);
        int right = clamp(Math.round(centerX) + half, left + 1, activeArray.width());
        int bottom = clamp(Math.round(centerY) + half, top + 1, activeArray.height());
        MeteringRectangle[] area = {new MeteringRectangle(new Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX - 1)};
        main.removeCallbacks(continuousFocus);
        try {
            if (maxFocusRegions > 0) request.set(CaptureRequest.CONTROL_AF_REGIONS, area);
            if (maxExposureRegions > 0) request.set(CaptureRequest.CONTROL_AE_REGIONS, area);
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            session.capture(request.build(), null, cameraHandler);
            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(request.build(), null, cameraHandler);
            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            repeat();
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException ignored) {
        }
        main.postDelayed(continuousFocus, 5000);
    }

    /** A while after a tap, the camera goes back to focusing by itself. */
    private void continuousFocus() {
        if (request == null || activeArray == null) return;
        MeteringRectangle[] anywhere = {new MeteringRectangle(0, 0, activeArray.width() - 1, activeArray.height() - 1, 0)};
        if (maxFocusRegions > 0) request.set(CaptureRequest.CONTROL_AF_REGIONS, anywhere);
        if (maxExposureRegions > 0) request.set(CaptureRequest.CONTROL_AE_REGIONS, anywhere);
        request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        if (continuousAvailable) {
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
        repeat();
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    /** A point on screen as fractions of the camera image's width and height, in the sensor's own orientation. */
    private PointF viewToSensor(float x, float y) {
        Matrix inverse = new Matrix();
        if (!transform.invert(inverse) || preview.getWidth() == 0) return null;
        float[] point = {x, y};
        inverse.mapPoints(point);
        float u = point[0] / preview.getWidth(), v = point[1] / preview.getHeight();
        switch (sensorOrientation) {
            case 90: return new PointF(v, 1 - u);
            case 180: return new PointF(1 - u, 1 - v);
            case 270: return new PointF(1 - v, u);
            default: return new PointF(u, v);
        }
    }

    /** The reverse of {@link #viewToSensor}: where a spot in the analysed frame appears on screen. */
    private PointF sensorToView(float x, float y) {
        float u, v;
        switch (sensorOrientation) {
            case 90: u = 1 - y; v = x; break;
            case 180: u = 1 - x; v = 1 - y; break;
            case 270: u = y; v = 1 - x; break;
            default: u = x; v = y;
        }
        float[] point = {u * preview.getWidth(), v * preview.getHeight()};
        transform.mapPoints(point);
        return new PointF(point[0], point[1]);
    }

    // ---- Decoding ----

    /** Camera thread: copies the brightness plane of the newest frame while the decoder is free; drops the rest. */
    private void onFrame(ImageReader source) {
        Image image = null;
        try {
            image = source.acquireLatestImage();
            Handler decode = decodeHandler;
            if (image == null || finished || decoding || decode == null) return;
            int width = image.getWidth(), height = image.getHeight();
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer data = plane.getBuffer();
            int stride = plane.getRowStride();
            if (luma == null || luma.length != width * height) luma = new byte[width * height];
            if (stride == width) data.get(luma, 0, width * height);
            else for (int row = 0; row < height; row++) {
                data.position(row * stride);
                data.get(luma, row * width, width);
            }
            decoding = true;
            int attempt = frames++;
            decode.post(() -> decode(width, height, attempt));
        } catch (RuntimeException ignored) {
            // The reader closed under us (screen left): nothing to do.
        } finally {
            if (image != null) image.close();
        }
    }

    /** Decoder thread. Two frames in three look at the middle of the frame (fast); the third looks at all of it. */
    private void decode(int width, int height, int attempt) {
        try {
            int left = 0, top = 0, cropWidth = width, cropHeight = height;
            if (attempt % 3 != 2) {
                int side = Math.round(Math.min(width, height) * 0.85f);
                left = (width - side) / 2;
                top = (height - side) / 2;
                cropWidth = cropHeight = side;
            }
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(luma, width, height, left, top,
                    cropWidth, cropHeight, false);
            Result result = decoder.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));
            found(result, left, top, width, height);
        } catch (ReaderException ignored) {
            // No code in this frame.
        } catch (RuntimeException ignored) {
        } finally {
            decoder.reset();
            decoding = false;
        }
    }

    private void found(Result result, int left, int top, int width, int height) {
        String text = result.getText();
        if (text == null || !text.startsWith("hopdrop://pair?")) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastWrongCode > 3000) {
                lastWrongCode = now;
                main.post(() -> say("That's a different QR code. On the laptop, open HopDrop → Devices → Pair a phone.", true));
            }
            return;
        }
        try {
            PairUri.parse(text);
        } catch (IllegalArgumentException damaged) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastWrongCode > 3000) {
                lastWrongCode = now;
                main.post(() -> say("This pairing code is from a different HopDrop version. Update HopDrop on both devices.", true));
            }
            return;
        }
        finished = true;
        ResultPoint[] points = result.getResultPoints();
        float[] spots = new float[points == null ? 0 : points.length * 2];
        for (int i = 0; points != null && i < points.length; i++) {
            spots[i * 2] = (left + points[i].getX()) / width;
            spots[i * 2 + 1] = (top + points[i].getY()) / height;
        }
        main.post(() -> success(text, spots));
    }

    private void success(String uri, float[] spots) {
        List<PointF> shown = new ArrayList<>();
        for (int i = 0; i + 1 < spots.length; i += 2) shown.add(sensorToView(spots[i], spots[i + 1]));
        overlay.success(shown);
        overlay.performHapticFeedback(Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM
                : HapticFeedbackConstants.VIRTUAL_KEY);
        say("Code found", false);
        main.postDelayed(() -> {
            setResult(RESULT_OK, new Intent().putExtra("uri", uri));
            finish();
        }, Ui.animationsEnabled() ? 400 : 0);
    }

    // ---- Text and small views ----

    private void showHint() {
        say("On the laptop: HopDrop → Devices → Pair a phone.\nFit the code inside the square. Too close to focus? Zoom in.", false);
    }

    private void say(String text, boolean problem) {
        message.setText(text);
        message.setTextColor(problem ? 0xFFFFD27A : Color.WHITE);
        main.removeCallbacks(hint);
        if (problem && !finished) main.postDelayed(hint, 5000);
    }

    private LinearLayout permissionPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(22), dp(24), dp(18));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF2172238);
        background.setCornerRadius(dp(24));
        panel.setBackground(background);
        panel.addView(text("Allow the camera", 20, true));
        TextView body = text("HopDrop uses the camera only to read the pairing code. Nothing is recorded or saved.", 15, false);
        body.setPadding(0, dp(8), 0, dp(18));
        panel.addView(body);
        TextView allow = text("Allow camera", 16, true);
        allow.setGravity(Gravity.CENTER);
        allow.setPadding(dp(20), dp(13), dp(20), dp(13));
        allow.setBackground(pill(getColor(R.color.button_blue)));
        allow.setOnClickListener(view -> {
            if (!asked || shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                asked = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            } else {
                // Android won't ask again: the permission can only be turned on in Settings.
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException error) {
                    say("Open Settings → Apps → HopDrop → Permissions and allow the camera.", true);
                }
            }
        });
        panel.addView(allow, new LinearLayout.LayoutParams(-1, -2));
        TextView cancel = text("Not now", 15, true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(20), dp(12), dp(20), dp(4));
        cancel.setOnClickListener(view -> finish());
        panel.addView(cancel, new LinearLayout.LayoutParams(-1, -2));
        return panel;
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sizeSp);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
        return view;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(100));
        return shape;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** The dimmed frame with the scanning square, a sweeping line, the tap-to-focus ring and the "found it" outline. */
    private final class Overlay extends View {
        private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint corners = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sweep = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hit = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF window = new RectF();
        private final Path shade = new Path(), bracket = new Path();
        private ValueAnimator sweeping, ringing;
        private float sweepAt = -1, ringX, ringY, ringDone = 1;
        private RectF code;

        Overlay(Context context) {
            super(context);
            scrim.setColor(0x8C000000);
            corners.setStyle(Paint.Style.STROKE);
            corners.setStrokeWidth(dp(4));
            corners.setStrokeCap(Paint.Cap.ROUND);
            corners.setColor(Color.WHITE);
            sweep.setColor(0xFF7FB0FF);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2));
            ring.setColor(Color.WHITE);
            hit.setStyle(Paint.Style.STROKE);
            hit.setStrokeWidth(dp(4));
            hit.setColor(0xFF34D399);
            setContentDescription("Camera preview. Tap to focus, pinch to zoom.");
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            float side = Math.min(width * 0.72f, Math.min(height * 0.42f, dp(340)));
            float centerX = width / 2f, centerY = height / 2f, radius = dp(22), arm = dp(36);
            window.set(centerX - side / 2, centerY - side / 2, centerX + side / 2, centerY + side / 2);
            shade.reset();
            shade.setFillType(Path.FillType.EVEN_ODD);
            shade.addRect(0, 0, width, height, Path.Direction.CW);
            shade.addRoundRect(window, radius, radius, Path.Direction.CW);
            bracket.reset();
            float l = window.left, t = window.top, r = window.right, b = window.bottom, d = radius * 2;
            bracket.moveTo(l, t + arm);
            bracket.arcTo(new RectF(l, t, l + d, t + d), 180, 90, false);
            bracket.lineTo(l + arm, t);
            bracket.moveTo(r - arm, t);
            bracket.arcTo(new RectF(r - d, t, r, t + d), 270, 90, false);
            bracket.lineTo(r, t + arm);
            bracket.moveTo(r, b - arm);
            bracket.arcTo(new RectF(r - d, b - d, r, b), 0, 90, false);
            bracket.lineTo(r - arm, b);
            bracket.moveTo(l + arm, b);
            bracket.arcTo(new RectF(l, b - d, l + d, b), 90, 90, false);
            bracket.lineTo(l, b - arm);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawPath(shade, scrim);
            corners.setColor(code == null ? Color.WHITE : hit.getColor());
            canvas.drawPath(bracket, corners);
            if (code == null && sweepAt >= 0) {
                float y = window.top + dp(12) + sweepAt * (window.height() - dp(24));
                sweep.setAlpha(Math.round(255 * (0.35f + 0.65f * (float) Math.sin(Math.PI * sweepAt))));
                canvas.drawRoundRect(window.left + dp(18), y - dp(1), window.right - dp(18), y + dp(1), dp(1), dp(1), sweep);
            }
            if (ringDone < 1) {
                ring.setAlpha(Math.round(255 * (1 - ringDone)));
                canvas.drawCircle(ringX, ringY, dp(30) + dp(10) * (1 - ringDone), ring);
            }
            if (code != null) canvas.drawRoundRect(code, dp(10), dp(10), hit);
        }

        void startSweep() {
            if (sweeping != null || !Ui.animationsEnabled()) return;
            sweeping = ValueAnimator.ofFloat(0f, 1f);
            sweeping.setDuration(2200);
            sweeping.setRepeatCount(ValueAnimator.INFINITE);
            sweeping.setRepeatMode(ValueAnimator.REVERSE);
            sweeping.addUpdateListener(animation -> {
                sweepAt = (float) animation.getAnimatedValue();
                invalidate();
            });
            sweeping.start();
        }

        void stopSweep() {
            if (sweeping != null) sweeping.cancel();
            sweeping = null;
            sweepAt = -1;
            invalidate();
        }

        void focusRing(float x, float y) {
            ringX = x;
            ringY = y;
            if (ringing != null) ringing.cancel();
            if (!Ui.animationsEnabled()) return;
            ringing = ValueAnimator.ofFloat(0f, 1f);
            ringing.setDuration(700);
            ringing.addUpdateListener(animation -> {
                ringDone = (float) animation.getAnimatedValue();
                invalidate();
            });
            ringing.start();
        }

        /** Outlines the code that was read (its three corner squares, plus a margin). */
        void success(List<PointF> points) {
            stopSweep();
            if (points.isEmpty()) code = new RectF(window);
            else {
                RectF box = new RectF(points.get(0).x, points.get(0).y, points.get(0).x, points.get(0).y);
                for (PointF point : points) box.union(point.x, point.y);
                float margin = Math.max(box.width(), box.height()) * 0.22f + dp(8);
                box.inset(-margin, -margin);
                code = box;
            }
            invalidate();
        }
    }
}
