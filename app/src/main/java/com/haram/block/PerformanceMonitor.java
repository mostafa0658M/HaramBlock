package com.haram.block;

import android.util.Log;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Performance monitoring utility for tracking FPS and frame processing times
 */
public class PerformanceMonitor {
    private static final String TAG = "PerformanceMonitor";
    private static final long FPS_WINDOW_MS = 1000; // 1 second window for FPS calculation
    
    private final AtomicLong lastReportTime = new AtomicLong(0);
    private final AtomicInteger frameCount = new AtomicInteger(0);
    private final AtomicInteger droppedFrames = new AtomicInteger(0);
    private final AtomicLong totalProcessingTime = new AtomicLong(0);
    private final AtomicLong minProcessingTime = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxProcessingTime = new AtomicLong(0);
    
    private volatile float currentFps = 0;
    private volatile long averageProcessingTime = 0;
    
    /**
     * Record a processed frame
     * @param processingTimeMs Time taken to process the frame in milliseconds
     */
    public void recordFrame(long processingTimeMs) {
        frameCount.incrementAndGet();
        totalProcessingTime.addAndGet(processingTimeMs);
        
        // Update min/max
        long currentMin = minProcessingTime.get();
        if (processingTimeMs < currentMin) {
            minProcessingTime.compareAndSet(currentMin, processingTimeMs);
        }
        
        long currentMax = maxProcessingTime.get();
        if (processingTimeMs > currentMax) {
            maxProcessingTime.compareAndSet(currentMax, processingTimeMs);
        }
        
        checkAndReport();
    }
    
    /**
     * Record a dropped frame
     */
    public void recordDroppedFrame() {
        droppedFrames.incrementAndGet();
    }
    
    /**
     * Check if it's time to report FPS and reset counters
     */
    private void checkAndReport() {
        long currentTime = System.currentTimeMillis();
        long lastReport = lastReportTime.get();
        
        if (currentTime - lastReport >= FPS_WINDOW_MS) {
            if (lastReportTime.compareAndSet(lastReport, currentTime)) {
                calculateAndReport(currentTime - lastReport);
            }
        }
    }
    
    /**
     * Calculate FPS and report performance metrics
     */
    private void calculateAndReport(long elapsedMs) {
        int frames = frameCount.getAndSet(0);
        int dropped = droppedFrames.getAndSet(0);
        long totalTime = totalProcessingTime.getAndSet(0);
        long minTime = minProcessingTime.getAndSet(Long.MAX_VALUE);
        long maxTime = maxProcessingTime.getAndSet(0);
        
        if (frames > 0) {
            currentFps = (frames * 1000.0f) / elapsedMs;
            averageProcessingTime = totalTime / frames;
            
            String report = String.format(
                "FPS: %.1f | Frames: %d | Dropped: %d | Avg: %dms | Min: %dms | Max: %dms",
                currentFps, frames, dropped, averageProcessingTime,
                minTime == Long.MAX_VALUE ? 0 : minTime, maxTime
            );
            
            Log.i(TAG, report);
            
            // Warn if FPS is below target
            if (currentFps < 28) { // Allow small margin below 30fps
                Log.w(TAG, "Performance warning: FPS below target (30)");
            }
            
            // Warn if too many frames are being dropped
            if (dropped > frames * 0.1) { // More than 10% dropped
                Log.w(TAG, "Performance warning: High frame drop rate (" + dropped + "/" + (frames + dropped) + ")");
            }
        }
    }
    
    /**
     * Get current FPS
     */
    public float getCurrentFps() {
        return currentFps;
    }
    
    /**
     * Get average processing time in milliseconds
     */
    public long getAverageProcessingTime() {
        return averageProcessingTime;
    }
    
    /**
     * Check if performance is meeting target (30 FPS)
     */
    public boolean isMeetingTarget() {
        return currentFps >= 28; // Allow small margin
    }
    
    /**
     * Get performance summary string
     */
    public String getPerformanceSummary() {
        return String.format(
            "Current FPS: %.1f | Avg Processing: %dms | Target Met: %s",
            currentFps, averageProcessingTime, isMeetingTarget() ? "Yes" : "No"
        );
    }
    
    /**
     * Reset all counters
     */
    public void reset() {
        lastReportTime.set(System.currentTimeMillis());
        frameCount.set(0);
        droppedFrames.set(0);
        totalProcessingTime.set(0);
        minProcessingTime.set(Long.MAX_VALUE);
        maxProcessingTime.set(0);
        currentFps = 0;
        averageProcessingTime = 0;
    }
}