# 驴驴课表 · Lǘlǘ Timetable

**An unofficial Android timetable app dedicated to Shandong Jiaotong University (SDJTU)** (current version **1.3.15 (67)**), derived from [augustlies/tangli](https://github.com/augustlies/tangli) and open-sourced under **GPL-3.0**.

[简体中文](README.md) | **English**

Sign in once through SDJTU WebVPN to store your **timetable, grades, empty classrooms, curriculum plan, and exam schedule** locally for instant offline access — featuring the **Frosted Glass** theme by default, home-screen widgets, class/rain reminders, and classmate free-time matching.

[Changes from Upstream](#changes-from-upstream) · [SDJTU Features](#sdjtu-features) · [Features](#features) · [Installation](#installation) · [Changelog](CHANGELOG.md)

---

## Changes from Upstream

Modified from [augustlies/tangli](https://github.com/augustlies/tangli) `v1.3.1` specifically for **Shandong Jiaotong University** (legacy Qiangzhi academic system via WebVPN):

### Added Features
- **Empty Classroom Finder**: Choose campus and week (optional building with cascading options) in-app, search rooms, filter by occupancy or all-day availability, with offline caching.
- **Curriculum Plan**: Sync and browse the Qiangzhi execution plan grouped by category, supporting multiple plans, semester filters, name/code search, and offline caching.
- **Exam Schedule**: Combines regular final exams and early in-class final exams for the current term into one deduplicated, chronological offline list.
- **Optional Portal Auto-Login**: Opt-in under Settings → Academic Account; credentials are encrypted locally via Android Keystore, automatically falling back to the visible WebVPN portal when extra verification is required.
- **Location Hide Phrases & Course Code Toggle**: Displays full classroom locations by default with user-configurable hide phrases for compact cards (full location preserved in details/export), plus a toggle for showing course codes.
- **Manual Fallback Capture**: If automatic menu navigation fails inside legacy frames, locate the page manually in the academic portal and tap "Read this page".

### Key Modifications & Simplifications
- **SDJTU-Only Focus**: Locked to SDJTU WebVPN (`wwebvpn.sdjtu.edu.cn`) with the university seal in Settings; removed the multi-school directory, school picker, custom school URLs, and other universities' adapters/assets.
- **Legacy Qiangzhi & Calendar Fixes**: Fixed course code being parsed as course name on legacy Qiangzhi timetables; calibrated SDJTU 2026–2027 Term 1 start date (2026-08-24) with one-tap current-week calibration in Settings.
- **Standalone App & Streamlined Scope**: Renamed to Lǘlǘ Timetable with donkey icon and standalone package ID `app.lvlv.timetable` (installable alongside upstream); completely removed DNS ad-filtering, VPN service, domain rules, and AI/text-command editing (`lvlv://` / `tangli://`); simplified the bottom bar to Timetable / Settings, enabled Frosted Glass by default, and unified buttons and cards across all screens.

---

## SDJTU Features

Settings displays Shandong Jiaotong University and its seal, and all academic tools share one WebVPN session:

- **Timetable & Grades**: Background sync with offline storage, new-grade notifications, and revealable grade cards.
- **Empty Classrooms (`查空教室`)**: Required campus and week, optional building, room search, status filter, and all-slot availability filter.
- **Curriculum Plan (`培养方案`)**: Grouped by course category with semester filter, keyword/code search, credits, hours, and completion status.
- **Exam Schedule (`考试安排`)**: Merges current-term final and early final exams into one timeline and updates offline cache when both succeed.

---

## Features

### 📅 Timetable
- A whole week on one screen, opening on the current week; swipe to change weeks, or **press and drag the big week number in the top-left** — one cell, one week
- The class you are currently in "fills with time", showing how many minutes are left; 15 minutes before the next class, a countdown appears on its corner
- **Rain hint on class blocks** (on by default, can be turned off in Settings): when rain is expected during a class, its card on the timetable and the home-screen widget glows with a rainy-blue halo and drizzle streaks to remind you to take an umbrella; tap the class to see the rain probability. Weather is checked against Open-Meteo using a coarse location only (accurate to about 1 km)
- **"Today's classes" home-screen widget**: see today's remaining classes without opening the app, with live countdowns ("mm:ss left" for the current class, "starts in mm:ss" for the next); resizable down to 2×2, with your own background image. Long-press the Lǘlǘ Timetable icon and tap "Add today's classes widget" to place it
- **Pre-class reminders** (off by default): a silent notification 5–20 minutes ahead, with class name, room, and countdown
- **Next class pinned in the notification shade** (off by default): a silent notification with the next class's name, time, and room; during class it counts down to the end, and it flips automatically between classes
- Two classes at the same time are shown side by side, never overlapping
- **Long-press an empty cell and drag up or down** to draw a class directly; you can also add and edit classes manually
- 8–16 periods a day, period length, breaks, and 5- or 7-day display can all be adjusted to your school's schedule
- Same-name courses share a color; different courses in the same term never clash
- Holidays are greyed out automatically; make-up days automatically carry the classes of the day they replace; long-press a class to suspend just this week, or move it elsewhere
- You get a reminder if the timetable hasn't synced in a long time or the login has expired

### 🎓 Grades
- View grades and GPA by term
- A notification when new grades are posted (checked about every 6 hours, can be turned off)
- New grades stay "covered" until you tap to reveal them; you can read a one-line comment before revealing the score

### 👫 Classmates
- Generate a QR code or share code to send your timetable to a classmate (only course names, rooms, times, and schedule — no teachers or student IDs); if their daily schedule differs, they can adopt yours in one tap
- Scan a code or paste one to import a classmate's timetable
- Compute the **common free time** of you and one or more classmates in one tap — no more comparing timetables class by class to plan a meal or a study session

### 🎲 Little extras
- Settings → Little extras: how much each class is worth in tuition, a red edge on the timetable when absences approach 1/3, a two-person timetable poster, and a term persona card — all computed on your phone

### 🎨 Appearance & more
- The launcher uses the 驴驴课表 donkey icon
- Two themes: "Frosted Glass" (default) and "Paper & Ink", each in light / dark, expanding in a circle from where you tap when switching; dialogs under Frosted Glass are nearly opaque, so text never blends into the background
- Export your timetable to your phone's calendar, or back it up to a file to restore on a new phone

## Installation

1. Download the latest APK from [Releases](https://github.com/cao-gift/sdjtu-timetable/releases) and install it on Android 7.0+.
2. Open Settings → Sign in to Unified Portal (WebVPN) to sync your timetable, grades, classrooms, curriculum plan, and exam schedule.

The independently signed build uses `app.lvlv.timetable` and can be installed alongside the previous app. To transfer a timetable, export it from the old app and import it into the new one.

For a local installable debug build, run `./gradlew assembleDebug` with JDK 17, or `./gradlew testDebugUnitTest` to run unit tests.

## Feedback

Please open an [Issue](https://github.com/cao-gift/sdjtu-timetable/issues) in this repository. Settings → About can export a scrubbed diagnostic log. Include the page you were on, steps to reproduce, and what happened.

## Notes

- Lǘlǘ Timetable is an unofficial tool made by a student, and is not affiliated with Shandong Jiaotong University or any academic-system vendor
- Timetables, grades, classrooms, curriculum plans, and exam schedules from the university system are authoritative; GPA calculations are for reference only
- Your data stays on your phone. Optional portal auto-login encrypts saved credentials locally with Android Keystore and can be cleared anytime in Settings. If the rain hint is on and location is allowed, a coarse location (~1 km) is sent to Open-Meteo for weather (can be turned off in Settings).

## License

Derived from [Tangli (augustlies/tangli)](https://github.com/augustlies/tangli), © 2026 August Lies, and distributed under [GPL-3.0](LICENSE). Versions v1.2.3 and earlier were released under MIT. The Frosted Glass theme's design references the galaxy component on [Uiverse.io](https://uiverse.io) (MIT) — see [NOTICE](NOTICE) for details.

Want to contribute? See [CONTRIBUTING.md](CONTRIBUTING.md).
