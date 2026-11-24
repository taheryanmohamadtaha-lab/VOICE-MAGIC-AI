# Voice Magic AI

اپ اندروید تغییر صدا و استودیوی صوتی (فارسی / English).
Android voice changer and audio studio with a native C++ DSP engine, offline effects, an audio editor, and a replaceable cloud-AI layer.

> **وضعیت صادقانه / Honest status:** کد کامل نوشته شده، اما در محیط توسعه امکان کامپایل Android وجود نداشت. تست‌های بک‌اند و تست DSP نیتیو واقعاً اجرا شده‌اند؛ ساخت APK و تست روی گوشی واقعی هنوز انجام نشده. جزئیات در [docs/STATUS.md](docs/STATUS.md).

## شروع سریع (برای مبتدی‌ها)

1. این پروژه را در GitHub خودتان داشته باشید.
2. به تب **Actions** بروید، workflow با نام **Build Android APK** را باز کنید.
3. بعد از سبز شدن، پایین صفحه در بخش **Artifacts** فایل `VoiceMagicAI-debug-apk` را دانلود کنید.
4. فایل ZIP را باز کنید و APK را روی گوشی نصب کنید.

راهنمای کامل و قدم‌به‌قدم: [docs/INSTALLATION_FA.md](docs/INSTALLATION_FA.md)

## امکانات

- **استودیوی افکت:** ۱۷ افکت آفلاین (مرد، زن، کودک، پیر، ربات، بیگانه، هیولا، کارتون، هلیوم، سنجاب، صدای بم، رادیو، تلفن، اکو، ریورب، تریلر سینمایی، غار) با شدت قابل تنظیم و علاقه‌مندی.
- **حالت زنده:** ضبط و پردازش لحظه‌ای با Oboe/AAudio و موتور DSP به زبان C++، نمودار موج، سطح ورودی/خروجی، تشخیص کلیپ، قطع صدا، مانیتور هدفون.
- **ویرایشگر صدا:** برش، تقسیم، ادغام، میکس، حجم، سرعت، تغییر گام، اکولایزر، کاهش نویز، ریورب، اکو، فید، Undo/Redo، ذخیره پروژه، خروجی WAV و M4A (AAC).
- **آزمایشگاه AI:** ساخت پروفایل صدا با رضایت صریح، سرویس ابری قابل جایگزینی، حذف نمونه‌ها. بدون سرور/کلید AI، پیام راه‌اندازی نشان داده می‌شود و هیچ نتیجه جعلی تولید نمی‌شود.
- **کتابخانه من، تنظیمات، تم تاریک/روشن، فارسی (RTL) و انگلیسی (LTR).**

## ساختار مخزن

```
android/
  app/            رابط Compose، دیتابیس Room، لایه AI، صفحات (Home/Studio/Live/AI/Editor/Library/Settings)
  audio-engine/   ماژول صوتی: DSP به زبان C++ + Oboe + JNI + ابزارهای ویرایش و WAV/M4A
backend/          FastAPI: اعتبار (credit)، پروفایل‌ها، تبدیل صدا، آداپتور AI
docs/             مستندات
.github/workflows ساخت خودکار APK
```

## مستندات

- [docs/INSTALLATION_FA.md](docs/INSTALLATION_FA.md) نصب و ساخت APK
- [docs/USER_GUIDE_FA.md](docs/USER_GUIDE_FA.md) راهنمای کاربر
- [docs/API_SETUP.md](docs/API_SETUP.md) راه‌اندازی سرور و سرویس AI
- [docs/PRIVACY.md](docs/PRIVACY.md) حریم خصوصی
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) معماری فنی
- [docs/STATUS.md](docs/STATUS.md) چه چیزی کامل است و چه چیزی نیست

## مجوز

MIT — محمدطاها طاهریان
