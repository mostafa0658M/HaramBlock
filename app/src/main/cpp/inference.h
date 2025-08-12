#ifndef IMAGECLASSIFICATION_INFERENCE_H
#define IMAGECLASSIFICATION_INFERENCE_H

#include <opencv2/core.hpp>
#include <android/asset_manager.h>

/**
 * @brief Perform inference for classifying a given image using the NCNN model.
 * @param src: input image in OpenCV Mat format.
 * @param mgr: AAssetManager pointer for loading NCNN model files in assets folder.
 * @return the predicted class
 */
std::string Inference(cv::Mat& src, AAssetManager* mgr);

#endif //IMAGECLASSIFICATION_INFERENCE_H