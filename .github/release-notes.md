# AetherMobile v1.4.3

**UI/UX polish and visual bug fixes.** No engine, tunnel or networking behaviour changed in this release.

### Fixed
- **Connect ring no longer clipped.** Its diameter was hardcoded at 244dp, which is wider than the content column on small phones, in landscape and at larger display sizes — the outer ticks were cut off. It is now derived from the available width and capped at the design size.
- **Diagnostics actions no longer run off the screen.** "Clear" was pushed past the right margin on narrow screens and in Persian, where the labels are longer. Run now owns a full-width row; Copy and Clear share the row below.
- **Onboarding no longer clips its own copy.** The first-run pages were a fixed-height, non-scrolling layout, so in landscape, in split-screen or at a large font scale the body text was pushed out of view. Pages stay centred when there is room and scroll when there is not.
- **Traffic sparkline is visible on real hardware.** Its line widths were specified in raw pixels instead of dp, so both traces rendered as sub-hairlines on 2.75x/3x screens.
- **Log console is byte-order faithful in Persian.** It was the one technical readout not pinned LTR, so timestamps, IPs and ports were visually reordered on the exact screen people are asked to screenshot for bug reports.
- **Text no longer hard-cuts mid-glyph.** Bounded lines across the ledger, the traffic meter, the status headline, the dropdowns and the app picker now ellipsise instead of clipping — most visible in Persian, where a cut through a joined letterform reads as a rendering fault.
- **Elapsed timer is centred** instead of hanging off the start edge of an otherwise centre-aligned screen.

### Accessibility
- Settings toggles, segmented selectors and the app picker are now single, correctly-labelled nodes announcing their on/off or selected state, instead of pairs of unnamed controls.
- Dropdowns announce themselves as collapsed lists; the copy action announces what it does.
- Every tap target in the shared components meets the 48dp minimum (settings rows, segments, action pills and dropdown fields were 41–45dp).
- The settings-locked notice is a live region, so it is announced when it appears.

### Polish
- Chevrons and the onboarding progress track animate on the app's shared motion curve instead of snapping.
- Tab touch feedback is clipped to a rounded shape, so it no longer flashes a hard-edged rectangle in a fully rounded UI.

### Version
- Version: AetherMobile 1.4.3, version code 11.
- Signing configuration unchanged, so 1.3.x users can install this over their existing app.

<div dir="rtl">

# AetherMobile نسخهٔ ۱.۴.۳

**بهبود رابط کاربری و رفع ایرادهای بصری.** رفتار موتور، تونل و شبکه در این نسخه تغییری نکرده است.

### رفع ایراد
- **حلقهٔ اتصال دیگر بریده نمی‌شود.** قطر آن به صورت ثابت ۲۴۴dp بود، که از عرض ستون محتوا در گوشی‌های کوچک بیشتر است. اکنون از عرض در دسترس محاسبه می‌شود.
- **دکمه‌های عیب‌یابی از لبهٔ صفحه خارج نمی‌شوند.** دکمهٔ «پاک کردن» در صفحه‌های باریک و در فارسی بریده می‌شد.
- **متن صفحات معرفی دیگر بریده نمی‌شود** و در ارتفاع کم قابل اسکرول است.
- **نمودار ترافیک روی دستگاه واقعی دیده می‌شود** (ضخامت خطوط به جای پیکسل خام، بر حسب dp).
- **کنسول لاگ در فارسی درست نمایش داده می‌شود**؛ تنها جایی بود که LTR تثبیت نشده بود و زمان، IP و پورت جابه‌جا می‌شدند.
- **متن دیگر از میان حروف بریده نمی‌شود** و با سه‌نقطه کوتاه می‌شود.
- **زمان‌شمار وسط‌چین شد.**

### دسترسی‌پذیری
- کلیدهای تنطیمات، انتخابگرهای قطعه‌ای و انتخاب برنامه‌ها اکنون یک گرهٔ واحد با وضعیت مشخص اعلام می‌کنند.
- تمام ناحیه‌های لمس حداقل ۴۸dp شدند (پیش‌تر ۴۱ تا ۴۵dp بودند).
- اطلاعیهٔ قفل شدن تنطیمات به محض نمایش خوانده می‌شود.

### نسخه
نسخه: AetherMobile 1.4.3، version code 11.

</div>
