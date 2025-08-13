@echo off

:: This script assumes Windows 10+ (for tar command) and must be run as Administrator where necessary.
:: Run it in the root directory of your Android project.
:: Prerequisites: curl and tar should be available (built-in on Windows 10+).

:: 1 & 2. Ensure Java 21 is installed
set "JAVA_HOME=%USERPROFILE%\jdk-21"
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo Java 21 not found. Installing...
  mkdir "%JAVA_HOME%"
  curl -L https://aka.ms/download-jdk/microsoft-jdk-21-windows-x64.zip -o %TEMP%\jdk.zip
  tar -xf %TEMP%\jdk.zip -C "%JAVA_HOME%" --strip-components=1
  del %TEMP%\jdk.zip
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

:: To make JAVA_HOME persistent, run: setx /M JAVA_HOME "%JAVA_HOME%"

:: 3. Ensure Android SDK is installed
set "ANDROID_HOME=%USERPROFILE%\Android\sdk"
if not exist "%ANDROID_HOME%" (
  echo Android SDK not found. Installing...
  mkdir "%ANDROID_HOME%\cmdline-tools"
  curl -L https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip -o %TEMP%\cmdline.zip
  tar -xf %TEMP%\cmdline.zip -C "%ANDROID_HOME%\cmdline-tools"
  ren "%ANDROID_HOME%\cmdline-tools\cmdline-tools" latest
  del %TEMP%\cmdline.zip
  set "PATH=%PATH%;%ANDROID_HOME%\cmdline-tools\latest\bin"
  echo y | sdkmanager.bat --licenses
  echo y | sdkmanager.bat --licenses
  echo y | sdkmanager.bat --licenses
  sdkmanager.bat --install "platform-tools" "platforms;android-34" "build-tools;34.0.0"
)
set "PATH=%PATH%;%ANDROID_HOME%\platform-tools"

:: 4. Download and extract OpenCV to app\src\main\cpp
if not exist app\src\main\cpp mkdir app\src\main\cpp
curl -L https://github.com/opencv/opencv/releases/download/4.12.0/opencv-4.12.0-android-sdk.zip -o %TEMP%\opencv.zip
mkdir %TEMP%\opencv-temp
tar -xf %TEMP%\opencv.zip -C %TEMP%\opencv-temp
move %TEMP%\opencv-temp\OpenCV-android-sdk\ app\src\main\cpp\
rd /s /q %TEMP%\opencv-temp
del %TEMP%\opencv.zip

:: 5. Download and extract ncnn to app\src\main\cpp and rename
curl -L https://github.com/Tencent/ncnn/releases/latest/download/ncnn-20250503-android.zip -o %TEMP%\ncnn.zip
mkdir %TEMP%\ncnn-temp
tar -xf %TEMP%\ncnn.zip -C %TEMP%\ncnn-temp
move %TEMP%\ncnn-temp\ncnn-20250503-android app\src\main\cpp\ncnn
rd /s /q %TEMP%\ncnn-temp
del %TEMP%\ncnn.zip

:: 6. Create local.properties (with forward slashes for paths)
echo sdk.dir=%ANDROID_HOME:\=/% > local.properties

:: 7. Create gradle.properties (with forward slashes for paths)
(
echo sdk.dir=%ANDROID_HOME:\=/%
echo android.useAndroidX=true
echo org.gradle.java.home=%JAVA_HOME:\=/%
echo android.suppressUnsupportedCompileSdk=34
) > gradle.properties

echo Setup complete. You may need to accept Android SDK licenses manually if not already done. Run 'setx /M JAVA_HOME "%JAVA_HOME%"' to persist JAVA_HOME.