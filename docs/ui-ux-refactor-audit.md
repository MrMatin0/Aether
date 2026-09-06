# UI/UX refactor and fixes

## Scope and baseline

Reviewed the Kotlin sources in `ui/`, `ui/components/`, `ui/engine/`, and `ui/theme/`, plus MainActivity, CrashReportActivity, the widget provider, the three XML layouts, English/Persian string resources, existing UI unit tests, and the build workflow. Baseline: main at e991a71cea54d082e472f5d993c67ec9dfe228e8. This is a source-level audit, not a claim that every screen was exercised on a device.

The patch changes 20 existing files and adds two localized resource files, two regression-test files, and this report. The initial estimate was 22 edits plus three additions; separate localization files avoid rewriting unrelated existing resources. Total scope remains 25 files.

## Implemented

- Technical input normalizes Persian/Arabic digits and BiDi markers, remaps selection and composing ranges, and applies numeric constraints before publishing or displaying a value. Numeric pastes saturate rather than truncate or overflow to zero. External reset values synchronize when unfocused or disabled. Active ordinary text edits still ignore delayed persistence echoes.
- Technical input labels and supporting text retain the surrounding layout direction. Masked profile fields request a password keyboard rather than the generic ASCII keyboard.
- Open dropdowns close when disabled, disable their options, and guard selection callbacks. Labels can wrap and the selected menu item exposes selection semantics.
- App-picker search and pending selection survive recreation. Select-search-results preserves unlisted packages already selected. Deselect-listed-apps is explicitly scoped. Loading failure and empty results are separate states; confirmation is disabled until loading succeeds. The dialog is width-bounded on tablets and the whole form scrolls when height is limited.
- Reset, preset deletion, and history clearing require an inline confirmation. Reset clears focus before replacing the profile so a focused field does not keep a stale value. Preset apply/import also clear focus.
- Language choices provide distinct 48dp targets, selection-group semantics and normal interaction feedback. Scan options wrap the time estimate and name rather than force both into a narrow row.
- Onboarding removes the decorative fixed-height diagram, compacts the footer at short heights, and replaces the inaccurate no-logs message with localized disclosure of local diagnostics and optional history.
- Setup names and history details wrap. History collection is lifecycle-aware and date formatting uses the app resource locale. About feature lists are localized; expansion survives recreation, reports its state, and links have minimum touch targets and failure feedback.
- Reduced motion observes system settings and refreshes on resume rather than assuming activity recreation. Dependent engine blocks and status hints honor it; the legacy pipeline no longer loops indefinitely.
- Sharing enumerates LAN addresses off the composition thread, refreshing while visible. Copyable addresses use bound ports rather than unverified default ports. IPv6 host formatting includes brackets.
- Theme window access unwraps an Activity safely. Existing light/dark palettes remain, with tinted on-colors in the light scheme.
- Widget touch target is 48dp. Notification readouts have bounded widths, retaining existing IDs and LTR technical values; compact notifications may ellipsize instead of pushing adjacent readings outside their bounds.
- Negative durations clamp to zero.

## Validation status

Two regression suites were added: TechnicalInputTest (normalization, composing/selection offsets, saturated seconds) and UiUxRegressionTest (search, selection preservation and duration boundaries).

Android build, Kotlin compilation, unit tests, screenshots, TalkBack, keyboard and device tests have NOT been run in this environment. Android SDK, Gradle, Kotlin compiler and emulator are unavailable, and the sandbox has no internet access to install them. Source inspection is not a substitute for compilation or runtime validation.

The existing pull-request Build workflow is configured to assemble the release APK and run `:app:testReleaseUnitTest`. This patch does not change the workflow, signing configuration, native engine, VPN service, credentials storage, or release version. Check the actual PR result before merging; no successful CI result is asserted here.

## Device checks required before merge

1. English and Persian, light/dark/system theme, Android API 26 and a current Android version.
2. Phone widths 320/360/412dp and tablet widths 600/800dp; portrait, landscape and split screen; font scales 1.0, 1.3, 1.5 and 2.0.
3. Type and paste localized digits, move/reverse selections, compose with an IME, exceed seconds limits, reset while focused, and verify persisted values after reopening the app.
4. Rotate an open app picker with unsaved choices and a query. Test empty results, load failure/retry, unlisted selected packages and the on-screen keyboard.
5. Start a VPN through the system tile while a dropdown/app picker is open; ensure settings remain locked.
6. Verify TalkBack labels and focus order for language, scan mode, dropdown selection and inline confirmations. Cancel every destructive confirmation and confirm data remains intact.
7. Toggle system Remove animations without killing the app; verify it updates on return.
8. Change Wi-Fi/hotspot while viewing sharing, including partial port binding. Confirm copied endpoints match active listeners.
9. Verify widget and compact/expanded notifications on multiple OEMs with long throughput values and large text. Ellipsized notification values must remain available in the app.

## Remaining observations, not fixed or runtime-verified

- MainActivity initially assumes onboarding is complete and supplies a default profile before persistence loads. A loading-state redesign should be handled with controller/state tests, not silently mixed into this view refactor.
- HomeScreen keeps a substantial fixed header/footer. Extreme font scaling in a short window still needs device verification; changing shared components does not prove the whole screen fits.
- The legacy ConnectionMeta, TrafficPanel and ConnectButton remain source-compatible, but not all legacy polling/animation behavior was refactored. No unused component was deleted without a whole-project reference check.
- A malformed Persian `noize_desc` string was observed in the source response. It needs raw-file/encoding verification before treating it as confirmed repository corruption.
- Old scan-mode wording remains in some existing resource strings. This patch does not remove compatibility resources.
- Widget locale propagation and actions while disconnecting, diagnostics coroutine lifetime across navigation, and the startup profile race need further investigation outside this patch.
- Crash-report I/O and dialog behavior on device remain unverified.

Do not describe this PR as a fully tested or exhaustive fix of every UI/UX issue.
