# راهنمای نصب و ساخت APK (فارسی ساده)

لازم نیست برنامه‌نویس باشید. فقط یک حساب GitHub و یک گوشی اندروید (نسخه ۸.۱ یا بالاتر) کافی است.

## ۱. ساخت مخزن (Repository) در GitHub

شما مخزن خودتان را دارید: `taheryanmohamadtaha-lab/VOICE-MAGIC-AI`. اگر مخزن جدید می‌خواهید:

1. وارد github.com شوید و روی علامت **+** بالای صفحه و بعد **New repository** بزنید.
2. نام را `VOICE-MAGIC-AI` بگذارید و **Create repository** را بزنید.

## ۲. بارگذاری پروژه

فایل‌های این پروژه الان در مخزن شما هستند. اگر خودتان می‌خواهید از کامپیوتر بارگذاری کنید:

```bash
git clone https://github.com/taheryanmohamadtaha-lab/VOICE-MAGIC-AI.git
cd VOICE-MAGIC-AI
git add .
git commit -m "update"
git push
```

## ۳. ساخت APK با GitHub Actions

1. در صفحه مخزن روی تب **Actions** بزنید.
2. اگر پیام فعال‌سازی دیدید، روی **I understand my workflows, enable them** بزنید.
3. در ستون چپ **Build Android APK** را انتخاب کنید.
4. روی **Run workflow** و بعد دکمه سبز **Run workflow** بزنید.
5. حدود ۱۰ تا ۲۰ دقیقه صبر کنید تا کنار نام اجرا تیک سبز بیاید.

اگر ✗ قرمز دیدید به بخش «رفع خطا» پایین بروید.

## ۴. دانلود APK

1. روی اجرای سبز کلیک کنید.
2. پایین صفحه، بخش **Artifacts**، روی `VoiceMagicAI-debug-apk` بزنید تا ZIP دانلود شود.
3. ZIP را باز کنید. داخلش فایل `app-debug.apk` است.

## ۵. نصب روی گوشی

1. فایل APK را به گوشی منتقل کنید (تلگرام، کابل، ...).
2. روی فایل بزنید. اگر گوشی پرسید، **Install unknown apps / نصب از منابع ناشناس** را برای همان برنامه (مثلاً فایل‌منیجر) روشن کنید.
3. **Install** را بزنید و برنامه را باز کنید.
4. اولین بار که میکروفون لازم شد، توضیح برنامه را بخوانید و **Allow** را بزنید.

نسخه debug با امضای موقت ساخته می‌شود و فقط برای تست است.

## ۶. اتصال به سرور AI (اختیاری)

بدون سرور هم افکت‌ها، ضبط زنده و ویرایشگر کار می‌کنند. فقط آزمایشگاه AI به سرور نیاز دارد. راهنما: [API_SETUP.md](API_SETUP.md)

## ۷. ساخت نسخه Release امضاشده (اختیاری)

کلید امضا را هرگز در مخزن نگذارید. روی کامپیوتر:

```bash
keytool -genkeypair -v -keystore release.jks -alias vma -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks > release.jks.b64
```

بعد در GitHub: **Settings → Secrets and variables → Actions → New repository secret** و این چهار مورد را بسازید:

| نام | مقدار |
|---|---|
| `VMA_KEYSTORE_BASE64` | محتوای فایل `release.jks.b64` |
| `VMA_KEYSTORE_PASSWORD` | رمز keystore |
| `VMA_KEY_ALIAS` | `vma` |
| `VMA_KEY_PASSWORD` | رمز کلید |

با این‌ها، workflow خودش artifact دیگری به نام `VoiceMagicAI-release-apk` می‌سازد. فایل `release.jks` را در جای امن نگه دارید؛ اگر گم شود نمی‌توانید نسخه‌های بعدی را با همان امضا بدهید.

## رفع خطاهای رایج

| مشکل | راه حل |
|---|---|
| Actions قرمز شد و در لاگ `error:` از Kotlin هست | روی مرحله قرمز کلیک کنید، چند خط خطا را کپی کنید و برای اصلاح بفرستید. این کد هنوز روی Android کامپایل نشده بود. |
| `SDK location not found` یا `NDK not found` | مرحله «Install NDK and CMake» باید سبز باشد. دوباره Run workflow بزنید. |
| گوشی می‌گوید «App not installed» | نسخه قدیمی را پاک کنید یا نسخه debug با release را با هم نصب نکنید. |
| صدای بلند و سوت (feedback) | حالت زنده را با هدفون استفاده کنید. بدون هدفون مانیتور خاموش است. |
| دکمه Artifacts دیده نمی‌شود | باید وارد حساب GitHub باشید. |
