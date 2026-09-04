# Durud & Salam (দরুদ ও সালাম) — Android App

## ফিচার সমূহ
- ১০টি সহীহ দরুদ (আরবি + বাংলা উচ্চারণ + অর্থ + হাদিস রেফারেন্স)
- 📿 দরুদ কাউন্টার (তাসবিহ) — লক্ষ্য সেট করুন, প্রগ্রেস দেখুন
- 🔍 দরুদ সার্চ করার সুবিধা
- 📋 কপি ও শেয়ার বাটন
- ⭐ ফজিলত ও ফযীলত বিভাগ
- সুন্দর ইসলামিক ডিজাইন (সবুজ + সোনালি থিম)

## প্রজেক্ট স্ট্রাকচার
```
DaroodApp/
├── app/
│   ├── src/main/
│   │   ├── assets/
│   │   │   └── index.html          ← সম্পূর্ণ UI (HTML/CSS/JS)
│   │   ├── java/com/darood/app/
│   │   │   └── MainActivity.java   ← WebView + Android Bridge
│   │   ├── res/
│   │   │   ├── values/
│   │   │   │   ├── strings.xml
│   │   │   │   ├── styles.xml
│   │   │   │   └── colors.xml
│   │   │   └── mipmap-*/          ← App icon এখানে রাখুন
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## Android Studio-তে কীভাবে খুলবেন

1. **Android Studio** চালু করুন
2. `File → Open` চাপুন
3. `DaroodApp` ফোল্ডার সিলেক্ট করুন
4. Gradle sync হওয়ার জন্য অপেক্ষা করুন
5. `Run → Run 'app'` চাপুন অথবা `▶` বাটন চাপুন

## App Icon যোগ করুন (ঐচ্ছিক)

`res/mipmap-*/` ফোল্ডারে নিচের সাইজের আইকন রাখুন:
- `mipmap-mdpi/ic_launcher.png` — 48x48 px
- `mipmap-hdpi/ic_launcher.png` — 72x72 px
- `mipmap-xhdpi/ic_launcher.png` — 96x96 px
- `mipmap-xxhdpi/ic_launcher.png` — 144x144 px
- `mipmap-xxxhdpi/ic_launcher.png` — 192x192 px

অথবা Android Studio-তে `Right-click res → New → Image Asset` দিয়ে তৈরি করুন।

## APK বানানো

1. `Build → Generate Signed Bundle / APK` চাপুন
2. `APK` সিলেক্ট করুন
3. Keystore তৈরি বা সিলেক্ট করুন
4. `Release` বিল্ড টাইপ সিলেক্ট করুন
5. Finish চাপুন — APK তৈরি হবে `app/release/` ফোল্ডারে

## Minimum Requirements
- Android 5.0 (API 21) বা তার উপরে
- Internet permission (Google Fonts লোডের জন্য)

## Offline ব্যবহার
Fonts offline-এ কাজ নাও করতে পারে। Offline সাপোর্টের জন্য:
1. Amiri ও Noto Serif Bengali ফন্ট ডাউনলোড করুন
2. `assets/fonts/` ফোল্ডারে রাখুন
3. `index.html`-এ `@font-face` দিয়ে লোড করুন

---
اَللّٰهُمَّ صَلِّ عَلٰى مُحَمَّدٍ وَّسَلِّمْ
