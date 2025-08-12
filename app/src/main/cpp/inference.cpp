#include <jni.h>
#include <string>
#include <android/bitmap.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <ncnn/net.h>
#include <chrono>
#include <vector>
#include <algorithm>
#include <cmath>
#include <sstream>
#include <iomanip>

// Global variables for models
static ncnn::Net face_net;
static ncnn::Net gender_net;
static bool models_loaded = false;

// Model configurations
const int FD_MODEL_WIDTH = 160;
const int FD_MODEL_HEIGHT = 120;
const int GENDER_MODEL_WIDTH = 32;
const int GENDER_MODEL_HEIGHT = 32;
const float FACE_THRESHOLD = 0.50f;
const float GENDER_CONF_THRESH = 0.9f;
const float IOU_THRESHOLD = 0.3f;

// Structure for detection results
struct Detection {
    float x1, y1, x2, y2;
    float score;
};


// Initialize both models
std::string initialize_models(AAssetManager* mgr) {
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Init: Starting model initialization");
    
    if (models_loaded) {
        __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Init: Models already loaded");
        return "SUCCESS";
    }
    
    if (!mgr) {
        __android_log_print(ANDROID_LOG_ERROR, "ImageClassification", "Init: Asset manager is null");
        return "ERROR: Asset manager is null";
    }
    
    // Load face detection model
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Init: Loading face detection model");
    face_net.opt.use_vulkan_compute = false;
    face_net.opt.num_threads = 1;  // Single thread for detection
    
    int ret = face_net.load_param(mgr, "quant.param");
    if (ret) {
        __android_log_print(ANDROID_LOG_ERROR, "ImageClassification", "Init: Failed to load face detection param, error code: %d", ret);
        return "Failed to load face detection param";
    }
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Init: Face detection param loaded successfully");
    
    ret = face_net.load_model(mgr, "quant.bin");
    if (ret) {
        __android_log_print(ANDROID_LOG_ERROR, "ImageClassification", "Init: Failed to load face detection bin, error code: %d", ret);
        return "Failed to load face detection bin";
    }
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Init: Face detection model loaded successfully");
    
    // Load gender classification model
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Init: Loading gender classification model");
    gender_net.opt.use_vulkan_compute = false;
    gender_net.opt.num_threads = 1; 
    
    ret = gender_net.load_param(mgr, "gender.param");
    if (ret) {
        __android_log_print(ANDROID_LOG_ERROR, "ImageClassification", "Init: Failed to load gender param, error code: %d", ret);
        return "Failed to load gender param";
    }
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Init: Gender param loaded successfully");
    
    ret = gender_net.load_model(mgr, "gender.bin");
    if (ret) {
        __android_log_print(ANDROID_LOG_ERROR, "ImageClassification", "Init: Failed to load gender bin, error code: %d", ret);
        return "Failed to load gender bin";
    }
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Init: Gender model loaded successfully");
    
    models_loaded = true;
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Init: All models initialized successfully");
    return "SUCCESS";
}

// Fast NMS implementation with timeout protection
std::vector<Detection> hard_nms(std::vector<Detection>& detections, float iou_threshold) {
    if (detections.empty()) return {};
    
    auto start_time = std::chrono::high_resolution_clock::now();
    const int max_nms_time = 1000; // 1 second max for NMS
    
    // Sort by score
    std::sort(detections.begin(), detections.end(),
              [](const Detection& a, const Detection& b) { return a.score < b.score; });
    
    std::vector<Detection> picked;
    std::vector<bool> suppressed(detections.size(), false);
    
    for (int i = detections.size() - 1; i >= 0; --i) {
        // Check timeout periodically
        auto current_time = std::chrono::high_resolution_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(current_time - start_time);
        if (elapsed.count() > max_nms_time) {
            __android_log_print(ANDROID_LOG_WARN, "hard_nms", "NMS timeout, returning partial results");
            break;
        }
        
        if (suppressed[i]) continue;
        
        const Detection& current = detections[i];
        picked.push_back(current);
        
        float current_area = (current.x2 - current.x1) * (current.y2 - current.y1);
        
        for (int j = i - 1; j >= 0; --j) {
            if (suppressed[j]) continue;
            
            const Detection& test = detections[j];
            
            float xx1 = std::max(current.x1, test.x1);
            float yy1 = std::max(current.y1, test.y1);
            float xx2 = std::min(current.x2, test.x2);
            float yy2 = std::min(current.y2, test.y2);
            
            float w = std::max(0.0f, xx2 - xx1);
            float h = std::max(0.0f, yy2 - yy1);
            float inter = w * h;
            
            float test_area = (test.x2 - test.x1) * (test.y2 - test.y1);
            float iou = inter / (current_area + test_area - inter + 1e-5f);
            
            if (iou > iou_threshold) {
                suppressed[j] = true;
            }
        }
    }
    
    __android_log_print(ANDROID_LOG_INFO, "hard_nms", "NMS completed: %zu detections from %zu original",
                       picked.size(), detections.size());
    return picked;
}

// Process face detection output
std::vector<Detection> process_face_output(ncnn::Mat& conf_mat, ncnn::Mat& box_mat,
                                         int img_width, int img_height) {
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "FaceOutput: Starting face output processing");
    std::vector<Detection> detections;
    
    const float* conf_data = conf_mat.channel(0);
    const float* box_data = box_mat.channel(0);
    
    int num_anchors = conf_mat.h;
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "FaceOutput: Processing %d anchors", num_anchors);
    
    // Add timeout protection for face output processing
    auto face_output_start = std::chrono::high_resolution_clock::now();
    const int max_face_output_time = 3000; // 3 seconds max for face output processing
    
    for (int i = 0; i < num_anchors; ++i) {
        // Check timeout periodically
        auto current_time = std::chrono::high_resolution_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(current_time - face_output_start);
        if (elapsed.count() > max_face_output_time) {
            __android_log_print(ANDROID_LOG_WARN, "ImageClassification", "FaceOutput: Timeout during anchor processing, returning partial results");
            break;
        }
        
        // Get confidence for face class (index 1)
        float conf = conf_data[i * 2 + 1];
        
        if (conf > FACE_THRESHOLD) {
            Detection det;
            det.x1 = box_data[i * 4 + 0] * img_width;
            det.y1 = box_data[i * 4 + 1] * img_height;
            det.x2 = box_data[i * 4 + 2] * img_width;
            det.y2 = box_data[i * 4 + 3] * img_height;
            det.score = conf;
            detections.push_back(det);
            
            // Log every 100th detection to avoid too much logging
            if (detections.size() % 100 == 0) {
                __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "FaceOutput: Processed %d anchors, found %zu detections", i+1, detections.size());
            }
        }
    }
    
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "FaceOutput: Found %zu detections before NMS", detections.size());
    std::vector<Detection> result = hard_nms(detections, IOU_THRESHOLD);
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "FaceOutput: NMS completed, %zu detections remaining", result.size());
    return result;
}

// Inline softmax for 2 classes
inline void softmax2(float& val0, float& val1) {
    float max_val = std::max(val0, val1);
    float exp0 = std::exp(val0 - max_val);
    float exp1 = std::exp(val1 - max_val);
    float sum = exp0 + exp1;
    val0 = exp0 / sum;
    val1 = exp1 / sum;
}

bool process_image_with_gender_count(cv::Mat& src, AAssetManager* mgr) {
    // Add timeout protection
    auto start_time = std::chrono::high_resolution_clock::now();
    const int max_processing_time = 10000; // 10 seconds max processing time
    
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 1 - Checking models loaded status");
    // Initialize models if needed
    if (!models_loaded) {
        __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 1a - Models not loaded, initializing...");
        std::string init_result = initialize_models(mgr);
        if (init_result != "SUCCESS") {
            __android_log_print(ANDROID_LOG_ERROR, "ImageClassification", "Process: Step 1a - Model initialization failed: %s", init_result.c_str());
            return false;  // Early return for failure
        }
        __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 1a - Models initialized successfully");
    } else {
        __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 1a - Models already loaded");
    }
    
    // Check timeout
    auto current_time = std::chrono::high_resolution_clock::now();
    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(current_time - start_time);
    if (elapsed.count() > max_processing_time) {
        __android_log_print(ANDROID_LOG_ERROR, "ImageClassification", "Process: Step 1b - Timeout during model initialization");
        return false;
    }
    
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 2 - Converting image to RGB");
    // Convert to RGB
    cv::Mat rgb_src;
    if (src.channels() == 3) {
        cv::cvtColor(src, rgb_src, cv::COLOR_BGR2RGB);
        __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 2a - Converted 3-channel BGR to RGB");
    } else if (src.channels() == 4) {
        cv::cvtColor(src, rgb_src, cv::COLOR_BGRA2RGB);
        __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 2a - Converted 4-channel BGRA to RGB");
    } else {
        __android_log_print(ANDROID_LOG_ERROR, "ImageClassification", "Process: Step 2a - Unsupported image format: %d channels", src.channels());
        return false;  // Unsupported format
    }
    
    int orig_width = rgb_src.cols;
    int orig_height = rgb_src.rows;
    
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 2b - Processing image: %dx%d", orig_width, orig_height);
    
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 3 - Starting face detection");
    // Face detection
    cv::Mat resized_fd;
    cv::resize(rgb_src, resized_fd, cv::Size(FD_MODEL_WIDTH, FD_MODEL_HEIGHT));
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 3a - Resized image for face detection");
    
    ncnn::Mat fd_input = ncnn::Mat::from_pixels(resized_fd.data, ncnn::Mat::PIXEL_RGB, FD_MODEL_WIDTH, FD_MODEL_HEIGHT);
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 3b - Created ncnn input tensor");
    
    const float mean_vals[3] = {127.0f, 127.0f, 127.0f};
    const float norm_vals[3] = {1.0f/160.0f, 1.0f/160.0f, 1.0f/160.0f};
    fd_input.substract_mean_normalize(mean_vals, norm_vals);
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 3c - Normalized input tensor");
    
    ncnn::Extractor face_ex = face_net.create_extractor();
    face_ex.set_light_mode(true);
    face_ex.input("in0", fd_input);
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 3d - Created face extractor and set input");
    
    ncnn::Mat conf_mat, box_mat;
    face_ex.extract("out0", conf_mat);
    face_ex.extract("out1", box_mat);
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 3e - Extracted face detection outputs");
    
    // Check timeout before processing face outputs
    auto face_process_time = std::chrono::high_resolution_clock::now();
    auto face_elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(face_process_time - start_time);
    if (face_elapsed.count() > 7000) {  // 7 seconds timeout before face processing
        __android_log_print(ANDROID_LOG_ERROR, "ImageClassification", "Process: Step 3e - Timeout before face processing, elapsed: %lld ms",
                           static_cast<long long>(face_elapsed.count()));
        return true;
    }
    
    std::vector<Detection> faces = process_face_output(conf_mat, box_mat, orig_width, orig_height);
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 3f - Processed face outputs, detected %zu faces", faces.size());
    
    if (faces.empty()) {
        __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 4 - No faces detected, returning true");
        return true;  // No faces, so return true
    }
    
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 5 - Starting face classification");
    // Process each detected face
    for (size_t i = 0; i < faces.size(); ++i) {
        // Check timeout periodically
        auto current_time = std::chrono::high_resolution_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(current_time - start_time);
        if (elapsed.count() > max_processing_time) {
            __android_log_print(ANDROID_LOG_ERROR, "ImageClassification", "Process: Step 5a - Timeout during face processing");
            return true;  // Return true to avoid blocking
        }
        
        const auto& face = faces[i];
        __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 5b - Processing face %zu at (%.1f,%.1f)-(%.1f,%.1f)",
                           i, face.x1, face.y1, face.x2, face.y2);
        
        int x1 = std::max(0, static_cast<int>(face.x1));
        int y1 = std::max(0, static_cast<int>(face.y1));
        int x2 = std::min(orig_width, static_cast<int>(face.x2));
        int y2 = std::min(orig_height, static_cast<int>(face.y2));
        
        // Validate face crop dimensions
        if (x2 <= x1 || y2 <= y1) {
            __android_log_print(ANDROID_LOG_WARN, "ImageClassification", "Process: Step 5c - Invalid face crop dimensions: %d,%d,%d,%d", x1, y1, x2, y2);
            continue;
        }
        __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 5d - Valid face crop: %dx%d", x2-x1, y2-y1);
        
        cv::Mat face_crop = rgb_src(cv::Rect(x1, y1, x2 - x1, y2 - y1));
        cv::Mat face_resized;
        cv::resize(face_crop, face_resized, cv::Size(GENDER_MODEL_WIDTH, GENDER_MODEL_HEIGHT));
        __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 5e - Resized face for gender classification");
        
        ncnn::Mat gender_input = ncnn::Mat::from_pixels(face_resized.data, ncnn::Mat::PIXEL_BGR2RGB, GENDER_MODEL_WIDTH, GENDER_MODEL_HEIGHT);
        __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 5f - Created gender input tensor");
        
        const float gender_norm[3] = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};
        const float gender_mean[3] = {0.0f, 0.0f, 0.0f};
        gender_input.substract_mean_normalize(gender_mean, gender_norm);
        __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 5g - Normalized gender input tensor");
        
        ncnn::Mat gender_input_chw;
        ncnn::convert_packing(gender_input, gender_input_chw, 1);
        __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 5h - Converted gender input to CHW format");
        
        ncnn::Extractor gender_ex = gender_net.create_extractor();
        gender_ex.set_light_mode(true);
        gender_ex.input("in0", gender_input_chw);
        __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 5i - Created gender extractor and set input");
        
        ncnn::Mat gender_output;
        gender_ex.extract("out0", gender_output);
        __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 5j - Extracted gender output, width: %d", gender_output.w);
        
        if (gender_output.w >= 2) {
            float female_score = gender_output[0];
            float male_score = gender_output[1];
            
            // Apply softmax
            float sum = std::exp(female_score) + std::exp(male_score);
            female_score = std::exp(female_score) / sum;
            male_score = std::exp(male_score) / sum;
            
            __android_log_print(ANDROID_LOG_INFO, "ImageClassification",
                              "Process: Step 5k - Face %zu: female=%.3f, male=%.3f", i, female_score, male_score);
            
            if (female_score >= male_score) {
                __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 5l - Female detected, returning false");
                return false;  // Female detected, stop and return false
            }
            // If male, continue to next face
        } else {
            __android_log_print(ANDROID_LOG_WARN, "ImageClassification",
                              "Process: Step 5m - Invalid gender output for face %zu: width=%d", i, gender_output.w);
        }
    }
    
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Process: Step 6 - No females detected, returning true");
    return true;  // No females detected
}

// Bitmap to Mat conversion
void Bitmap2Mat(JNIEnv* env, jobject bitmap, cv::Mat& dst, jboolean needUnPremultiplyAlpha) {
    if (bitmap == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "Bitmap2Mat", "Error: bitmap is null");
        return;
    }
    
    AndroidBitmapInfo info;
    void* pixels = 0;

    int ret = AndroidBitmap_getInfo(env, bitmap, &info);
    if (ret < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "Bitmap2Mat", "AndroidBitmap_getInfo() failed, error code: %d", ret);
        return;
    }
    
    __android_log_print(ANDROID_LOG_INFO, "Bitmap2Mat", "Bitmap info: width=%d, height=%d, format=%d",
                       info.width, info.height, info.format);
    
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 && info.format != ANDROID_BITMAP_FORMAT_RGB_565) {
        __android_log_print(ANDROID_LOG_ERROR, "Bitmap2Mat", "Unsupported bitmap format: %d", info.format);
        return;
    }
    
    ret = AndroidBitmap_lockPixels(env, bitmap, &pixels);
    if (ret < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "Bitmap2Mat", "AndroidBitmap_lockPixels() failed, error code: %d", ret);
        return;
    }
    
    if (pixels == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "Bitmap2Mat", "Error: pixels is null after lock");
        AndroidBitmap_unlockPixels(env, bitmap);
        return;
    }
    
    if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        dst = cv::Mat(info.height, info.width, CV_8UC4, pixels);
        if (needUnPremultiplyAlpha) {
            cvtColor(dst, dst, cv::COLOR_mRGBA2RGBA);
        }
    } else {
        cv::Mat tmp(info.height, info.width, CV_8UC2, pixels);
        cvtColor(tmp, dst, cv::COLOR_BGR5652RGBA);
    }
    
    AndroidBitmap_unlockPixels(env, bitmap);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_haram_block_ImageViewAccessibilityService_ImageClassification(
    JNIEnv* env,
    jobject,  // This is 'thiz' for the instance method
    jobject bitmapIn,
    jobject assetManager){
    
    // Add timeout protection to prevent infinite loops
    auto start_time = std::chrono::high_resolution_clock::now();
    
    cv::Mat src;
    if (bitmapIn == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "ImageClassification", "Error: bitmapIn is null");
        return env->NewStringUTF("false");
    }
    
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Step 1: Converting bitmap to Mat");
    Bitmap2Mat(env, bitmapIn, src, false);
    
    if (src.empty()) {
        __android_log_print(ANDROID_LOG_ERROR, "ImageClassification", "Error: Source image is empty");
        return env->NewStringUTF("false");
    }
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Step 1: Bitmap conversion successful, size: %dx%d", src.cols, src.rows);
    
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Step 2: Getting asset manager");
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (mgr == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "ImageClassification", "Error: Asset manager is null");
        return env->NewStringUTF("false");
    }
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Step 2: Asset manager obtained");
    
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Step 3: Starting image classification processing");
    
    // Add a timeout check before starting processing
    auto pre_process_time = std::chrono::high_resolution_clock::now();
    auto pre_elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(pre_process_time - start_time);
    if (pre_elapsed.count() > 8000) {  // 8 seconds timeout before processing
        __android_log_print(ANDROID_LOG_ERROR, "ImageClassification", "Step 3: Timeout before processing, elapsed: %lld ms",
                           static_cast<long long>(pre_elapsed.count()));
        return env->NewStringUTF("false");
    }
    
    bool result = process_image_with_gender_count(src, mgr);
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Step 3: Classification processing completed, result: %s", result ? "true" : "false");
    
    // Check if processing took too long
    auto end_time = std::chrono::high_resolution_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time);
    
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification",
                       "Step 4: Classification completed in %lld ms, result: %s",
                       static_cast<long long>(duration.count()), result ? "true" : "false");
    
    __android_log_print(ANDROID_LOG_INFO, "ImageClassification", "Step 5: Returning result to Java");
    return env->NewStringUTF(result ? "true" : "false");
}