#!/bin/bash

# This script assumes Ubuntu/Debian-based Linux and must be run with sudo privileges where necessary.
# Run it in the root directory of your Android project.
# Prerequisites: wget, unzip, curl should be installed (sudo apt install wget unzip curl if needed).

# 1 & 2. Ensure Java 21 is installed and set as default
if ! command -v java &> /dev/null || ! java -version 2>&1 | grep -q "openjdk version \"21"; then
  echo "Java 21 not found or not default. Installing..."
  sudo apt update -y
  sudo apt install openjdk-21-jdk -y
fi

JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
export JAVA_HOME
export PATH=$JAVA_HOME/bin:$PATH

# 3. Ensure Android SDK is installed
ANDROID_HOME=$HOME/Android/sdk
if [ ! -d "$ANDROID_HOME" ]; then
  echo "Android SDK not found. Installing..."
  mkdir -p $ANDROID_HOME/cmdline-tools
  wget https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -O /tmp/cmdline.zip
  unzip /tmp/cmdline.zip -d $ANDROID_HOME/cmdline-tools
  mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
  rm /tmp/cmdline.zip
  export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
  yes | sdkmanager --licenses > /dev/null
  sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0"
fi
export ANDROID_HOME
export PATH=$PATH:$ANDROID_HOME/platform-tools

# 4. Download and extract OpenCV to app/src/main/cpp
mkdir -p app/src/main/cpp
wget https://github.com/opencv/opencv/releases/download/4.12.0/opencv-4.12.0-android-sdk.zip -O /tmp/opencv.zip
unzip /tmp/opencv.zip -d /tmp/opencv-temp
mv /tmp/opencv-temp/OpenCV-android-sdk/ app/src/main/cpp/
rm -rf /tmp/opencv-temp /tmp/opencv.zip

# 5. Download and extract ncnn to app/src/main/cpp and rename
wget https://github.com/Tencent/ncnn/releases/latest/download/ncnn-20250503-android.zip -O /tmp/ncnn.zip
unzip /tmp/ncnn.zip -d /tmp/ncnn-temp
mv /tmp/ncnn-temp/ncnn-20250503-android app/src/main/cpp/ncnn
rm -rf /tmp/ncnn-temp /tmp/ncnn.zip

# 6. Create local.properties
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 7. Create gradle.properties
cat <<EOF > gradle.properties
sdk.dir=$ANDROID_HOME
android.useAndroidX=true
org.gradle.java.home=$JAVA_HOME
android.suppressUnsupportedCompileSdk=34
EOF

echo "Setup complete. You may need to accept Android SDK licenses manually if not already done."