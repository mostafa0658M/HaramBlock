// ImageViewAccessibilityService.java
package com.haram.block;

import android.util.Log;
import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.Gravity;
import android.content.res.AssetManager;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ImageViewAccessibilityService extends AccessibilityService {

    // From Activity: we’ll command the service to turn feature on/off
    public static final String ACTION_SET_ACTIVE = "com.haram.block.ACTION_SET_ACTIVE";
    public static final String EXTRA_ACTIVE = "extra_active";

    // Optional: service -> activity to ask for MP permission if missing after reconnect
    public static final String ACTION_NEEDS_MEDIA_PROJECTION = "com.haram.block.ACTION_NEEDS_MEDIA_PROJECTION";

    // Shared prefs to remember only "user wants it active" (not "is running")
    private static final String PREFS = "com.haram.block";
    private static final String PREF_USER_WANTS_ACTIVE = "user_wants_active";

    // MediaProjection result (Activity will set these statically after user grants it)
    public static Intent sMediaProjectionResultData;
    public static int sMediaProjectionResultCode;

    private static final String TAG = "ImageViewService";

    // Removed: public static boolean active = false;
    private boolean active = false;

    // Native libs
    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("imageclassification");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library: " + e.getMessage());
        }
    }

    private WindowManager windowManager;
    private OverlayView overlayView;
    private Handler handler;
    private Handler backgroundHandler;

    private String currentPackageName = "";
    private Map<String, List<Rect>> cachedFixedElements = new HashMap<>();
    private static final int MIN_IMAGE_SIZE_DP = 75; // dp
    private boolean isUpdating = false;
    private boolean isScrollMonitoring = false;
    private static final long SCROLL_MONITOR_DURATION = 1000; // ms
    private long lastFixedDetectTime = 0;
    private static final long FIXED_DETECT_DEBOUNCE = 500; // ms
    private Choreographer.FrameCallback frameCallback;
    private Runnable stopMonitoringRunnable;
    private int currentScreenScrollX = 0;
    private int currentScreenScrollY = 0;

    // Media projection for screen capture
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;

    // Cache for performance optimization
    private Map<String, ImageViewInfo> cachedImageViews = new HashMap<>();
    private long lastViewCacheTime = 0;
    private static final long VIEW_CACHE_DURATION = 3000; // ms
    private boolean needsViewRefresh = true;

    // Classification threshold
    private static final int VISIBILITY_THRESHOLD = 60; // Only classify images >60% visible

    // Foreground notification
    private static final String NOTIF_CHANNEL_ID = "image_view_visibility_service_channel";
    private static final int NOTIF_ID = 1;

    // Receiver for commands from Activity
    private final BroadcastReceiver cmdReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            final String action = intent.getAction();
            if (ACTION_SET_ACTIVE.equals(action)) {
                boolean want = intent.getBooleanExtra(EXTRA_ACTIVE, false);
                Log.d(TAG, "ACTION_SET_ACTIVE: " + want);
                setActive(want);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());

        // Background thread for processing
        android.os.HandlerThread backgroundThread = new android.os.HandlerThread("ImageClassificationThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        // DO NOT start foreground here. Only when actually capturing screen.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String CHANNEL_ID = "image_view_visibility_service_channel";
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "ImageView Visibility Service",
                    NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                    .createNotificationChannel(channel);

            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Image Capture Service")
                    .setContentText("Actively monitoring screen for image views.")
                    .setSmallIcon(R.drawable.man)
                    .build();

            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            Log.d(TAG, "Service started in foreground from onCreate.");
        }
        stopMonitoringRunnable = () -> {
            isScrollMonitoring = false;
            Log.d(TAG, "Stopped scroll monitoring");
        };

        frameCallback = frameTimeNanos -> {
            if (active) {
                updateImageViewVisibilityFast();
                if (isScrollMonitoring) {
                    Choreographer.getInstance().postFrameCallback(frameCallback);
                }
            }
        };

        Log.d(TAG, "ImageViewAccessibilityService created");
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "onServiceConnected");

        // Receive commands from the Activity
        registerReceiver(cmdReceiver, new IntentFilter(ACTION_SET_ACTIVE));

        // If user intended it ON previously, try to resume (if we still have MP data)
        boolean userWantsActive = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(PREF_USER_WANTS_ACTIVE, false);
        if (userWantsActive) {
            Log.d(TAG, "User wants active. Attempting to activate.");
            setActive(true);
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "onUnbind - service disabled by user/system");
        // Stop all work but do NOT clear the user preference. On next enable, we can resume.
        setActive(false);
        try { unregisterReceiver(cmdReceiver); } catch (Throwable ignored) {}
        return super.onUnbind(intent);
    }

    // -------- Activation lifecycle --------

    private void setActive(boolean enable) {
        if (enable == active) {
            Log.d(TAG, "setActive: already " + enable);
            return;
        }

        if (enable) {
            // Ensure we have MediaProjection
            if (mediaProjection == null) {
                if (sMediaProjectionResultData != null) {
                    mediaProjection = mediaProjectionManager.getMediaProjection(
                            sMediaProjectionResultCode, sMediaProjectionResultData);
                }
            }

            if (mediaProjection == null) {
                Log.w(TAG, "MediaProjection not available. Asking Activity to request it.");
                // Ask the Activity to launch the permission dialog (it should listen for this)
                Intent i = new Intent(ACTION_NEEDS_MEDIA_PROJECTION);
                i.setPackage(getPackageName());
                sendBroadcast(i);
                // We'll try again after Activity grants it and updates sMediaProjectionResultData
                return;
            }

            // We’re good to start capturing
            setupScreenCapture();
            startOverlayAndLoop();
            active = true;
            Log.d(TAG, "Feature ACTIVATED");

        } else {
            // Deactivate feature
            active = false;
            stopOverlayAndLoop();
            teardownScreenCapture();
            Log.d(TAG, "Feature DEACTIVATED");
        }
    }

    private void startOverlayAndLoop() {
        // Start any overlay UI if you have it (overlayView)
        // if (overlayView == null) { overlayView = new OverlayView(this); windowManager.addView(...); }
        isScrollMonitoring = true;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void stopOverlayAndLoop() {
        isScrollMonitoring = false;
        try { Choreographer.getInstance().removeFrameCallback(frameCallback); } catch (Throwable ignored) {}
        // Remove overlay if you added it
        if (overlayView != null) { windowManager.removeView(overlayView); overlayView = null; }
    }

    // -------- MediaProjection & capture --------

    private void setupScreenCapture() {
        if (mediaProjection == null) {
            Log.e(TAG, "mediaProjection is null, cannot setup screen capture");
            return;
        }
        try {
            DisplayMetrics metrics = getResources().getDisplayMetrics();

            imageReader = ImageReader.newInstance(
                    metrics.widthPixels,
                    metrics.heightPixels,
                    PixelFormat.RGBA_8888,
                    2
            );

            imageReader.setOnImageAvailableListener(reader -> {
                if (!active) {
                    // Drain to avoid backpressure if needed
                    try {
                        Image img = reader.acquireLatestImage();
                        if (img != null) img.close();
                    } catch (Throwable ignored) {}
                    return;
                }
                backgroundHandler.post(() -> processAvailableImage(reader));
            }, backgroundHandler);

            createVirtualDisplay();

            // Now that we are actually capturing, run as foreground (Q+)
            startForegroundIfNeeded();

            Log.d(TAG, "Screen capture setup completed");
        } catch (Exception e) {
            Log.e(TAG, "Error setting up screen capture: " + e.getMessage(), e);
        }
    }

    private void createVirtualDisplay() {
        try {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenCapture",
                    metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, null
            );

            if (virtualDisplay != null) {
                Log.d(TAG, "Virtual display created");
            } else {
                Log.e(TAG, "Failed to create virtual display - null result");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating virtual display: " + e.getMessage(), e);
        }
    }

    private void teardownScreenCapture() {
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error releasing virtual display", t);
        }
        try {
            if (imageReader != null) {
                imageReader.setOnImageAvailableListener(null, null);
                imageReader.close();
                imageReader = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error closing imageReader", t);
        }
        try {
            if (mediaProjection != null) {
                mediaProjection.stop();
                mediaProjection = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error stopping mediaProjection", t);
        }
        stopForegroundIfNeeded();
    }

    private void startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL_ID, "ImageView Visibility Service", NotificationManager.IMPORTANCE_LOW
            );
            nm.createNotificationChannel(channel);

            Notification notification = new Notification.Builder(this, NOTIF_CHANNEL_ID)
                    .setContentTitle("Image Capture Service")
                    .setContentText("Monitoring screen for image views")
                    .setSmallIcon(R.drawable.man)
                    .build();

            try {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } catch (Throwable t) {
                // Fallback for older devices or if type not supported
                try {
                    startForeground(NOTIF_ID, notification);
                } catch (Throwable ignored) {}
            }
        }
    }

    private void stopForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { stopForeground(true); } catch (Throwable ignored) {}
        }
    }

    private void processAvailableImage(ImageReader reader) {
        Log.d(TAG, "Processing available image from reader");
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            Log.d(TAG, "Acquired latest image: " + (image != null ? "success" : "null"));
            if (image != null) {
                Bitmap screenBitmap = imageTobitmap(image);
                if (screenBitmap != null) {
                    Log.d(TAG, "Successfully converted image to bitmap, size: " + screenBitmap.getWidth() + "x" + screenBitmap.getHeight());
                    // Run classification on the background thread
                    processScreenCapture(screenBitmap);
                } else {
                    Log.w(TAG, "Failed to convert image to bitmap");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing captured image: " + e.getMessage(), e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private Bitmap imageTobitmap(Image image) {
        Log.d(TAG, "Converting image to bitmap...");
        try {
            Image.Plane[] planes = image.getPlanes();
            Log.d(TAG, "Image planes obtained: " + planes.length);
            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();
            
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();
            
            Bitmap bitmap = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride,
                image.getHeight(),
                Bitmap.Config.ARGB_8888
            );
            
            bitmap.copyPixelsFromBuffer(buffer);
            Bitmap result = Bitmap.createBitmap(bitmap, 0, 0, image.getWidth(), image.getHeight());
            Log.d(TAG, "Successfully created bitmap: " + result.getWidth() + "x" + result.getHeight());
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error converting image to bitmap: " + e.getMessage(), e);
            return null;
        }
    }

    private void processScreenCapture(Bitmap screenBitmap) {
        Log.d(TAG, "Starting screen capture processing with bitmap dimensions: " + screenBitmap.getWidth() + "x" + screenBitmap.getHeight());
        try {
            List<ImageViewInfo> toClassify = new ArrayList<>();
            synchronized (this) {
                Log.d(TAG, "Checking cached image views for classification. Total views: " + cachedImageViews.size());
                for (ImageViewInfo info : cachedImageViews.values()) {
                    if (info.visibilityPercentage > VISIBILITY_THRESHOLD && !info.isClassified) {
                        // Mark as processing by setting isClassified to false, but keep old text
                        info.isClassified = false;
                        toClassify.add(info);
                        Log.d(TAG, "Added view for classification: " + info.bounds + " (Visibility: " + info.visibilityPercentage + "%)");
                    }
                }
            }
            Log.d(TAG, "Found " + toClassify.size() + " views requiring classification");

            for (ImageViewInfo info : toClassify) {
                Log.d(TAG, "Cropping image for bounds: " + info.bounds);
                Bitmap croppedImage = cropImageFromScreen(screenBitmap, info.bounds);
                Log.d(TAG, "Crop result: " + (croppedImage != null ?
                    croppedImage.getWidth() + "x" + croppedImage.getHeight() : "null"));
                if (croppedImage == null) {
                    Log.w(TAG, "Cropped image is null for bounds: " + info.bounds);
                    continue;
                }
                // Create a clean, standalone copy of the bitmap. This can prevent
                // issues with native code that might not handle sub-bitmaps correctly.
                Bitmap imageToClassify = croppedImage.copy(Bitmap.Config.ARGB_8888, false);
                croppedImage.recycle(); // We have a copy, so we can recycle the original.

                if (imageToClassify == null) {
                    Log.w(TAG, "Failed to create a copy of the cropped image for classification.");
                    continue;
                }

                try {
                    Log.d(TAG, "Starting native ImageClassification for bounds: " + info.bounds);
                    String classificationResult = ImageClassification(imageToClassify, getAssets());
                    Log.d(TAG, "Classification completed. Raw result: " + classificationResult);
                    Log.d(TAG, "Classification result: " + classificationResult);
                    info.classificationResult = "true".equals(classificationResult);
                    info.isClassified = true;
                    info.classificationText = classificationResult;
                    info.lastClassificationTime = System.currentTimeMillis();
                    info.highestVisibilityPercentage = Math.max(info.highestVisibilityPercentage, info.visibilityPercentage);

                    Log.d(TAG, "Classified image at " + info.bounds + ": " + classificationResult +
                          " -> " + info.classificationResult);
                } catch (Exception e) {
                    Log.e(TAG, "Error calling ImageClassification: " + e.getMessage(), e);
                    info.classificationResult = false;
                    info.isClassified = true;
                    info.classificationText = "ERROR";
                    info.lastClassificationTime = System.currentTimeMillis();
                } finally {
                    if (imageToClassify != null) {
                        imageToClassify.recycle();
                    }
                }
            }

            if (!toClassify.isEmpty()) {
                // Post UI updates to the main thread
                final List<ImageViewInfo> imageViewsForOverlay = new ArrayList<>(cachedImageViews.values());
                handler.post(() -> updateOverlay(imageViewsForOverlay));
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing screen capture: " + e.getMessage(), e);
        } finally {
            if (screenBitmap != null && !screenBitmap.isRecycled()) {
                screenBitmap.recycle();
            }
        }
    }

    private Bitmap cropImageFromScreen(Bitmap screenBitmap, Rect bounds) {
        try {
            int left = Math.max(0, bounds.left);
            int top = Math.max(0, bounds.top);
            int right = Math.min(screenBitmap.getWidth(), bounds.right);
            int bottom = Math.min(screenBitmap.getHeight(), bounds.bottom);
            
            int width = right - left;
            int height = bottom - top;
            
            if (width > 0 && height > 0) {
                return Bitmap.createBitmap(screenBitmap, left, top, width, height);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error cropping image: " + e.getMessage(), e);
        }
        return null;
    }

    private void triggerScreenCapture() {
        // Check if mediaProjection is already set up. If not, try to set it up.
        if (mediaProjection == null && sMediaProjectionResultData != null) {
            Log.d(TAG, "triggerScreenCapture: MediaProjection not set up, attempting now.");
            mediaProjection = mediaProjectionManager.getMediaProjection(sMediaProjectionResultCode, sMediaProjectionResultData);
            if (mediaProjection != null) {
                Log.d(TAG, "triggerScreenCapture: MediaProjection obtained, setting up screen capture.");
                setupScreenCapture();
            } else {
                Log.e(TAG, "triggerScreenCapture: Failed to obtain MediaProjection.");
            }
        } else if (mediaProjection == null) {
            Log.w(TAG, "triggerScreenCapture: Cannot start capture, MediaProjection data not available.");
        } else {
            // With continuous capture, this method just acknowledges that the
            // view hierarchy has changed. The next frame processed will use the
            // updated view information.
            Log.d(TAG, "View hierarchy changed, awaiting next frame for classification.");
        }
    }

    private String getEventTypeName(int eventType) {
        switch (eventType) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                return "TYPE_WINDOW_STATE_CHANGED";
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                return "TYPE_WINDOW_CONTENT_CHANGED";
            case AccessibilityEvent.TYPE_VIEW_SCROLLED:
                return "TYPE_VIEW_SCROLLED";
            case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                return "TYPE_VIEW_FOCUSED";
            case AccessibilityEvent.TYPE_VIEW_SELECTED:
                return "TYPE_VIEW_SELECTED";
            default:
                return "Unknown event type: " + eventType;
        }
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
  if (!active) {
   return;
  }
        int eventType = event.getEventType();
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) {
            
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
                if (!packageName.equals(currentPackageName)) {
                    currentPackageName = packageName;
                    needsViewRefresh = true;
                    handler.postDelayed(this::detectFixedElements, 100);
                }
                needsViewRefresh = true;
                updateImageViewVisibility();
            } else if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                       eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                       eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) {
                detectFixedElements();
                needsViewRefresh = true;
                updateImageViewVisibility();
            } else if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                updateImageViewVisibilityFast();
                
                handler.removeCallbacks(stopMonitoringRunnable);
                handler.postDelayed(stopMonitoringRunnable, SCROLL_MONITOR_DURATION);
                
                if (!isScrollMonitoring) {
                    isScrollMonitoring = true;
                    Log.d(TAG, "Started scroll monitoring");
                    Choreographer.getInstance().postFrameCallback(frameCallback);
                }
            }
        }
    }

    private void updateImageViewVisibilityFast() {
        if (isUpdating || cachedImageViews.isEmpty()) {
            return;
        }
        
        try {
            List<Rect> fixedElements = cachedFixedElements.get(currentPackageName);
            if (fixedElements == null) {
                fixedElements = new ArrayList<>();
            }
            
            boolean needsClassification = false;
            for (ImageViewInfo info : cachedImageViews.values()) {
                int oldVisibility = info.visibilityPercentage;
                info.visibilityPercentage = calculateVisibility(info.bounds, fixedElements);
                
                if (info.visibilityPercentage > info.highestVisibilityPercentage * 1.1) {
                   info.highestVisibilityPercentage = info.visibilityPercentage;
                   if (info.isClassified) {
                       info.isClassified = false;
                       needsClassification = true;
                   }
                }
                if (!info.isClassified && info.visibilityPercentage > VISIBILITY_THRESHOLD) {
                    needsClassification = true;
                }
            }

            updateOverlay(new ArrayList<>(cachedImageViews.values()));
            
            if (needsClassification) {
                triggerScreenCapture();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error in updateImageViewVisibilityFast: " + e.getMessage(), e);
        }
    }

    private void updateImageViewVisibility() {
        if (isUpdating) return;
        
        long now = System.currentTimeMillis();
        if (!needsViewRefresh && !cachedImageViews.isEmpty() && 
            (now - lastViewCacheTime) < VIEW_CACHE_DURATION) {
            updateImageViewVisibilityFast();
            return;
        }
        
        isUpdating = true;
        
        handler.post(() -> {
            AccessibilityNodeInfo rootNode = null;
            try {
                rootNode = getRootInActiveWindow();
                if (rootNode == null) {
                    Log.w(TAG, "Root node is null");
                    isUpdating = false;
                    return;
                }

                Map<String, ImageViewInfo> imageViews = new HashMap<>();
                Set<Rect> seen = new HashSet<>();
                Set<AccessibilityNodeInfo> visited = new HashSet<>();
                
                findAllViewsEnhanced(rootNode, imageViews, seen, visited, 0, "r");
                
                Log.d(TAG, "Found " + imageViews.size() + " Views (ImageViews/SurfaceViews/VideoViews) in hierarchy");
                
                List<Rect> fixedElements = cachedFixedElements.get(currentPackageName);
                if (fixedElements == null) {
                    fixedElements = new ArrayList<>();
                }
                
                boolean needsClassification = false;
                for (ImageViewInfo info : imageViews.values()) {
                    info.visibilityPercentage = calculateVisibility(info.bounds, fixedElements);
                    
                    if (info.visibilityPercentage > VISIBILITY_THRESHOLD) {
                        ImageViewInfo existing = cachedImageViews.get(info.childPath);
                        if (existing == null || !existing.isClassified) {
                            needsClassification = true;
                        } else if (existing.isClassified) {
                           // Previously classified, check if it needs re-classification
                           if (System.currentTimeMillis() - existing.lastClassificationTime > VIEW_CACHE_DURATION) {
                               // Only reprocess if visibility has increased by at least 10%
                               if (info.visibilityPercentage > existing.highestVisibilityPercentage * 1.1) {
                                   needsClassification = true;
                                   info.isClassified = false; // Will be picked up by processScreenCapture
                               }
                           }
                        }
                    }
                }

                cachedImageViews = imageViews;
                lastViewCacheTime = System.currentTimeMillis();
                needsViewRefresh = false;

                updateOverlay(new ArrayList<>(imageViews.values()));
                
                if (needsClassification) {
                    triggerScreenCapture();
                }
                
                for (AccessibilityNodeInfo node : visited) {
                    try {
                        node.recycle();
                    } catch (Exception e) {
                        Log.w(TAG, "Error recycling node: " + e.getMessage());
                    }
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error in updateImageViewVisibility: " + e.getMessage(), e);
            } finally {
                if (rootNode != null) {
                    try {
                        rootNode.recycle();
                    } catch (Exception e) {
                        Log.w(TAG, "Error recycling root node: " + e.getMessage());
                    }
                }
                isUpdating = false;
            }
        });
    }

    // ... [Keep all the existing helper methods: findAllViewsEnhanced, isTargetViewType, etc.] ...
    private void findAllViewsEnhanced(AccessibilityNodeInfo node, Map<String, ImageViewInfo> imageViews,
                                      Set<Rect> seen, Set<AccessibilityNodeInfo> visited, int depth, String path) {
        if (node == null || visited.contains(node) || depth > 30) {
            return;
        }
        
        visited.add(node);
        
        try {
            String className = node.getClassName() != null ? node.getClassName().toString() : "";
            boolean isTargetView = isTargetViewType(className);
            boolean isVisibile = node.isVisibleToUser();
            if (isTargetView && isVisibile) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                
                if (isValidView(bounds) && !seen.contains(bounds)) {
                    int scrollX = 0, scrollY = 0;
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            AccessibilityNodeInfo parent = node.getParent();
                            while (parent != null && scrollX == 0 && scrollY == 0) {
                                if (parent.isScrollable()) {
                                    scrollX = currentScreenScrollX;
                                    scrollY = currentScreenScrollY;
                                    break;
                                }
                                AccessibilityNodeInfo nextParent = parent.getParent();
                                parent.recycle();
                                parent = nextParent;
                            }
                            if (parent != null) {
                                parent.recycle();
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error getting scroll info: " + e.getMessage());
                        scrollX = currentScreenScrollX;
                        scrollY = currentScreenScrollY;
                    }
                    
                    ImageViewInfo info = new ImageViewInfo(bounds, className, path);
                    info.scrollX = scrollX;
                    info.scrollY = scrollY;
                    
                    ImageViewInfo existing = cachedImageViews.get(path);
                    if (existing != null) {
                        info.isClassified = existing.isClassified;
                        info.classificationResult = existing.classificationResult;
                        info.classificationText = existing.classificationText;
                        info.lastClassificationTime = existing.lastClassificationTime;
                        info.highestVisibilityPercentage = Math.max(existing.highestVisibilityPercentage, info.visibilityPercentage);
                    } else {
                       info.highestVisibilityPercentage = info.visibilityPercentage;
                    }

                    imageViews.put(path, info);
                    seen.add(new Rect(bounds));
                    Log.d(TAG, "Added valid " + className + " with path " + path + " and bounds: " + bounds.toString() +
                          " ScrollX: " + scrollX + " ScrollY: " + scrollY + " Depth: " + depth);
                }
            }
            
            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = null;
                try {
                    child = node.getChild(i);
                    if (child != null) {
                        findAllViewsEnhanced(child, imageViews, seen, visited, depth + 1, path + "-" + i);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error accessing child " + i + " at depth " + depth + ": " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing node at depth " + depth + ": " + e.getMessage(), e);
        }
    }

    private boolean isTargetViewType(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        
        return "android.widget.ImageView".equals(className) ||
               "android.view.SurfaceView".equals(className) ||
               "android.widget.VideoView".equals(className) ||
               className.contains("VideoView") || 
               className.contains("Player");
    }

    private boolean isValidView(Rect bounds) {
        if (bounds.isEmpty()) {
            return false;
        }
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int minSizePx = (int) (MIN_IMAGE_SIZE_DP * metrics.density);

        int width = bounds.width();
        int height = bounds.height();
        int area = width * height;
        int minArea = minSizePx * minSizePx;
        
        if (width < minSizePx || height < minSizePx || area < minArea) {
            return false;
        }
        
        if (bounds.left < -metrics.widthPixels || bounds.top < -metrics.heightPixels ||
            bounds.right > metrics.widthPixels * 2 || bounds.bottom > metrics.heightPixels * 2) {
            Log.w(TAG, "View bounds seem unreasonable: " + bounds.toString());
            return false;
        }
        return true;
    }

    private void detectFixedElements() {
        long now = System.currentTimeMillis();
        if (now - lastFixedDetectTime < FIXED_DETECT_DEBOUNCE) {
            return;
        }
        lastFixedDetectTime = now;

        AccessibilityNodeInfo rootNode = null;
        try {
            rootNode = getRootInActiveWindow();
            if (rootNode == null) return;

            List<Rect> fixedElements = new ArrayList<>();
            Set<AccessibilityNodeInfo> visited = new HashSet<>();
            
            findFixedElementsEnhanced(rootNode, fixedElements, visited, 0);
            
            Rect statusBar = getStatusBarBounds();
            if (statusBar != null) {
                fixedElements.add(statusBar);
            }
            
            Rect navBar = getNavigationBarBounds();
            if (navBar != null) {
                fixedElements.add(navBar);
            }
            
            cachedFixedElements.put(currentPackageName, fixedElements);
            
            for (AccessibilityNodeInfo node : visited) {
                try {
                    node.recycle();
                } catch (Exception e) {
                    Log.w(TAG, "Error recycling fixed element node: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in detectFixedElements: " + e.getMessage(), e);
        } finally {
            if (rootNode != null) {
                try {
                    rootNode.recycle();
                } catch (Exception e) {
                    Log.w(TAG, "Error recycling root node in detectFixedElements: " + e.getMessage());
                }
            }
        }
    }

    private void findFixedElementsEnhanced(AccessibilityNodeInfo node, List<Rect> fixedElements, Set<AccessibilityNodeInfo> visited, int depth) {
        if (node == null || visited.contains(node) || depth > 30) return;
        
        visited.add(node);
        
        try {
            String className = node.getClassName() != null ? node.getClassName().toString() : "";
            
            boolean isFixed = className.contains("Toolbar") ||
                              className.contains("AppBar") ||
                              className.contains("ActionBar") ||
                              className.contains("TabLayout") ||
                              className.contains("BottomNavigation") ||
                              className.contains("NavigationView") ||
                              className.contains("StatusBar");
            
            if (!isFixed) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                
                if (bounds.top <= 0 && bounds.width() >= metrics.widthPixels * 0.9 && bounds.height() < 200) {
                    isFixed = true;
                }
                
                if (bounds.bottom >= metrics.heightPixels - 10 && bounds.width() >= metrics.widthPixels * 0.9 && bounds.height() < 200) {
                    isFixed = true;
                }
            }
            
            if (isFixed) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (!bounds.isEmpty()) {
                    fixedElements.add(bounds);
                }
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    findFixedElementsEnhanced(child, fixedElements, visited, depth + 1);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in findFixedElementsEnhanced: " + e.getMessage(), e);
        }
    }

    private int calculateVisibility(Rect imageBounds, List<Rect> fixedElements) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        Rect screenBounds = new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);

        Rect visibleRect = new Rect(imageBounds);
        if (!visibleRect.intersect(screenBounds)) {
            return 0;
        }

        int totalArea = imageBounds.width() * imageBounds.height();
        int visibleArea = visibleRect.width() * visibleRect.height();

        Rect unionOverlap = new Rect();
        for (Rect fixed : fixedElements) {
            if (Rect.intersects(visibleRect, fixed)) {
                Rect overlap = new Rect();
                overlap.setIntersect(visibleRect, fixed);
                unionOverlap.union(overlap);
            }
        }
        if (!unionOverlap.isEmpty()) {
            int overlapArea = unionOverlap.width() * unionOverlap.height();
            visibleArea = Math.max(0, visibleArea - overlapArea);
        }

        return totalArea > 0 ? Math.max(0, Math.min(100, (visibleArea * 100) / totalArea)) : 0;
    }

    private Rect getStatusBarBounds() {
        int statusBarHeight = getStatusBarHeight();
        if (statusBarHeight > 0) {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            return new Rect(0, 0, metrics.widthPixels, statusBarHeight);
        }
        return null;
    }

    private Rect getNavigationBarBounds() {
        int navBarHeight = getNavigationBarHeight();
        if (navBarHeight > 0) {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            return new Rect(0, metrics.heightPixels - navBarHeight, metrics.widthPixels, metrics.heightPixels);
        }
        return null;
    }

    private int getStatusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return getResources().getDimensionPixelSize(resourceId);
        }
        return 0;
    }

    private int getNavigationBarHeight() {
        int resourceId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return getResources().getDimensionPixelSize(resourceId);
        }
        return 0;
    }

    private void updateOverlay(List<ImageViewInfo> imageViews) {
        if (overlayView == null) {
            createOverlay();
        }
        
        if (overlayView != null) {
            overlayView.updateImageViews(imageViews);
        }
    }
    private void clearOverlay() {
        if (overlayView != null) {
            overlayView.updateImageViews(new ArrayList<>());
        }
    }
    
    private void createOverlay() {
        overlayView = new OverlayView(this);
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_FULLSCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                PixelFormat.TRANSLUCENT
        );
        
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = 0;
        params.y = 0;
        
        try {
            windowManager.addView(overlayView, params);
        } catch (Exception e) {
            Log.e(TAG, "Error creating overlay: " + e.getMessage(), e);
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service interrupted");
        if (isScrollMonitoring) {
            isScrollMonitoring = false;
            Choreographer.getInstance().removeFrameCallback(frameCallback);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service destroyed");
  active = false;
        
        if (isScrollMonitoring) {
            isScrollMonitoring = false;
            Choreographer.getInstance().removeFrameCallback(frameCallback);
        }
        
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
        
        if (mediaProjection != null) {
            mediaProjection.stop();
        }
        
        if (imageReader != null) {
            imageReader.close();
        }
        
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                Log.e(TAG, "Error removing overlay: " + e.getMessage(), e);
            }
        }
        
        if (backgroundHandler != null) {
            backgroundHandler.getLooper().quitSafely();
        }
  stopForeground(true);
  clearOverlay();
    }

    // Native method for image classification
    public native String ImageClassification(Bitmap bitmapIn, AssetManager assetManager);
    
    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("imageclassification");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library: " + e.getMessage());
        }
    }

    static class ImageViewInfo {
        Rect bounds;
        int visibilityPercentage;
        String nodeType;
        int scrollX;
        int scrollY;
        boolean classificationResult;
        boolean isClassified;
        String classificationText;
        String childPath;
        long lastClassificationTime;
        int highestVisibilityPercentage;

        ImageViewInfo(Rect bounds, String nodeType, String childPath) {
            this.bounds = new Rect(bounds);
            this.visibilityPercentage = 0;
            this.nodeType = nodeType;
            this.scrollX = 0;
            this.scrollY = 0;
            this.classificationResult = false;
            this.isClassified = false;
            this.classificationText = "";
            this.childPath = childPath;
            this.lastClassificationTime = 0;
            this.highestVisibilityPercentage = 0;
        }
        
        @Override
        public String toString() {
            return "ImageViewInfo{" +
                    "bounds=" + bounds +
                    ", visibilityPercentage=" + visibilityPercentage +
                    ", nodeType='" + nodeType + '\'' +
                    ", scrollX=" + scrollX +
                    ", scrollY=" + scrollY +
                    ", classificationResult=" + classificationResult +
                    ", isClassified=" + isClassified +
                    ", classificationText='" + classificationText + '\'' +
                    ", childPath='" + childPath + '\'' +
                    ", lastClassificationTime=" + lastClassificationTime +
                    ", highestVisibilityPercentage=" + highestVisibilityPercentage +
                    '}';
        }
    }
}