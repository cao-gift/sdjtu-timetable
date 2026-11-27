<div align="center">

<img src="app/src/main/res/drawable-nodpi/lvlv_artwork.png" width="96" height="96" alt="Lǘlǘ Timetable Icon" />

# 驴驴课表 · Lǘlǘ Timetable

**An unofficial Android timetable app dedicated to Shandong Jiaotong University (SDJTU)**
Sign in once via WebVPN for instant offline access to timetables, grades, empty rooms, curriculum plans, and exams.

[![Version](https://img.shields.io/badge/Version-v1.6.0_(79)-1B1B1F?style=flat-square)](https://github.com/cao-gift/sdjtu-timetable/releases)
[![Android](https://img.shields.io/badge/Android-7.0+-2F5D3A?style=flat-square)](https://lvlv.freeblock.cn)
[![License](https://img.shields.io/badge/License-GPL--3.0-555450?style=flat-square)](LICENSE)
[![Website](https://img.shields.io/badge/Mirror-lvlv.freeblock.cn-FF4A1C?style=flat-square)](https://lvlv.freeblock.cn)

[简体中文](README.md) · **English**

[Screenshots](#screenshots) · [SDJTU Academic Tools](#sdjtu-academic-tools) · [Everyday Experience](#everyday-experience) · [Diff from Upstream](#diff-from-upstream) · [Install & Build](#install--build) · [Privacy & License](#privacy--license)

</div>

---

## Screenshots

> Sample course and instructor names are anonymized. Ships with **Frosted Glass** by default and optional **Paper & Ink**, both supporting light and dark modes.

<table>
  <tr>
    <td align="center" width="20%">
      <img src="https://lvlv.freeblock.cn/showcase/01-timetable.jpg" alt="Weekly Timetable" />
      <br /><sub><b>Weekly Timetable</b></sub>
    </td>
    <td align="center" width="20%">
      <img src="https://lvlv.freeblock.cn/showcase/02-grades.jpg" alt="Grades & GPA Trend" />
      <br /><sub><b>Grades &amp; GPA Trend</b></sub>
    </td>
    <td align="center" width="20%">
      <img src="https://lvlv.freeblock.cn/showcase/03-rooms.jpg" alt="Live Empty Rooms" />
      <br /><sub><b>Live Empty Rooms</b></sub>
    </td>
    <td align="center" width="20%">
      <img src="https://lvlv.freeblock.cn/showcase/04-exams.jpg" alt="Exam Timeline" />
      <br /><sub><b>Exam Timeline</b></sub>
    </td>
    <td align="center" width="20%">
      <img src="https://lvlv.freeblock.cn/showcase/05-plan.jpg" alt="Curriculum Plan" />
      <br /><sub><b>Curriculum Plan</b></sub>
    </td>
  </tr>
</table>

---

## SDJTU Academic Tools

Locked directly to SDJTU Unified Portal (`wwebvpn.sdjtu.edu.cn`) so all five academic modules share one session from on- or off-campus networks:

| Module | Highlights | Mode |
| :--- | :--- | :---: |
| **Timetable Sync** | One-tap top-bar sync for current & historical terms; merges adjacent identical sessions, separates course code from title, and preserves custom entries | Offline |
| **Grades & GPA** | Term breakdown with GPA trend chart, score sorting, periodic new-grade notifications, and tap-to-reveal anti-spoiler grade cards | Offline |
| **Empty Rooms** | Live uncached query pre-filled with Wuyingshan Campus, Building 1, current week, and weekday; renders per-room period matrix with quick slot filters | Live |
| **Curriculum Plan** | Syncs legacy Qiangzhi execution plans with total course/credit/required stats, horizontal semester ruler, category grouping, and search | Offline |
| **Exam Schedule** | Merges regular final exams and early in-class finals into a single deduplicated hairline timeline with reminders & system calendar sync | Offline |
| **Auto-Login** | Opt-in local credential encryption via Android Keystore (AES-256-GCM); automatically falls back to the visible portal when extra verification is required | Opt-in |

> [!TIP]
> Because SDJTU's legacy Qiangzhi queries run inside sub-frames, every academic screen includes a **"Locate in Portal → Read this page"** manual fallback if menu routes ever change.

---

## Everyday Experience

- **Tactile Timetable**: Full week on one screen; drag the top-left week numeral like a precision dial; ongoing classes fill with time and upcoming classes display a 15-minute countdown; long-press empty cells to draw a class directly.
- **Smart Location Simplifier & SDJTU Defaults**: Automatically extracts frequent campus/building prefixes for one-tap hiding with live preview (details & exports retain raw locations); pre-calibrated to SDJTU bell times (`08:30–11:45`, `13:30–16:45`, `19:00–20:35`) and 2026–2027 Term 1 (`2026-08-24`), with one-tap week calibration and statutory holiday make-up handling.
- **Widgets & Reminders**: Resizable 2×2 to wide "Today's Classes" widget with live countdowns; rain-alert glow via Open-Meteo using built-in SDJTU campus coordinates (Wuyingshan / Changqing / Weihai — zero location permission); background schedule-change notifications, silent pre-class alerts, persistent next-class shade notification, and homework deadlines with photo attachments.
- **Utilities & Classmate Free-Time Matching**: Built-in GPA target simulator, tuition per-class cost, and absence tracker; exchange sanitized timetables (no student IDs or instructors) via QR image or share code and compute shared free slots in one tap.
- **Dual Themes & Local Ownership**: Frosted Glass (default) and Paper & Ink with circular reveal switching, direct Android calendar sync, and local JSON backup/restore.

---

## Diff from Upstream

Derived from [augustlies/tangli](https://github.com/augustlies/tangli) under **GPL-3.0**, signed with standalone package ID `app.lvlv.timetable` so it can coexist with upstream.

<details>
<summary>View key additions and removals relative to upstream</summary>

- **Added for SDJTU**: Live empty classroom period matrix, offline curriculum plan browser, merged regular + early final exam timeline, opt-in Android Keystore auto-login, automatic consecutive-session merging, smart location hide-phrases with live preview, course code toggle, and manual portal capture fallback.
- **Streamlined**: Dedicated exclusively to SDJTU WebVPN and legacy Qiangzhi parsing; removed multi-school directory/adapters, DNS ad-blocking, VPN service, and AI/text-command editing (`lvlv://`); simplified bottom navigation to Timetable / Settings and unified Gauge UI components across all screens.

</details>

---

## Install & Build

### Download

1. Grab the latest APK from **[lvlv.freeblock.cn](https://lvlv.freeblock.cn)** (fast mainland China CDN) or **[GitHub Releases](https://github.com/cao-gift/sdjtu-timetable/releases)** on **Android 7.0+**. In-app updates verify SHA-256 automatically with multi-mirror fallback.
2. Open **Settings → Sign in to Unified Portal** to sync via SDJTU WebVPN.

### Build Locally (JDK 17)

```bash
./gradlew assembleDebug        # Build debug APK
./gradlew testReleaseUnitTest  # Run JVM unit tests
```

---

## Privacy & License

- **Privacy First**: An unofficial student-built tool unaffiliated with SDJTU. **Zero cloud backends, zero ads, zero analytics SDKs** — all academic data and optional encrypted credentials remain strictly on your device. See [Privacy Policy](https://lvlv.freeblock.cn/privacy.html) and [CHANGELOG](CHANGELOG.md).
- **Feedback**: Open a [GitHub Issue](https://github.com/cao-gift/sdjtu-timetable/issues) or email `3514088137@qq.com`.
- **License & Credits**: Derived from [Tangli (augustlies/tangli)](https://github.com/augustlies/tangli) (© 2026 August Lies) under [GPL-3.0](LICENSE). Frosted Glass references [Uiverse.io](https://uiverse.io) galaxy (MIT); QR scanning/generation uses ZXing (Apache-2.0).
