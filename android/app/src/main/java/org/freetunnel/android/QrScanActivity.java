package org.freetunnel.android;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scans a TrustTunnel QR code with the camera: what a TrustTunnel endpoint, a TrustTunnel client or the desktop's QR page shows
 * for a server is a {@code tt://} link or a trusttunnel.org/qr.html address. The first code that holds one ends the scan, and its
 * text is the result ({@link #EXTRA_TEXT}); MainActivity imports it as a pasted link, with the same question before anything is
 * added. A code that holds anything else is not taken: the hint says so and the scan goes on.
 *
 * <p>Camera2 and ZXing's core alone, with no camera library: the preview is a TextureView, cropped to fill the window, and the
 * luminance of each frame from an ImageReader goes to a QR reader on a thread of its own, one frame at a time; frames that come while
 * one is read are dropped. The text, which carries the server's password, is never logged or shown.
 *
 * <p>Only offered where the device has a camera (MainActivity.addMenu), and never on a TV. Its results: RESULT_OK with the text,
 * RESULT_CANCELED when the user leaves, {@link #RESULT_NO_PERMISSION} and {@link #RESULT_NO_CAMERA}, which MainActivity says in a
 * notice.
 */
public final class QrScanActivity extends Activity {
    static final String EXTRA_TEXT = "org.freetunnel.android.QR_TEXT";
    static final int RESULT_NO_PERMISSION = RESULT_FIRST_USER;
    static final int RESULT_NO_CAMERA = RESULT_FIRST_USER + 1;

    private static final int REQUEST_CAMERA = 1;
    // The largest frame read for codes: enough for a dense code across the window, small enough to read many a second.
    private static final int MAX_READ_PIXELS = 1920 * 1080;
    private static final int MIN_READ_PIXELS = 640 * 480;
    private static final long WRONG_CODE_HINT_MS = 2500;

    private TextureView preview;
    private TextView hint;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private HandlerThread readThread;
    private Handler readHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Size previewSize;
    private int sensorOrientation;
    private boolean opening;
    private boolean done;
    private long wrongCodeUntil;
    private final AtomicBoolean reading = new AtomicBoolean();
    private final QrDecoder qr = new QrDecoder();

    // Its words are in the app's language, which Android 8 to 12 only knows from this (AppLocale).
    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(AppLocale.wrap(base)); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(layout());
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && state == null)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
    }

    // The preview, the frame the code goes in, the close button at the top and the hint at the foot, in the dark theme's colours:
    // they are drawn over the camera's picture, which is no page of the app.
    private View layout() {
        Theme theme = Theme.DARK;
        float density = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) { openCamera(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { transform(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
        });
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        root.addView(new Frame(this, theme, density), new FrameLayout.LayoutParams(-1, -1));

        ImageView close = new ImageView(this);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(theme.text);
        close.setContentDescription(getString(R.string.text_back));
        int pad = Math.round(12 * density);
        close.setPadding(pad, pad, pad, pad);
        close.setFocusable(true);
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(Math.round(48 * density), Math.round(48 * density), Gravity.TOP | Gravity.START);
        root.addView(close, closeLp);

        hint = new TextView(this);
        hint.setTypeface(Fonts.regular(this));
        hint.setTextColor(theme.text);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setGravity(Gravity.CENTER);
        hint.setText(R.string.qr_scan_hint);
        int side = Math.round(24 * density);
        hint.setPadding(side, 0, side, Math.round(32 * density));
        root.addView(hint, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // Edge to edge from Android 15: the close button and the hint stay clear of the bars and the cutout.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            closeLp.topMargin = top;
            closeLp.leftMargin = insets.getSystemWindowInsetLeft();
            close.setLayoutParams(closeLp);
            hint.setPadding(side, 0, side, Math.round(32 * density) + bottom);
            return insets;
        });
        return root;
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != REQUEST_CAMERA) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) openCamera();
        else finishWith(RESULT_NO_PERMISSION, null);
    }

    @Override protected void onResume() {
        super.onResume();
        cameraThread = new HandlerThread("qr-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        readThread = new HandlerThread("qr-read");
        readThread.start();
        readHandler = new Handler(readThread.getLooper());
        reading.set(false);
        if (preview.isAvailable()) openCamera();
    }

    @Override protected void onPause() {
        closeCamera();
        cameraThread.quitSafely();
        readThread.quitSafely();
        cameraThread = null;
        readThread = null;
        cameraHandler = null;
        readHandler = null;
        super.onPause();
    }

    private void finishWith(int result, String text) {
        if (done) return;
        done = true;
        setResult(result, text == null ? null : new Intent().putExtra(EXTRA_TEXT, text));
        finish();
    }

    // ---- The camera ----

    @SuppressLint("MissingPermission") // Checked first: without it the request is still out, or the scan has ended.
    private void openCamera() {
        if (done || opening || camera != null || cameraHandler == null || !preview.isAvailable()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String id = chooseCamera(manager);
            if (id == null) { finishWith(RESULT_NO_CAMERA, null); return; }
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation == null ? 90 : orientation;
            Size read = map == null ? null : chooseSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            Size shown = map == null ? null : chooseSize(map.getOutputSizes(SurfaceTexture.class));
            if (read == null || shown == null) { finishWith(RESULT_NO_CAMERA, null); return; }
            previewSize = shown;
            reader = ImageReader.newInstance(read.getWidth(), read.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::frame, cameraHandler);
            opening = true;
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    runOnUiThread(() -> {
                        opening = false;
                        if (cameraHandler == null || done) { device.close(); return; }
                        camera = device;
                        startPreview();
                    });
                }
                @Override public void onDisconnected(CameraDevice device) {
                    device.close();
                    runOnUiThread(() -> { opening = false; if (camera == device) camera = null; });
                }
                @Override public void onError(CameraDevice device, int error) {
                    device.close();
                    runOnUiThread(() -> { opening = false; camera = null; finishWith(RESULT_NO_CAMERA, null); });
                }
            }, cameraHandler);
        } catch (CameraAccessException | RuntimeException e) {
            opening = false;
            finishWith(RESULT_NO_CAMERA, null);
        }
    }

    // The back camera where there is one, as a code is held in front of the device; else any.
    private static String chooseCamera(CameraManager manager) throws CameraAccessException {
        String[] ids = manager.getCameraIdList();
        for (String id : ids) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return ids.length > 0 ? ids[0] : null;
    }

    // Of the sizes a stream offers, the one nearest 1280 x 720 within the limits, wide side first as the sensor gives them.
    static Size chooseSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        List<Size> fitting = new ArrayList<>();
        for (Size s : sizes) {
            long pixels = (long) s.getWidth() * s.getHeight();
            if (pixels <= MAX_READ_PIXELS && pixels >= MIN_READ_PIXELS) fitting.add(s);
        }
        List<Size> from = fitting.isEmpty() ? Arrays.asList(sizes) : fitting;
        Size best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Size s : from) {
            long distance = Math.abs((long) s.getWidth() * s.getHeight() - 1280L * 720L);
            if (distance < bestDistance) { best = s; bestDistance = distance; }
        }
        return best;
    }

    @SuppressWarnings("deprecation") // createCaptureSession(List, ...) is the form Android 8 has; SessionConfiguration is from 9.
    private void startPreview() {
        try {
            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null || camera == null) return;
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            transform();
            Surface shown = new Surface(texture);
            Surface read = reader.getSurface();
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(shown);
            request.addTarget(read);
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            CameraDevice device = camera;
            device.createCaptureSession(Arrays.asList(shown, read), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (camera != device) { configured.close(); return; }
                    session = configured;
                    try { configured.setRepeatingRequest(request.build(), null, cameraHandler); }
                    catch (CameraAccessException | RuntimeException e) { runOnUiThread(() -> finishWith(RESULT_NO_CAMERA, null)); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession failed) {
                    runOnUiThread(() -> finishWith(RESULT_NO_CAMERA, null));
                }
            }, cameraHandler);
        } catch (CameraAccessException | RuntimeException e) {
            finishWith(RESULT_NO_CAMERA, null);
        }
    }

    private void closeCamera() {
        if (session != null) { try { session.close(); } catch (RuntimeException ignored) { } session = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (reader != null) { reader.close(); reader = null; }
    }

    // The preview fills the window, cropped and never stretched, and stands the right way up in every rotation of the display.
    private void transform() {
        if (previewSize == null) return;
        int viewWidth = preview.getWidth();
        int viewHeight = preview.getHeight();
        if (viewWidth == 0 || viewHeight == 0) return;
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        // The picture as the display shows it: the sensor's wide side across when sensor and display are turned alike.
        int degrees = rotation * 90;
        boolean swapped = (sensorOrientation - degrees + 360) % 180 != 0;
        float shownWidth = swapped ? previewSize.getHeight() : previewSize.getWidth();
        float shownHeight = swapped ? previewSize.getWidth() : previewSize.getHeight();
        Matrix matrix = new Matrix();
        float cx = viewWidth / 2f;
        float cy = viewHeight / 2f;
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            // TextureView fits the buffer as the natural orientation sees it: turned back, then cropped.
            RectF view = new RectF(0, 0, viewWidth, viewHeight);
            RectF buffer = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
            buffer.offset(cx - buffer.centerX(), cy - buffer.centerY());
            matrix.setRectToRect(view, buffer, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) viewHeight / previewSize.getHeight(), (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, cx, cy);
            matrix.postRotate(90 * (rotation - 2), cx, cy);
        } else {
            float scale = Math.max(viewWidth / shownWidth, viewHeight / shownHeight);
            matrix.setScale(shownWidth * scale / viewWidth, shownHeight * scale / viewHeight, cx, cy);
            if (rotation == Surface.ROTATION_180) matrix.postRotate(180, cx, cy);
        }
        preview.setTransform(matrix);
    }

    // ---- Reading ----

    // On the camera's thread: the frame's luminance is copied and the frame given back at once, so the camera never waits for the
    // reader. A frame that comes while another is read is dropped.
    private void frame(ImageReader from) {
        Image image;
        try { image = from.acquireLatestImage(); } catch (RuntimeException e) { return; }
        if (image == null) return;
        try {
            if (done || readHandler == null || !reading.compareAndSet(false, true)) return;
            int width = image.getWidth();
            int height = image.getHeight();
            byte[] luminance = luminance(image.getPlanes()[0], width, height);
            // A reader that has stopped (the activity paused meanwhile) takes nothing, and the next frame may try again.
            if (!readHandler.post(() -> read(luminance, width, height))) reading.set(false);
        } catch (RuntimeException e) {
            reading.set(false);
        } finally {
            image.close();
        }
    }

    // The Y plane as width x height bytes, one a pixel: its rows may be padded, and its pixels apart.
    static byte[] luminance(Image.Plane plane, int width, int height) {
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        byte[] out = new byte[width * height];
        if (pixelStride == 1 && rowStride == width) {
            buffer.get(out, 0, Math.min(out.length, buffer.remaining()));
            return out;
        }
        byte[] row = new byte[rowStride];
        for (int y = 0; y < height; y++) {
            int start = y * rowStride;
            if (start >= buffer.limit()) break;
            buffer.position(start);
            int length = Math.min(rowStride, buffer.remaining());
            buffer.get(row, 0, length);
            for (int x = 0, i = 0; x < width && i < length; x++, i += pixelStride) out[y * width + x] = row[i];
        }
        return out;
    }

    // On the reader's thread, the only one that uses the decoder.
    private void read(byte[] luminance, int width, int height) {
        String text;
        try { text = qr.decode(luminance, width, height); }
        finally { reading.set(false); }
        if (text != null) runOnUiThread(() -> found(text));
    }

    private void found(String text) {
        if (done) return;
        if (DeepLinkImport.looksLikeLink(text)) { finishWith(RESULT_OK, text); return; }
        // Some other code: said for a while, and the scan goes on.
        long now = android.os.SystemClock.uptimeMillis();
        if (now < wrongCodeUntil) return;
        wrongCodeUntil = now + WRONG_CODE_HINT_MS;
        hint.setText(R.string.qr_scan_not_a_link);
        hint.postDelayed(() -> { if (android.os.SystemClock.uptimeMillis() >= wrongCodeUntil) hint.setText(R.string.qr_scan_hint); },
                WRONG_CODE_HINT_MS);
    }

    /** The square a code is held in: the rest of the picture dimmed, and the square's corners in the accent. */
    private static final class Frame extends View {
        private final Paint dim = new Paint();
        private final Paint corner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outside = new Path();
        private final RectF square = new RectF();
        private final RectF arc = new RectF();
        private final float density;

        Frame(Context context, Theme theme, float density) {
            super(context);
            this.density = density;
            dim.setColor(theme.backdrop);
            corner.setColor(theme.text);
            corner.setStyle(Paint.Style.STROKE);
            corner.setStrokeWidth(3 * density);
            corner.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float side = Math.min(w, h) * 0.65f;
            float left = (w - side) / 2f;
            float top = (h - side) / 2f;
            square.set(left, top, left + side, top + side);
            float radius = 12 * density;
            outside.reset();
            outside.setFillType(Path.FillType.EVEN_ODD);
            outside.addRect(0, 0, w, h, Path.Direction.CW);
            outside.addRoundRect(square, radius, radius, Path.Direction.CW);
            canvas.drawPath(outside, dim);
            float arm = side * 0.12f;
            float r = square.right;
            float b = square.bottom;
            // Each corner as an L along the square's edges.
            canvas.drawLine(left, top + arm, left, top + radius, corner);
            canvas.drawLine(left + radius, top, left + arm, top, corner);
            canvas.drawLine(r - arm, top, r - radius, top, corner);
            canvas.drawLine(r, top + radius, r, top + arm, corner);
            canvas.drawLine(left, b - arm, left, b - radius, corner);
            canvas.drawLine(left + radius, b, left + arm, b, corner);
            canvas.drawLine(r - arm, b, r - radius, b, corner);
            canvas.drawLine(r, b - radius, r, b - arm, corner);
            arc.set(left, top, left + 2 * radius, top + 2 * radius);
            canvas.drawArc(arc, 180, 90, false, corner);
            arc.set(r - 2 * radius, top, r, top + 2 * radius);
            canvas.drawArc(arc, 270, 90, false, corner);
            arc.set(left, b - 2 * radius, left + 2 * radius, b);
            canvas.drawArc(arc, 90, 90, false, corner);
            arc.set(r - 2 * radius, b - 2 * radius, r, b);
            canvas.drawArc(arc, 0, 90, false, corner);
        }
    }
}
