# 🌐 [العربية](#arabic) | [English](#english)

---

## Arabic

# 🛡️ تطبيق Haram Block

**حرام بلوك** هو تطبيق يعمل على طمس الصور غير اللائقة (التي تحتوي على نساء أو مشاهد غير مناسبة) باستخدام تقنية الـ **Blur** أو الحجب الكامل، بنفس فكرة إضافة **Haram Blur** للمتصفح، لكن هذه النسخة تعمل على **جهاز الأندرويد بالكامل** وليس فقط على المتصفح.

---

## 📑 الفهرس
1. [كيفية تنزيل التطبيق (للمستخدم العادي)](#كيفية-تنزيل-التطبيق-للمستخدم-العادي)
2. [كيفية تنزيل التطبيق (للمطورين)](#كيفية-تنزيل-التطبيق-للمطورين)
3. [آلية عمل التطبيق](#آلية-عمل-التطبيق)
4. [مخطط سير العمل](#مخطط-سير-العمل)
5. [اقتراحات للتطوير والتحسين](#اقتراحات-للتطوير-والتحسين)
6. [دعوة للمشاركة](#دعوة-للمشاركة)

---

## 📥 كيفية تنزيل التطبيق (للمستخدم العادي)

1. قم بتحميل ملف **APK** من: [رابط التحميل](#)  
2. افتح الملف على هاتفك واختر **تثبيت**.  
3. في حال منع التثبيت، فعّل خيار **التثبيت من مصادر غير معروفة** من إعدادات الهاتف.

---

## 💻 كيفية تنزيل التطبيق (للمطورين)

```bash
git clone [URL]
cd HaramBlock
# على لينكس
./setup.sh
# على ويندوز
./setup.bat

> ملاحظة: قد تستغرق العملية وقتًا أطول إذا لم يكن لديك JDK 17 أو Android SDK مثبتين.




---

⚙️ آلية عمل التطبيق

يعمل التطبيق عبر عدة مراحل مترابطة:

1. خدمة الوصول (Accessibility Service)

اكتشاف مواقع الصور المعروضة على الشاشة.

تمرير الإحداثيات إلى المراحل التالية.

استخدام Cache لتجنب المعالجة المتكررة لكل إطار.



2. التصوير عبر Media Projection

أخذ لقطة شاشة.

قص إطارات الصور بناءً على الإحداثيات من خدمة الوصول.



3. JNI + NCNN

JNI يسمح بتشغيل مكتبات C++ داخل تطبيقات Java.

NCNN مكتبة ذكاء اصطناعي خفيفة وسريعة لمعالجات ARM.



4. معالجة الصور

قص الصور وتجهيزها لتكون مناسبة للتحليل.



5. اكتشاف الوجوه

باستخدام نموذج slim-128 الخفيف (Ultralight face detection ~1MB).

سرعة الكشف: 5-12ms لكل صورة.



6. تصنيف الجنس

نموذج PyTorch مخصص، محول لـ NCNN.

سرعة الكشف: 0.3-1.5ms لكل وجه.



7. آلية الحجب

رسم مربع أسود فوق الصور غير اللائقة لتقليل الاستهلاك بدلاً من إعادة المعالجة بالـ Blur.





---

📊 مخطط سير العمل

[Accessibility Service] --إحداثيات--> [Media Projection] --لقطة--> [Image Processing]
       |                                                          |
       v                                                          v
   [Cache] --> [JNI + NCNN] --> [Face Detection] --> [Gender Classification] --> [Block Image]


---

🛠️ اقتراحات للتطوير والتحسين

تحسين واجهة المستخدم.

إضافة ميزة التطبيقات الآمنة.

إتاحة اختيار النموذج المناسب بناءً على قوة الجهاز واستهلاك البطارية.

إضافة إحصائيات بعدد الصور المحجوبة.

إصدار نسخة iOS (باستخدام مكتبة MNN).

تحسين استهلاك البطارية والذاكرة.

تسريع خوارزمية الكشف والمعالجة.

تحسين نماذج الذكاء الاصطناعي.

إنتاج فيديو دعائي (خالٍ من الموسيقى والمشاهد المحرمة).

تطوير ملف Documentation ليكون أشمل.



---

🤝 دعوة للمشاركة

ندعو كل مطور أو مهتم بالمشروع للمساهمة في تحسينه، ابتغاءً للأجر والثواب بإذن الله.


---


---

English

🛡️ Haram Block App

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

git clone [URL]
cd HaramBlock
# On Linux
./setup.sh
# On Windows
./setup.bat

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

