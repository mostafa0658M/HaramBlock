🌐 [العربية](/README.md) | [English](#english)

Haram Block is an Android application that blurs or completely blocks inappropriate images (such as those containing women) — similar to the Haram Blur browser extension — but for the entire device, not just the browser.


---

📑 Table of Contents

1. How to Install (For Regular Users)


2. How to Install (For Developers)


3. How It Works


4. Workflow Diagram


5. Suggestions for Improvement


6. Contribution Invitation




---

📥 How to Install (For Regular Users)

1. Download the APK from: Download Link


2. Open the file and tap Install.


3. If blocked, enable Install from Unknown Sources in your phone settings.




---

💻 How to Install (For Developers)

```bash
git clone [URL]
cd HaramBlock
# On Linux
./setup.sh
# On Windows
./setup.bat
```

> Note: The process may take longer if JDK 17 or Android SDK are not installed.




---

⚙️ How It Works

The app processes the screen in several stages:

1. Accessibility Service

Detects image frame positions on the screen.

Passes coordinates to the next stages.

Uses Cache to avoid reprocessing every frame.



2. Media Projection

Takes a screenshot.

Crops image frames using coordinates from the accessibility service.



3. JNI + NCNN

JNI allows running C++ libraries in Java apps.

NCNN is a fast, lightweight AI inference library for ARM processors.



4. Image Processing

Crops and prepares images for analysis.



5. Face Detection

Uses a lightweight slim-128 model (~1MB).

Detection speed: 5–12ms per image.



6. Gender Classification

Custom PyTorch model converted to NCNN.

Speed: 0.3–1.5ms per face.



7. Blocking Mechanism

Draws a black box over inappropriate images instead of reapplying blur, saving resources.





---

📊 Workflow Diagram

[Accessibility Service] --coordinates--> [Media Projection] --screenshot--> [Image Processing]
       |                                                                   |
       v                                                                   v
   [Cache] --> [JNI + NCNN] --> [Face Detection] --> [Gender Classification] --> [Block Image]


---

🛠️ Suggestions for Improvement

Improve UI design.

Add Safe Apps feature.

Allow users to choose between models based on device performance and battery usage.

Add statistics on blocked images.

Create an iOS version (using MNN library).

Optimize battery and memory usage.

Speed up detection and rendering algorithms.

Improve AI models.

Make a promotional video (without music or inappropriate scenes).

Expand and enhance the documentation.



---

🤝 Contribution Invitation

We welcome any developer or enthusiast to contribute to improving the app — seeking reward from Allah for helping protect users.
