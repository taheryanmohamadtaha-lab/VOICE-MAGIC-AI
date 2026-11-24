# راه‌اندازی سرور و سرویس AI

اپ بدون سرور هم کار می‌کند (افکت، ضبط، ویرایش). سرور فقط برای آزمایشگاه AI لازم است.
کلید سرویس AI فقط روی **سرور** نگه داشته می‌شود؛ هرگز داخل APK نمی‌رود.

## ۱. اجرای سرور

نیاز: Python 3.11 یا بالاتر.

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
```

اجرا (متغیرهای `.env` را خودتان export کنید یا در سرویس سیستم بگذارید):

```bash
uvicorn app.main:app_factory --factory --host 0.0.0.0 --port 8000
```

بررسی: `http://localhost:8000/health` باید `{"ok": true}` بدهد.

اپ در نسخه release فقط آدرس **https** را قبول می‌کند. پشت یک reverse proxy (مثل Caddy یا nginx) با گواهی TLS بگذارید.

## ۲. ساخت کاربر و توکن

```bash
python -m app.admin create-user --credits 20
```

توکن فقط یک بار نمایش داده می‌شود. در اپ: **تنظیمات ← سرور** آدرس و توکن را وارد کنید.

افزودن اعتبار: `python -m app.admin grant <user_id> 10`

## ۳. وضعیت‌هایی که اپ نشان می‌دهد

| وضعیت | معنی |
|---|---|
| تنظیم نشده | آدرس یا توکن وارد نشده |
| در دسترس نیست | سرور جواب نمی‌دهد یا توکن اشتباه است |
| سرور بدون سرویس AI | سرور کار می‌کند ولی `VMA_AI_PROVIDER=none` است؛ تبدیل صدا انجام نمی‌شود |
| آماده | سرویس AI واقعی تنظیم شده |

## ۴. اضافه کردن سرویس AI واقعی

این مخزن فقط آداپتور `none` دارد (صادقانه: هیچ مدل AI همراه پروژه نیست). برای سرویس خودتان:

1. در `backend/ai_providers/` فایل جدید بسازید و `VoiceProvider` را پیاده کنید (`create_profile`, `delete_profile`, `convert`، و `configured = True`).
2. در `backend/ai_providers/__init__.py` داخل `build_provider` نام آن را ثبت کنید.
3. کلید را فقط به‌صورت متغیر محیطی روی سرور بگذارید و `VMA_AI_PROVIDER=<نام>` را تنظیم کنید.
4. فقط سرویس‌هایی را وصل کنید که استفاده از نمونه صدا را با رضایت صاحب صدا مجاز می‌دانند.

## ۵. متغیرها

| نام | پیش‌فرض | توضیح |
|---|---|---|
| `VMA_DB_PATH` | `./data/voice_magic.db` | مسیر دیتابیس SQLite |
| `VMA_FREE_CREDITS` | 10 | اعتبار کاربر جدید |
| `VMA_CONVERT_COST` | 1 | هزینه هر تبدیل |
| `VMA_MAX_UPLOAD_MB` | 25 | حداکثر حجم آپلود |
| `VMA_AI_PROVIDER` | `none` | نام آداپتور |

## ۶. خرید و اعتبار

کسر اعتبار فقط روی سرور انجام می‌شود. `/v1/billing/validate` عمداً کد 501 می‌دهد تا اعتبارسنجی Google Play پیاده‌سازی شود (نیاز به Service Account). تا آن زمان هیچ خریدی تأیید نمی‌شود.

## تست سرور

```bash
pip install -r requirements-dev.txt
python -m pytest -q
```
