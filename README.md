<a id="readme-top"></a>

<div align="center" dir="rtl">

<a href="https://github.com/MrMatin0/Aether/releases/latest"><img src=".github/assets/readme/hero-fa.svg" width="100%" alt="Aether+ : عبور هوشمند از محدودیت‌های اینترنت"></a>

<br>

<a href="https://github.com/MrMatin0/Aether/releases"><img src="https://img.shields.io/github/v/release/MrMatin0/Aether?include_prereleases&style=for-the-badge&logo=github&logoColor=white&label=release&color=7C3AED" alt="Release"></a>
<a href="https://github.com/MrMatin0/Aether/releases"><img src="https://img.shields.io/github/downloads/MrMatin0/Aether/total?style=for-the-badge&logo=android&logoColor=white&label=downloads&color=0891B2" alt="Downloads"></a>
<img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 8.0+">
<a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0--or--later-EC4899?style=for-the-badge" alt="AGPL-3.0-or-later"></a>
<a href="https://github.com/MrMatin0/Aether/stargazers"><img src="https://img.shields.io/github/stars/MrMatin0/Aether?style=for-the-badge&logo=githubsponsors&logoColor=white&color=F59E0B" alt="Stars"></a>

**فارسی** · [English](README.en.md)

<a href="#features">قابلیت‌ها</a> •
<a href="#quick-start">شروع سریع</a> •
<a href="#modes">حالت‌های اتصال</a> •
<a href="#chains">زنجیره‌ها</a> •
<a href="#scanner">اسکنر</a> •
<a href="#privacy">حریم خصوصی</a> •
<a href="#troubleshooting">عیب‌یابی</a> •
<a href="#credits">قدردانی</a>

</div>

<div dir="rtl">

<br>

> **Aether+** یک کلاینت بومی اندروید برای عبور از سانسور اینترنت است که با Kotlin و Jetpack Compose ساخته شده. خودش بهترین نقطهٔ اتصال را پیدا می‌کند، ترافیک را از یک تونل رمزگذاری‌شده عبور می‌دهد و وقتی یک مسیر به‌تنهایی جواب نمی‌دهد، هسته‌های **Aether**، **Psiphon**، **Tor** و سرور **Xray** شخصی شما را روی هم زنجیره می‌کند.

<p align="center"><img src=".github/assets/readme/divider.svg" width="100%" alt=""></p>

<a id="features"></a>

## ✨ قابلیت‌ها

<table>
<tr>
<td width="50%" valign="top">

**🧠 Smart Auto و چند پروتکل**<br>
MASQUE روی HTTP/3 یا HTTP/2، WireGuard و Gool (WireGuard تودرتو). حالت Smart Auto مسیر مناسب را خودش انتخاب می‌کند.

</td>
<td width="50%" valign="top">

**📡 اسکنر تطبیقی نقطهٔ اتصال**<br>
سه حالت Turbo، Precise و Ultra، به‌همراه تعیین دستی نقطهٔ اتصال یا بازه‌های IP دلخواه.

</td>
</tr>
<tr>
<td valign="top">

**🔗 زنجیرهٔ هسته‌ها**<br>
Aether، Psiphon و Tor به‌تنهایی یا به‌صورت زنجیره‌ای؛ ۱۲ ترکیب آماده که با یک انتخاب فعال می‌شوند.

</td>
<td valign="top">

**🛰️ سرور Xray شخصی**<br>
VLESS، VMess، Trojan، Shadowsocks، Hysteria2، WireGuard و SOCKS به‌عنوان اولین هاپ، زیر Aether، Psiphon یا Tor.

</td>
</tr>
<tr>
<td valign="top">

**🧅 پل‌های Tor**<br>
obfs4، Snowflake، WebTunnel و meek. پل‌های داخلی به‌صورت پیش‌فرض روشن‌اند و اگر یکی مسدود باشد، نوبت بعدی امتحان می‌شود.

</td>
<td valign="top">

**🌐 Smart DNS**<br>
DNS معمولی، DoH و DoT با تشخیص خودکار نوع سرور و آزمون واقعی زمان پاسخ، مستقیم از صفحهٔ اتصال.

</td>
</tr>
<tr>
<td valign="top">

**🎯 کنترل کامل مسیر**<br>
VPN سراسری، حالت فقط پراکسی، Split Tunneling برای هر برنامه، Kill Switch، محافظت از نشت IPv6 و تنظیم DNS و MTU.

</td>
<td valign="top">

**🎨 طراحی Aurora**<br>
رابط فارسی و انگلیسی با پشتیبانی کامل RTL، فونت وزیرمتن، آمار زنده، تاریخچهٔ نشست‌ها، کاشی تنظیمات سریع و ویجت.

</td>
</tr>
</table>

> [!NOTE]
> وجود یک قابلیت به معنی تضمین اتصال نیست. نتیجه به شبکهٔ شما، هستهٔ انتخاب‌شده و محتوای APK نصب‌شده بستگی دارد.

<a id="quick-start"></a>

## 🚀 شروع سریع

| پیش‌نیاز | توضیح |
|:--|:--|
| 📱 **اندروید** | نسخهٔ ۸.۰ یا جدیدتر (API 26+) |
| 🧩 **معماری** | ARM64 (`arm64-v8a`) یا ARM سی‌ودوبیتی (`armeabi-v7a`) |
| 🔓 **دسترسی** | بدون نیاز به روت؛ فقط تأیید مجوز VPN اندروید |

1. **دانلود:** از صفحهٔ [Releases](https://github.com/MrMatin0/Aether/releases) فایل مناسب دستگاهتان را بگیرید. برای استفادهٔ روزمره، نسخهٔ دارای تگ را به **Pre-release** ترجیح دهید.
2. **نصب:** APK را نصب و **Aether+** را باز کنید، راهنمای آغازین را طی کنید و زبان دلخواه را انتخاب کنید.
3. **اتصال:** با هستهٔ Aether و تنظیمات پیش‌فرض شروع کنید، دکمهٔ اتصال را بزنید و درخواست VPN اندروید را تأیید کنید.
4. **بررسی:** پس از اتصال، باز شدن واقعی محتوا را در یک برنامه امتحان کنید. اگر وصل نشد، پیش از افزودن لایه‌های زنجیره سری به بخش تشخیص خطا بزنید.

| فایل | مناسب برای |
|:--|:--|
| `arm64-v8a` | بیشتر گوشی‌های امروزی |
| `armeabi-v7a` | گوشی‌های قدیمی‌تر ۳۲بیتی |
| `universal` | اگر مطمئن نیستید؛ شامل هر دو معماری ARM است، **نه x86 یا x86_64** |

<p align="center">
<a href="https://github.com/MrMatin0/Aether/releases/latest"><img src=".github/assets/readme/download-fa.svg" width="340" alt="دانلود آخرین نسخه"></a>
</p>

> [!TIP]
> به‌روزرسانی فقط وقتی روی نسخهٔ فعلی نصب می‌شود که شناسهٔ برنامه یکسان و کلید امضا سازگار باشد. Aether+ با شناسهٔ `io.github.mrmatin0.aether` نصب می‌شود و می‌تواند در کنار نسخه‌های بالادستی روی یک دستگاه باشد.

<a id="modes"></a>

## 🧭 حالت‌های اتصال

| حالت | چه چیزی را پوشش می‌دهد | کِی مناسب است |
|:--|:--|:--|
| 🛡️ **VPN** | ترافیک کل دستگاه، طبق قواعد مسیریابی و Split Tunneling | استفادهٔ روزمره |
| 🔌 **فقط پراکسی** | فقط برنامه‌هایی که خودتان روی پراکسی محلی تنظیم کنید | وقتی فقط چند برنامهٔ مشخص لازم است |
| 📶 **اشتراک در شبکهٔ محلی** | پراکسی SOCKS5 و HTTP برای دستگاه‌های دیگر | فقط در Wi-Fi یا هات‌اسپات مورد اعتماد |

در حالت فقط پراکسی، برنامه‌های دیگر لزوماً از تونل عبور نمی‌کنند. اشتراک در شبکهٔ محلی به‌صورت پیش‌فرض خاموش است؛ پس از روشن‌کردن، از نشانی و پورت‌هایی که در پنل اشتراک نمایش داده می‌شود استفاده کنید.

<a id="chains"></a>

## 🔗 زنجیره‌ها

<p align="center"><img src=".github/assets/readme/chain.svg" width="100%" alt="زنجیرهٔ کامل: Android، Xray، Aether، Psiphon، Tor، اینترنت"></p>

عبارت **« X روی Y»**