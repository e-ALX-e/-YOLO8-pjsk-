package com.pjsk.autoplayer.screen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;
import android.view.WindowMetrics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class ScreenCaptureSource implements AutoCloseable {
    private static final int CAPTURE_MAX_LONG_SIDE = 960;
    private static final float CAPTURE_REQUESTED_FRAME_RATE = 120.0f;

    public interface Listener {
        default boolean shouldCaptureFrame() {
            return true;
        }

        void onFrame(Frame frame);

        default void onCaptureError(Throwable error) {
        }

        default void onCaptureStopped() {
        }
    }

    private final Context context;
    private final MediaProjection mediaProjection;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("pjsk-screen-capture");

    private Handler handler;
    private MediaProjection.Callback projectionCallback;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private Bitmap frameBitmap;
    private Bitmap paddedBitmap;
    private ByteBuffer pixelCopyBuffer;
    private boolean frameBitmapInUse;
    private int displayWidth;
    private int displayHeight;
    private int width;
    private int height;
    private int densityDpi;
    private boolean frameDeliveryPaused;
    private boolean closed;

    public ScreenCaptureSource(Context context, MediaProjection mediaProjection, Listener listener) {
        this.context = context.getApplicationContext();
        this.mediaProjection = mediaProjection;
        this.listener = listener;
    }

    public void start() {
        int[] metrics = getDisplayMetrics();
        displayWidth = Math.max(metrics[0], metrics[1]);
        displayHeight = Math.min(metrics[0], metrics[1]);
        densityDpi = metrics[2];
        int[] captureSize = chooseCaptureSize(displayWidth, displayHeight);
        width = captureSize[0];
        height = captureSize[1];

        thread.start();
        handler = new Handler(thread.getLooper());
        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                releaseResources(false);
                listener.onCaptureStopped();
            }
        };
        mediaProjection.registerCallback(projectionCallback, handler);

        createCapturePipeline();
    }

    /**
     * Pauses delivery without recreating the virtual display. Keeping the projection token and
     * surface intact is required on Android 14, while leaving ImageReader buffers full prevents
     * needless bitmap copies during clock-driven logic playback.
     */
    public void setFrameDeliveryPaused(boolean paused) {
        Handler currentHandler = handler;
        if (currentHandler == null) {
            return;
        }
        currentHandler.post(() -> {
            if (closed || frameDeliveryPaused == paused) {
                return;
            }
            frameDeliveryPaused = paused;
            if (!paused) {
                discardQueuedImages();
            }
        });
    }

    private void createCapturePipeline() {
        Surface surface = createImageReader();
        requestHighFrameRate(surface);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "pjsk-capture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null);
    }

    private Surface createImageReader() {
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            if (frameDeliveryPaused) {
                return;
            }
            Image image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }
            Frame frame = null;
            try {
                if (isClosed() || !listener.shouldCaptureFrame()) {
                    return;
                }
                try {
                    frame = toFrame(image);
                    listener.onFrame(frame);
                    frame = null;
                } catch (Throwable t) {
                    listener.onCaptureError(t);
                }
            } finally {
                if (frame != null) {
                    frame.close();
                }
                image.close();
            }
        }, handler);
        return imageReader.getSurface();
    }

    private void discardQueuedImages() {
        if (imageReader == null) {
            return;
        }
        while (true) {
            Image image = imageReader.acquireLatestImage();
            if (image == null) {
                return;
            }
            image.close();
        }
    }

    private void releaseCapturePipeline() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        releaseImageReader();
    }

    private void releaseImageReader() {
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    /**
     * MediaProjection virtual displays otherwise default to 60 Hz on this device,
     * even while the physical display is rendering the game at 120 Hz.
     */
    private void requestHighFrameRate(Surface surface) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            surface.setFrameRate(
                    CAPTURE_REQUESTED_FRAME_RATE,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
        } catch (RuntimeException ignored) {
            // Frame-rate requests are advisory; capture continues with the platform default.
        }
    }

    private synchronized Frame toFrame(Image image) {
        if (closed) {
            throw new IllegalStateException("capture source is closed");
        }
        long startNs = System.nanoTime();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();

        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        if (pixelStride != 4 || rowStride < pixelStride * width) {
            throw new IllegalStateException("unexpected capture layout: pixelStride=" + pixelStride
                    + " rowStride=" + rowStride + " width=" + width);
        }
        int requiredBytes = rowStride * height;
        ByteBuffer source = buffer.duplicate();
        source.rewind();
        if (source.remaining() < requiredBytes) {
            throw new IllegalStateException("capture buffer is too small: "
                    + source.remaining() + " < " + requiredBytes);
        }
        source.limit(source.position() + requiredBytes);
        ByteBuffer pixels = obtainPixelCopyBuffer(requiredBytes);
        pixels.put(source);
        pixels.flip();

        int rowPadding = rowStride - pixelStride * width;
        int bitmapWidth = width + rowPadding / pixelStride;

        Bitmap bitmap = obtainFrameBitmap(width, height);
        if (bitmapWidth == width) {
            bitmap.copyPixelsFromBuffer(pixels);
        } else {
            Bitmap padded = obtainPaddedBitmap(bitmapWidth, height);
            padded.copyPixelsFromBuffer(pixels);
            Canvas canvas = new Canvas(bitmap);
            Rect src = new Rect(0, 0, width, height);
            Rect dst = new Rect(0, 0, width, height);
            canvas.drawBitmap(padded, src, dst, null);
        }

        double timestampSec = System.nanoTime() / 1_000_000_000.0;
        long captureMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
        return new Frame(
                bitmap,
                width,
                height,
                displayWidth,
                displayHeight,
                timestampSec,
                captureMs,
                () -> releaseFrameBitmap(bitmap));
    }

    private ByteBuffer obtainPixelCopyBuffer(int requiredBytes) {
        if (pixelCopyBuffer == null || pixelCopyBuffer.capacity() < requiredBytes) {
            pixelCopyBuffer = ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder());
        }
        pixelCopyBuffer.clear();
        pixelCopyBuffer.limit(requiredBytes);
        return pixelCopyBuffer;
    }

    private synchronized boolean isClosed() {
        return closed;
    }

    private int[] chooseCaptureSize(int sourceWidth, int sourceHeight) {
        int longSide = Math.max(sourceWidth, sourceHeight);
        if (longSide <= CAPTURE_MAX_LONG_SIDE) {
            return new int[]{sourceWidth, sourceHeight};
        }
        float scale = CAPTURE_MAX_LONG_SIDE / (float) longSide;
        return new int[]{
                evenAtLeast(sourceWidth * scale, 320),
                evenAtLeast(sourceHeight * scale, 180)};
    }

    private int evenAtLeast(float value, int minimum) {
        int rounded = Math.max(minimum, Math.round(value));
        if ((rounded & 1) != 0) {
            rounded--;
        }
        return Math.max(2, rounded);
    }

    private synchronized Bitmap obtainFrameBitmap(int bitmapWidth, int bitmapHeight) {
        if (frameBitmap != null
                && (frameBitmap.getWidth() != bitmapWidth || frameBitmap.getHeight() != bitmapHeight)) {
            frameBitmap.recycle();
            frameBitmap = null;
            frameBitmapInUse = false;
        }
        if (frameBitmap == null) {
            frameBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
        }
        if (frameBitmapInUse) {
            return Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
        }
        frameBitmapInUse = true;
        return frameBitmap;
    }

    private synchronized Bitmap obtainPaddedBitmap(int bitmapWidth, int bitmapHeight) {
        if (paddedBitmap != null
                && (paddedBitmap.getWidth() != bitmapWidth || paddedBitmap.getHeight() != bitmapHeight)) {
            paddedBitmap.recycle();
            paddedBitmap = null;
        }
        if (paddedBitmap == null) {
            paddedBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
        }
        return paddedBitmap;
    }

    private synchronized void releaseFrameBitmap(Bitmap bitmap) {
        if (bitmap == frameBitmap) {
            frameBitmapInUse = false;
            if (closed && frameBitmap != null && !frameBitmap.isRecycled()) {
                frameBitmap.recycle();
                frameBitmap = null;
            }
        } else if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private int[] getDisplayMetrics() {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return new int[]{1280, 720, DisplayMetrics.DENSITY_DEFAULT};
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics windowMetrics = wm.getCurrentWindowMetrics();
            Rect bounds = windowMetrics.getBounds();
            DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
            return new int[]{bounds.width(), bounds.height(), displayMetrics.densityDpi};
        }
        DisplayMetrics displayMetrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(displayMetrics);
        return new int[]{displayMetrics.widthPixels, displayMetrics.heightPixels, displayMetrics.densityDpi};
    }

    @Override
    public void close() {
        releaseResources(true);
    }

    private synchronized void releaseResources(boolean stopProjection) {
        if (closed) {
            return;
        }
        closed = true;
        releaseCapturePipeline();
        if (projectionCallback != null) {
            try {
                mediaProjection.unregisterCallback(projectionCallback);
            } catch (IllegalStateException ignored) {
            }
            projectionCallback = null;
        }
        if (stopProjection) {
            try {
                mediaProjection.stop();
            } catch (IllegalStateException ignored) {
            }
        }
        if (frameBitmap != null && !frameBitmapInUse) {
            frameBitmap.recycle();
            frameBitmap = null;
            frameBitmapInUse = false;
        }
        if (paddedBitmap != null) {
            paddedBitmap.recycle();
            paddedBitmap = null;
        }
        pixelCopyBuffer = null;
        thread.quitSafely();
    }

    public static final class Frame implements AutoCloseable {
        public final Bitmap bitmap;
        public final int width;
        public final int height;
        public final int displayWidth;
        public final int displayHeight;
        public final double timestampSec;
        public final long captureMs;

        private final Runnable onClose;
        private boolean closed;

        Frame(
                Bitmap bitmap,
                int width,
                int height,
                int displayWidth,
                int displayHeight,
                double timestampSec,
                long captureMs,
                Runnable onClose) {
            this.bitmap = bitmap;
            this.width = width;
            this.height = height;
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
            this.timestampSec = timestampSec;
            this.captureMs = captureMs;
            this.onClose = onClose;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            onClose.run();
        }
    }
}
