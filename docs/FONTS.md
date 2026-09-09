# Bundled UI fonts

Aether bundles two faces and picks between them by UI language. Neither is ever
resolved over the network at runtime.

| Script | Face | Files | Licence |
| --- | --- | --- | --- |
| Latin (English UI) | Vazirmatn | 5 static weights: Regular, Medium, SemiBold, Bold, ExtraBold | SIL Open Font License 1.1 |
| Persian / Arabic (Persian UI) | Noto Naskh Arabic UI | 1 variable file, `wght` 400–700 | SIL Open Font License 1.1 |

The selection happens in `AetherTheme` (`ui/theme/Theme.kt`), which already
resolves the effective app language for the leading multiplier, and the two
families are declared in `ui/theme/Type.kt`.

## Why two faces and not one

Noto Naskh Arabic UI is an Arabic-script font. google/fonts lists exactly the
`arabic` and `menu` subsets for it, so it carries no usable Latin alphabet:
pointing the English UI at it would hand every Latin glyph to the platform
fallback. That matters more here than in an ordinary app, because the reason the
faces are bundled at all is that this app is used on networks where a font that
has to be downloaded is a font that is missing.

Vazirmatn covers both scripts and is what the Persian UI used before this split.
It stays for Latin, unchanged.

## Why a variable file for Persian

The Persian type scale uses five weights. The variable `wght` axis lets Compose
instantiate all five from one ~200 KB resource
(`FontVariation.Settings(FontVariation.weight(...))` per `Font`) instead of
downloading and packaging five static files.

The axis stops at 700, so ExtraBold requests 700 rather than letting the platform
synthesise a fake 800 — on a naskh face, smeared outlines are exactly what turns
joined letters to mud.

## How they get into the APK

Font binaries follow the same rule as the native cores: **never committed**. The
`fetchUiFonts` Gradle task (see the UI FONTS section of `app/build.gradle.kts`)
downloads them into the gitignored res source set
`app/src/main/res-fonts/font/` before resource merging runs, so they are compiled
into the APK. `scripts/fetch-fonts.sh` does the same thing by hand, for priming a
machine that will later build without network access.

Expected resource names, if you are placing the files yourself:

```text
app/src/main/res-fonts/font/vazirmatn_regular.ttf
app/src/main/res-fonts/font/vazirmatn_medium.ttf
app/src/main/res-fonts/font/vazirmatn_semibold.ttf
app/src/main/res-fonts/font/vazirmatn_bold.ttf
app/src/main/res-fonts/font/vazirmatn_extrabold.ttf
app/src/main/res-fonts/font/noto_naskh_arabic_ui.ttf
```

## Pinned sources

Both downloads are pinned, for the same reason the toolchain is: the APK attached
to a tag must be the APK that tag's source produces.

| Face | Upstream | Pin |
| --- | --- | --- |
| Vazirmatn | [rastikerdar/vazirmatn](https://github.com/rastikerdar/vazirmatn) | tag `v33.003` (`vazirmatnVersion`) |
| Noto Naskh Arabic UI | [google/fonts](https://github.com/google/fonts), `ofl/notonaskharabicui` | commit `5fb648bb932bf1cdcd5fd71a73b79097e8666c36` (`notoNaskhArabicCommit`) |

google/fonts is pinned to a commit rather than a tag because it has none and its
default branch moves several times a day. To move either pin, edit the value in
`app/build.gradle.kts`, mirror it in `scripts/fetch-fonts.sh`, delete
`app/src/main/res-fonts/font/` and rebuild — the task keeps files that are
already present, so a stale download will not be replaced on its own.

The upstream file name for the variable font is `NotoNaskhArabicUI[wght].ttf`.
The brackets are percent-encoded (`%5B` / `%5D`) in both the Gradle task and the
shell script: `java.net.URI` rejects them raw because square brackets are
reserved for IPv6 hosts, and `curl` would read them as a glob.

## Licence notices

Both faces are licensed under the SIL Open Font License, Version 1.1. The
full licence text ships with each upstream project
([Vazirmatn](https://github.com/rastikerdar/vazirmatn/blob/master/OFL.txt),
[Noto Naskh Arabic UI](https://github.com/google/fonts/blob/main/ofl/notonaskharabicui/OFL.txt)).

- Vazirmatn: Copyright © Saber Rastikerdar.
- Noto Naskh Arabic UI: Copyright © 2019 Google LLC. All Rights Reserved.

The OFL permits bundling in an application, including a commercial one, provided
the copyright notice and licence travel with the font and the Reserved Font Names
are not reused for modified versions. Neither file is modified here — they are
downloaded verbatim and packaged as-is — so no renaming obligation applies. Do
not replace either file with a face whose licence does not allow redistribution:
the APK is published, so anything in `res-fonts/` is being redistributed.
