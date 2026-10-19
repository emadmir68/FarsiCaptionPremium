# FarsiCaptionPremium 3.5.3-fast-turbo (51)

- Fast mode now uses Whisper Large-V3-Turbo Q5 (4 decoder layers) instead of Whisper Small (12 decoder layers).
- Fast mode keeps greedy decoding (best-of 1) and the existing Silero VAD/batched speech windows.
- Quality, Persian-specialized, VAD, timestamp, normalization, package ID and project formats are unchanged.
- Goal: materially reduce offline transcription latency when the user selects Fast, without changing the established quality/Persian modes.
- Release only after native, timeline, Kotlin, signed APK and publication gates pass.

نسخهٔ **3.5.2-longvideo (50)** — Android 8+ / arm64-v8a

- بهینه‌سازی بارگذاری PCM برای ویدئوهای چنددقیقه‌ای: حافظهٔ نمونه‌های صوتی از ابتدا بر اساس اندازهٔ واقعی WAV رزرو می‌شود تا رشد و کپی‌های تکراری buffer حذف شود.
- اندازهٔ buffer خواندن PCM از 8KB به 64KB افزایش یافته است؛ نمونه‌های صوتی، timestampها، VAD و پارامترهای decoding بدون تغییر باقی مانده‌اند.
- `applicationId` همچنان `com.farsicaption.premium` است و تنظیمات، پروژه‌ها و مدل فارسی نسخهٔ قبل حفظ می‌شوند.
- انتشار APK فقط پس از عبور تست native، تست‌های Kotlin، build امضاشده و gateهای ASR انجام می‌شود.

نسخهٔ **3.5.1-performance (49)** — Android 8+ / arm64-v8a

- بهینه‌سازی مسیر آفلاین فارسی برای ویدیوهای چنددقیقه‌ای: بازه‌های نزدیک گفتار که Silero VAD جدا می‌کند، با حفظ زمان اصلی و سقف ۲۸ ثانیه به‌صورت batch به Whisper داده می‌شوند تا اجرای تکراری encoder/setup کمتر شود.
- حالت «سریع» اکنون واقعاً از Greedy decoding با best-of=1 استفاده می‌کند؛ حالت‌های کیفیت و «فارسی تخصصی» همچنان Beam Search 5 را حفظ می‌کنند.
- در بیشتر انتخاب‌های Android، صوت مستقیماً از file descriptor ویدیو به FFmpeg داده می‌شود و کپی کامل ویدیو به cache حذف می‌شود. اگر provider این مسیر را پشتیبانی نکند، برنامه خودکار به روش امن قبلی برمی‌گردد.
- اندازه‌گیری داخلی زمان آماده‌سازی صوت، ASR و کل pipeline با تگ FarsiCaptionPerf اضافه شده تا گلوگاه سرعت روی گوشی واقعی قابل تشخیص باشد؛ متن گفتار یا مسیر فایل در log ثبت نمی‌شود.
- مدل «فارسی تخصصی» Givia Q5، Turbo Q5، Large-V3-Turbo، Silero VAD، زبان صریح فارسی/عربی، token timestamps، تنظیمات خروجی، پروژه‌ها و کلید امضای نسخهٔ قبلی حفظ شده‌اند.
- حالت اختیاری «آنلاین سریع» Groq و نگهداری رمزگذاری‌شدهٔ کلید شخصی بدون تغییر باقی مانده است.

هدف این نسخه کاهش زمان انتظار آفلاین است، اما درصد دقیق افزایش سرعت هنوز وابسته به گوشی، مدل انتخابی، طول سکوت‌ها و نوع فایل است و تا آزمون روی ویدیوی واقعی کاربر عدد تضمینی اعلام نمی‌شود.
