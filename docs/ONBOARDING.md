# First-run welcome (v1.5.0)

The onboarding shown on first launch was rebuilt from three static text pages into a four-beat story. Code: `ui/OnboardingScreen.kt` (UI), `ui/WelcomeFlow.kt` (page/progress rules), copy in `res/values*/strings_welcome.xml`.

## The story

| # | Beat | What the user learns | Hero |
|---|------|----------------------|------|
| 1 | Hello | One tap, no account, EN/FA, dark/light | The connect orb with orbiting satellites (mirrors Home) |
| 2 | Smart mode | The app picks MASQUE / WireGuard / WARP×2 and obfuscation for you | Three routes, two blocked, one taken |
| 3 | Chain | 7 routes with Psiphon and Tor, picked from Home, each hop costs speed | You → Aether → Psiphon → Tor → Internet, lit hop by hop |
| 4 | Privacy | Logs/history stay local; Android will ask for VPN (and notifications) next | Shield with a slow ripple |

Page 4 exists for a reason: `MainActivity` raises the notification prompt immediately after onboarding, and the VPN consent dialog appears on the first connect. Both are now questions the user was told about.

## Copy

Conversational, second person, short lines, in both languages. Every claim must stay true. The 1.2.x audit removed a "no logs" line because it was inaccurate, so page 4 says where logs live, not that they do not exist. The old `onboarding_*` strings are left in `strings.xml` untouched and unreferenced.

## UX details

- Segmented progress bar that fills continuously with the swipe, plus an "N of M" label announced politely to TalkBack.
- Skip in the top bar (hidden on the last page, where the primary button already finishes; its slot is kept so the language toggle does not jump).
- Back button from page 2 on, in addition to system Back.
- Per-page CTA labels ("Show me how", "Nice, what else?", "Got it", "Let's go").
- Tone: brand for pages 1 to 3, protected (mint) on page 4 only. Working and failed colours are never used here because they carry meaning on Home.
- `finish()` is idempotent, so a double tap on the last page cannot mark completion twice. The primary button checks `settledPage`, so a tap mid-swipe cannot finish from page 3.
- Short windows (< 560dp tall: landscape, split screen, huge font) drop the illustrations and shrink the title; copy is never cut and every page scrolls.
- RTL: the pager, arrows (AutoMirrored), progress fill, route flow direction and the chain label all follow the layout direction.

## Motion and performance

- Only the settled page animates, and every animated value is read in the draw phase: a running loop redraws one Canvas and never recomposes the screen.
- With system "Remove animations", no loop runs; each hero shows a fixed representative frame and page changes snap.
- Durations and curves come from `ui/theme/Motion.kt` (`AetherDur`, `AetherEaseOutExpo`).

## Startup flash fix

`MainActivity` used `collectAsState(initial = true)` for the onboarding flag, so a first-run user saw Home for a frame before onboarding replaced it (listed as an open item in `docs/ui-ux-refactor-audit.md`). The initial value is now `null` and nothing but the background is drawn until the flag is read.

## Validation

`WelcomeFlowTest` covers story order, index clamping, Skip/Back visibility, last-page detection and progress fill. The UI itself has not been run on a device in this change; before release check EN/FA, light/dark, font scale 1.0 to 2.0, landscape, TalkBack order, and Remove animations.
