# Aether v1.4.6

**The Psiphon and Tor cores are really in the app now.**

The chain feature shipped in 1.4.5's source code but not in its APK. Psiphon's
core is cross-compiled from source during the build, that build step is allowed
to fail without failing the whole APK, and it had been failing every single time:
the pipeline installed Go 1.23 while `psiphon-tunnel-core` requires Go 1.26. So
every published APK was missing `libpsiphon.so`, and the app said so - "this
build does not include the Psiphon core" was the truth, not a bug in the
settings page.

- **Fixed:** the build now uses the Go version Psiphon's own `go.mod` asks for,
  reads that requirement out of upstream's source instead of a hand-maintained
  number, and fails with the file to edit when the two drift apart again.
- **Both cores are verified before they are packaged:** the right CPU
  architecture, and a real executable, per ABI. A core built for the wrong
  architecture used to fail silently on the device.
- **A release can no longer ship without them.** A missing Psiphon or Tor core is
  a warning on a test build and a hard failure on a tagged release, checked both
  when the cores are built and inside the finished APKs.
- **Tor:** `libtor.so` is located inside the Tor Project's published package
  instead of being expected at one fixed path, the download falls back to a
  second mirror, and the country database (`geoip`) has a fallback source - so
  **Tor exit country** selection stops disappearing without explanation.

## What you can do with it

Settings > Chain, seven combinations:

| Mode | Path |
|---|---|
| Aether | device -> Aether -> internet |
| Psiphon | device -> Psiphon -> internet |
| Tor | device -> Tor -> internet |
| Psiphon over Aether | device -> Psiphon -> Aether -> internet |
| Tor over Aether | device -> Tor -> Aether -> internet |
| Tor over Psiphon | device -> Tor -> Psiphon -> internet |
| Tor over Psiphon over Aether | device -> Tor -> Psiphon -> Aether -> internet |

- **Psiphon needs a client config.** `PropagationChannelId` and `SponsorId` are
  issued by the Psiphon network and are not ours to ship. Paste one in
  Settings > Chain, or build with the `PSIPHON_CONFIG_B64` secret set.
- **Tor is slow and carries no UDP.** A first bootstrap on a hostile network
  regularly takes over two minutes. Video calls will not work through a Psiphon
  or Tor chain; use Aether alone for those. QUIC / HTTP3 falls back to TCP by
  itself.
- **DNS works** in every mode: a chain that does not enter through Aether enters
  through a local SOCKS5 front that answers the device's DNS inside the tunnel
  (Tor's own DNSPort, or DNS over TCP for Psiphon). This is the difference
  between "connected and nothing opens" and a working tunnel.

**Version:** 1.4.6, base version code 14. Same signing key and same package name
as 1.4.5, so this installs straight over it and keeps your settings.

<div dir="rtl">

# اِتِر نسخهٔ ۱.۴.۶

**هستهٔ سایفون و تور واقعاً داخل برنامه هست.**

قابلیت زنجیره‌سازی در سورس نسخهٔ ۱.۴.۵ بود، ولی در فایل APK نه. هستهٔ سایفون در
زمان بیلد از سورس کامپایل می‌شود، و آن مرحله اجازه دارد شکست بخورد بدون اینکه کل
APK شکست بخورد - و هر بار شکست می‌خورد: پایپلاین Go نسخهٔ ۱.۲۳ را نصب می‌کرد در
حالی که `psiphon-tunnel-core` به Go نسخهٔ ۱.۲۶ نیاز دارد. پس هیچ APK منتشرشده‌ای
`libpsiphon.so` نداشت و پیام «این بیلد شامل هستهٔ سایفون نیست» حقیقت بود، نه یک
باگ در صفحهٔ تنظیمات.

- **رفع شد:** بیلد از همان نسخهٔ Go استفاده می‌کند که `go.mod` خودِ سایفون
  می‌خواهد، این نیاز را از سورس بالادستی می‌خواند نه از یک عدد دستی، و اگر این دو
  دوباره از هم فاصله گرفتند با نام فایلی که باید ویرایش شود شکست می‌خورد.
- **هر دو هسته قبل از بسته‌بندی بازبینی می‌شوند:** معماری درست پردازنده و اینکه
  واقعاً یک فایل اجرایی است، برای هر ABI. هستهٔ ساخته‌شده برای معماری اشتباه قبلاً
  بی‌صدا روی دستگاه از کار می‌افتاد.
- **یک نسخهٔ رسمی دیگر نمی‌تواند بدون این دو منتشر شود.** نبودن هستهٔ سایفون یا
  تور در بیلدهای آزمایشی هشدار است و روی تگ نسخه خطای قطعی، هم هنگام ساخت هسته‌ها
  و هم داخل APK نهایی بررسی می‌شود.
- **تور:** فایل `libtor.so` داخل بستهٔ منتشرشدهٔ پروژهٔ تور جست‌وجو می‌شود نه در یک
  مسیر ثابت، دانلود یک آینهٔ دوم هم دارد، و پایگاه دادهٔ کشورها (`geoip`) منبع
  جایگزین دارد - پس انتخاب **کشور خروج تور** دیگر بی‌توضیح ناپدید نمی‌شود.

## با آن چه می‌توانید بکنید

تنظیمات > زنجیره، هفت ترکیب:

| حالت | مسیر |
|---|---|
| اتر | دستگاه ← اتر ← اینترنت |
| سایفون | دستگاه ← سایفون ← اینترنت |
| تور | دستگاه ← تور ← اینترنت |
| سایفون روی اتر | دستگاه ← سایفون ← اتر ← اینترنت |
| تور روی اتر | دستگاه ← تور ← اتر ← اینترنت |
| تور روی سایفون | دستگاه ← تور ← سایفون ← اینترنت |
| تور روی سایفون روی اتر | دستگاه ← تور ← سایفون ← اتر ← اینترنت |

- **سایفون به کانفیگ کلاینت نیاز دارد.** مقادیر `PropagationChannelId` و
  `SponsorId` را شبکهٔ سایفون صادر می‌کند و انتشارشان کار ما نیست. یکی را در
  تنظیمات > زنجیره وارد کنید، یا بیلد را با سکرت `PSIPHON_CONFIG_B64` بسازید.
- **تور کند است و UDP حمل نمی‌کند.** اولین بوت‌استرپ روی شبکهٔ سخت‌گیر معمولاً بیش
  از دو دقیقه طول می‌کشد. تماس تصویری از داخل زنجیرهٔ سایفون یا تور کار نمی‌کند؛
  برای آن‌ها فقط از اتر استفاده کنید. QUIC/HTTP3 خودش به TCP برمی‌گردد.
- **DNS در همهٔ حالت‌ها کار می‌کند:** زنجیره‌ای که ورودی‌اش اتر نیست از یک SOCKS5
  محلی وارد می‌شود که DNS دستگاه را داخل تونل پاسخ می‌دهد (DNSPort خود تور، یا
  DNS روی TCP برای سایفون). همین تفاوت «وصل است ولی هیچ سایتی باز نمی‌شود» با یک
  تونل سالم است.

**نسخه:** ۱.۴.۶ با version code پایهٔ ۱۴. کلید امضا و نام پکیج مثل ۱.۴.۵ است، پس
مستقیم روی آن نصب می‌شود و تنظیماتتان می‌ماند.

</div>
