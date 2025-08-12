// Optimized OverlayView.java
package com.haram.block;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import android.os.Build;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;

public class OverlayView extends View {
    private static final String TAG = "OverlayView";
    private static final boolean DEBUG_LOGGING = false;
    private Paint rectPaint;
    private Paint textPaint;
    private Paint typePaint;
    private Paint blockedPaint;
    // Use thread-safe list for concurrent access
    private volatile List<ImageViewAccessibilityService.ImageViewInfo> imageViews = new CopyOnWriteArrayList<>();
    private volatile boolean isDrawing = false;
    private Handler mainHandler;
    
    // Performance optimization
    private static final long MIN_REDRAW_INTERVAL = 16; // ~60 FPS max for overlay
    private long lastDrawTime = 0;
    private boolean pendingUpdate = false;
    
    // Pre-calculated text strings to avoid allocations during draw
    private static final String TEXT_TRUE = "TRUE";
    private static final String TEXT_FALSE = "FALSE";
    private static final String TEXT_BLOCKED = "blocked";
    private static final String TEXT_PROCESSING = "PROCESSING...";
    private static final String TEXT_VIDEO = "VIDEO";
    private static final String TEXT_SURFACE = "SURFACE";

    public OverlayView(Context context) {
        super(context);
        
        mainHandler = new Handler(Looper.getMainLooper());
        
        // Optimize paint objects - disable anti-aliasing for better performance
        rectPaint = new Paint();
        rectPaint.setStyle(Paint.Style.STROKE);
        rectPaint.setStrokeWidth(3);
        rectPaint.setDither(false);
        
        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(20);
        textPaint.setShadowLayer(1, 0, 1, Color.BLACK);
        textPaint.setDither(false);
        textPaint.setSubpixelText(false); // Disable for better performance

        typePaint = new Paint(textPaint);
        typePaint.setTextSize(14);
        typePaint.setColor(Color.YELLOW);

        blockedPaint = new Paint();
        blockedPaint.setStyle(Paint.Style.FILL);
        blockedPaint.setColor(Color.BLACK);
        blockedPaint.setAlpha(242); // 95% opacity
        
        // Use hardware acceleration for better performance
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        
        // Disable unnecessary view operations
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
    }

    public void updateImageViews(List<ImageViewAccessibilityService.ImageViewInfo> newImageViews) {
        // Throttle updates to maintain performance
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastDrawTime < MIN_REDRAW_INTERVAL) {
            pendingUpdate = true;
            mainHandler.postDelayed(() -> {
                if (pendingUpdate) {
                    pendingUpdate = false;
                    performUpdate(newImageViews);
                }
            }, MIN_REDRAW_INTERVAL);
        } else {
            performUpdate(newImageViews);
        }
    }
    
    private void performUpdate(List<ImageViewAccessibilityService.ImageViewInfo> newImageViews) {
        // Direct assignment for thread-safe list
        this.imageViews = new CopyOnWriteArrayList<>(newImageViews);
        
        // Only invalidate if we're on the UI thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            invalidate();
        } else {
            postInvalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (isDrawing) {
            return; // Skip if already drawing
        }
        
        isDrawing = true;
        lastDrawTime = System.currentTimeMillis();
        
        try {
            super.onDraw(canvas);
            
            // Clear canvas more efficiently
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            
            // Direct iteration without copying (thread-safe list)
            for (ImageViewAccessibilityService.ImageViewInfo info : imageViews) {
                if (info != null && info.bounds != null && info.isClassified && !info.classificationResult) {
                    drawImageViewOverlay(canvas, info);
                }
            }
            
        } catch (Exception e) {
            if (DEBUG_LOGGING) Log.e(TAG, "Error in onDraw: " + e.getMessage());
        } finally {
            isDrawing = false;
        }
    }
    
    private void drawImageViewOverlay(Canvas canvas, ImageViewAccessibilityService.ImageViewInfo info) {
        Rect bounds = info.bounds;
        
        // Skip if bounds are invalid
        if (bounds.isEmpty()) {
            return;
        }
        
        // Draw solid black box with 95% opacity
        canvas.drawRect(bounds, blockedPaint);

        // Only draw text if bounds are large enough
        if (bounds.width() < 50 || bounds.height() < 30) {
            return;
        }

        // Calculate text position
        float textWidth = textPaint.measureText(TEXT_BLOCKED);
        float textX = bounds.centerX() - (textWidth / 2);
        float textY = bounds.centerY() + (textPaint.getTextSize() / 3);

        // Clamp text position
        textX = Math.max(bounds.left + 5, Math.min(textX, bounds.right - textWidth - 5));
        textY = Math.max(bounds.top + textPaint.getTextSize(), Math.min(textY, bounds.bottom - 5));

        canvas.drawText(TEXT_BLOCKED, textX, textY, textPaint);
    }
    
    private int getOverlayColor(ImageViewAccessibilityService.ImageViewInfo info) {
        // This method is no longer used for drawing the blocked overlay,
        // but we can keep it for potential future use or other overlay types.
        if (info.isClassified && !info.classificationResult) {
            return Color.BLACK; // Blocked
        }
        
        // Quick checks first
        if (info.nodeType != null) {
            if (info.nodeType.contains("Video")) return Color.RED;
            if (info.nodeType.contains("Surface")) return Color.BLUE;
        }

        // Visibility-based coloring
        if (info.visibilityPercentage > 60) {
            if (info.isClassified) {
                return info.classificationResult ? Color.GREEN : Color.RED;
            }
            return Color.YELLOW;
        } else if (info.visibilityPercentage >= 25) {
            return Color.MAGENTA;
        } else {
            return Color.GRAY;
        }
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
    }
}