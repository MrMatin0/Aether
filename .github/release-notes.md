Aether Mobile v1.2.4

Kill Switch and Strict Kill Switch, IPv6 leak protection, smart reconnect with a
retry limit, per-app internet blocking, advanced engine settings, deep watchdog
fixes - and a fresh 0-100 security audit scoring 92/100.

## What's new in v1.2.4

- **Kill Switch and Strict Kill Switch**: on an unexpected drop, a blocking blackhole TUN stays up so no traffic leaks outside the VPN; Strict mode keeps blocking even after a manual disconnect until you lift it yourself.
- **IPv6 Leak Protection**: new toggle, on by default; the IPv6 default route is kept inside the tunnel.
- **Smart Reconnect with a retry limit**: the number of automatic reconnect attempts is configurable (3 to 20) before an error is reported.
- **Per-app internet blocking**: a newly added userspace filter bridge resolves each flow's owning app and drops blocked apps' traffic; it replaces the default hev forwarder only while this feature is enabled, so the default path stays untouched.
- **Advanced engine settings**: Fragment Size/Delay ranges, No Data Check, TLS Groups, Validate/Reconnect Secs, No Profile Retry and the core log level, all validated and mapped only to flags the bundled native core actually supports.
- **New UI sections**: "Security & stability" and "Advanced engine settings" in the Advanced panel, fully localized (English + فارسی) and persisted in DataStore and the profile codec.
- **"Works 1-2 minutes, then no site opens" fixed**: a connection watchdog probes the tunnel end-to-end every 30 seconds and restarts the engine on sustained failure, and the tunnel's idle timeouts were raised (TCP 60 s to 5 min, UDP to 120 s).
- **Periodic drop-outs fixed at the root**: the watchdog probe is now multi-attempt and multi-target and restarts the engine only after three consecutive failed checks, so brief, self-healing network stalls no longer kill a healthy session and force a long endpoint rescan.
- **Desktop-parity info row**: Protocol, Endpoint and live Latency now sit directly under the IP badge (ported from the Windows edition); the old standalone ping badge was removed.
- **Security**: a fresh 0-100 security audit was performed and scored **92/100** (full report: `docs/SECURITY_AUDIT_1.2.4.md`):
  | Area | Result |
  | --- | --- |
  | 1. Keys & secrets | Zero Trust secrets sealed in the Android Keystore (AES-GCM); no hardcoding; passed to the engine only via env |
  | 2. Cryptography & protocols | WireGuard and MASQUE/QUIC with TLS 1.3, ECH and ClientHello fragmentation; no custom crypto |
  | 3. Data leaks | Full IPv4/IPv6 routing, in-tunnel DNS, Kill Switch; the only public egress is the IP badge's geolocation probe |
  | 4. Local storage | Private DataStore, `allowBackup=false`, no exported Provider |
  | 5. Permissions & manifest | Minimal permissions, no `QUERY_ALL_PACKAGES` or `debuggable` |
  | 6. Logs | In-memory only; engine output reaches Logcat only in debug builds |
  | 7. Code quality & network | Cleartext denied app-wide, proxies bound to 127.0.0.1, input validation; watchdog self-DoS fixed |
- **Version**: app 1.2.4 (version code 8).

## Install

- Version code 8, same signing certificate as 1.2.3, so existing users can install this directly over their current app and keep all their settings - no uninstall required.

---

<div dir="rtl">

## تازه‌های نسخهٔ ۱.۲.۴

- **کیل‌سوییچ و کیل‌سوییچ سخت‌گیرانه:** در قطعی غیرمنتظره، یک رابط <span dir="ltr">TUN</span> مسدودکننده (blackhole) فعال می‌ماند تا هیچ ترافیکی خارج از تونل نشت نکند؛ حالت سخت‌گیرانه حتی پس از قطع دستی هم مسدودسازی را نگه می‌دارد تا خودتان لغوش کنید.
- **محافظت در برابر نشت <span dir="ltr">IPv6</span>:** کلید جدید، به‌صورت پیش‌فرض روشن؛ مسیر پیش‌فرض <span dir="ltr">IPv6</span> داخل تونل نگه داشته می‌شود.
- **اتصال مجدد هوشمند با محدودیت تلاش:** تعداد تلاش‌های خودکار اتصال مجدد قابل تنظیم است (۳ تا ۲۰) و پس از آن خطا گزارش می‌شود.
- **مسدودسازی اینترنت اپ‌ها:** بریج فیلتر <span dir="ltr">userspace</span> تازه‌ای که به برنامه افزوده شده مالک هر جریان را تشخیص می‌دهد و ترافیک اپ‌های مسدودشده را حذف می‌کند؛ فقط وقتی این قابلیت فعال است جایگزین مسیر پیش‌فرض <span dir="ltr">hev</span> می‌شود و مسیر پیش‌فرض دست‌نخورده باقی می‌ماند.
- **تنظیمات پیشرفتهٔ هسته:** بازهٔ اندازه/تأخیر فرگمنت، <span dir="ltr">No Data Check</span>، گروه‌های <span dir="ltr">TLS</span>، ثانیه‌های <span dir="ltr">Validate/Reconnect</span>، <span dir="ltr">No Profile Retry</span> و سطح لاگ هسته؛ همه با اعتبارسنجی و فقط با فلگ‌هایی که هستهٔ بومی واقعاً پشتیبانی می‌کند.
- **بخش‌های تازهٔ رابط کاربری:** «امنیت و پایداری» و «تنظیمات پیشرفتهٔ هسته» در پنل پیشرفته، با رشته‌های فارسی و انگلیسی و ذخیره‌سازی کامل در <span dir="ltr">DataStore</span> و کدک پروفایل.
- **رفع مشکل «یکی دو دقیقه کار می‌کند، بعد سایت باز نمی‌شود»:** واچداگ اتصال هر ۳۰ ثانیه یک پروب سرتاسری از داخل <span dir="ltr">SOCKS5</span> هسته می‌زند و پس از خرابی پایدار هسته را ری‌استارت می‌کند؛ تایم‌اوت بیکاری <span dir="ltr">TCP</span> از ۶۰ ثانیه به ۵ دقیقه و <span dir="ltr">UDP</span> به ۱۲۰ ثانیه افزایش یافت.
- **رفع ریشه‌ای قطعی‌های دوره‌ای:** پروب واچداگ حالا در هر بررسی سه تلاش با سه هدف متفاوت انجام می‌دهد و هسته فقط پس از سه بررسی ناموفق پشت‌سرهم ری‌استارت می‌شود؛ در نتیجه لرزش‌های کوتاه و خودبه‌خودبرطرف‌شوندهٔ شبکه دیگر یک سشن سالم را نمی‌کشند و قطعی چندده‌ثانیه‌ای ایجاد نمی‌کنند.
- **ردیف اطلاعات هم‌تراز با نسخهٔ دسکتاپ:** پروتکل، اندپوینت و تأخیر زنده حالا دقیقاً زیر نشان IP نمایش داده می‌شوند (پورت از نسخهٔ ویندوز) و نشان پینگ مستقل قبلی حذف شده است.
- **امنیت:** یک ممیزی امنیتی تازهٔ صفر تا صد انجام شد و امتیاز **<span dir="ltr">۹۲ از ۱۰۰</span>** گرفت (گزارش کامل: <span dir="ltr">`docs/SECURITY_AUDIT_1.2.4.md`</span>):
  | بخش | نتیجه |
  | --- | --- |
  | ۱. کلیدها و اسرار | اسرار <span dir="ltr">Zero Trust</span> در <span dir="ltr">Android Keystore (AES-GCM)</span> مُهروموم شده؛ بدون هاردکد؛ انتقال فقط با <span dir="ltr">env</span> |
  | ۲. رمزنگاری و پروتکل‌ها | <span dir="ltr">WireGuard</span> و <span dir="ltr">MASQUE/QUIC</span> با <span dir="ltr">TLS 1.3</span>، ‏<span dir="ltr">ECH</span> و فرگمنت <span dir="ltr">ClientHello</span>؛ بدون رمز سفارشی |
  | ۳. نشت اطلاعات | روت کامل <span dir="ltr">IPv4/IPv6</span>، ‏<span dir="ltr">DNS</span> داخل تونل، <span dir="ltr">Kill Switch</span>؛ تنها خروجی عمومی، پروب ژئولوکیشن نشان IP است |
  | ۴. ذخیره‌سازی محلی | <span dir="ltr">DataStore</span> خصوصی، ‏<span dir="ltr">`allowBackup=false`</span>، بدون <span dir="ltr">Provider</span> صادرشده |
  | ۵. مجوزها و مانیفست | حداقل مجوزها، بدون <span dir="ltr">`QUERY_ALL_PACKAGES`</span> و <span dir="ltr">`debuggable`</span> |
  | ۶. لاگ‌ها | فقط در حافظه؛ خروجی موتور به <span dir="ltr">Logcat</span> فقط در بیلد <span dir="ltr">debug</span> |
  | ۷. کیفیت کد و شبکه | <span dir="ltr">Cleartext</span> ممنوع در کل اپ، پراکسی‌ها روی <span dir="ltr">127.0.0.1</span>، اعتبارسنجی ورودی‌ها؛ رفع <span dir="ltr">self-DoS</span> واچداگ |
- **نسخه:** برنامهٔ <span dir="ltr">۱.۲.۴</span> و <span dir="ltr">version code</span> برابر <span dir="ltr">۸</span>.

### نصب

- <span dir="ltr">version code</span> برابر <span dir="ltr">۸</span> با همان گواهی امضای نسخهٔ ۱.۲.۳؛ کاربران فعلی می‌توانند مستقیم روی برنامهٔ موجود نصب کنند و همهٔ تنظیماتشان حفظ می‌شود - بدون نیاز به حذف نصب.

</div>
