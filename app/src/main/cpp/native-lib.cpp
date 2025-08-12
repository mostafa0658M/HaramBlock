#include <jni.h>
#include <string>
#include <android/bitmap.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include "inference.h"

/**
 * @brief Convert input image from bitmap to OpenCV Mat.
 * @tparam env: JNIEnv pointer.
 * @tparam bitmap: input image in bitmap format.
 * @param bst: output input image in OpenCV format.
 * @param needUnPremultiplyAlpha: boolean variable to convert image color space
 */
void Bitmap2Mat(JNIEnv * env, jobject bitmap, 
                cv::Mat& dst, jboolean needUnPremultiplyAlpha) {
    AndroidBitmapInfo  info;    // uses jnigraphics
    void*              pixels = 0;

    CV_Assert( AndroidBitmap_getInfo(env, bitmap, &info) >= 0 );
    CV_Assert( info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 ||
               info.format == ANDROID_BITMAP_FORMAT_RGB_565 );
    CV_Assert( AndroidBitmap_lockPixels(env, bitmap, &pixels) >= 0 );
    CV_Assert( pixels );
    
    if( info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 ) {
        dst = cv::Mat(info.height, info.width, CV_8UC4, pixels);
        if(needUnPremultiplyAlpha) {
            cvtColor(dst, dst, cv::COLOR_mRGBA2RGBA);
        }
    } else {
        cv::Mat tmp(info.height, info.width, CV_8UC2, pixels);
        cvtColor(tmp, dst, cv::COLOR_BGR5652RGBA);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return;
}

/**
 * @brief Entry point of C++ code and MainActivity.java will call this function.
 * @tparam env: JNIEnv pointer.
 * @tparam bitmapIn: input image in bitmap format.
 * @param assetManager: AssetManager object for loading NCNN model files in assets folder.
 * @Return predicted class.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_haram_block_ImageViewAccessibilityService_ImageClassification(
        JNIEnv* env,
        jobject,
        jobject bitmapIn,
        jobject assetManager) {
    
    // Convert bitmap to OpenCV Mat without resizing
    cv::Mat src;
    Bitmap2Mat(env, bitmapIn, src, false);   // Convert bitmap to OpenCV Mat
    
    // Don't resize here - let the Inference function handle the appropriate resizing
    
    // Convert RGBA to BGR (OpenCV's default format)
    cv::Mat bgr_src;
    if (src.channels() == 4) {
        cv::cvtColor(src, bgr_src, cv::COLOR_RGBA2BGR);
    } else if (src.channels() == 3) {
        cv::cvtColor(src, bgr_src, cv::COLOR_RGB2BGR);
    } else {
        bgr_src = src.clone();
    }

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    std::string pred_class = Inference(bgr_src, mgr);    // Image classification
    return env->NewStringUTF(pred_class.c_str());
}