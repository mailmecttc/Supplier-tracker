# Supplier Dispatch Tracker

Two versions live here: a native **Android app** (`android/`) and the original web app (PWA, repo root).

Mobile-first PWA to track daily supplier dispatch against weekly plans. Data stays on the device (IndexedDB); back up to a JSON file.

- `npm install`, `npm run dev` for local development
- `npm test` runs the calculation tests (spec section 8 acceptance cases)
- `npm run build` writes a static site to `dist/` (works from any sub-path, e.g. GitHub Pages)

Code map: `src/calc.ts` (all calculations, pure), `src/store.ts` (IndexedDB + backup validation),
`src/exports.ts` (CSV/JSON), `src/screens/*` (Entry, Status, Plan, Issues, All vendors, Setup, Weekly review).

No company data is in the code. Vendors, parts and plans are entered in the app.
Each vendor has contact, address (with an Open in Maps link) and notes fields in Setup.
To load many vendors at once, prepare a backup-format JSON file and use Setup → Restore from file; keep that file out of this repo.

## Android app (`android/`)

Native Kotlin + Jetpack Compose app. Data is one JSON file in the app's private storage; nothing is sent anywhere.
Screens: Entry (pick a vendor, type each part's dispatch; the box turns green / amber / red against today's plan),
Status (daily / weekly / monthly adherence and critical parts; Parts details worst first with a Good / Bad / Critical
filter and Day / Week / Month bars per part; tap a part for its page: a calendar of the month with dispatch over plan
each day, weekly and monthly views; dated notes; past months; vendor details with Open in Maps), Dashboard (the start screen and the
round centre button: headline figures, 6-week trend chart, vendors, gap causes, report, WhatsApp status), Plan (month
plan per part split into weeks, or week by week, with re-plan tracking; opening backlog), Setup (help, settings,
vendors, parts, supplier days off, holidays, report template).

- Report: Dashboard → pick a period (this week, month to date, last month or a custom range) → **Build report
  (PowerPoint)** builds the review on the phone: summary with RAG-coloured rows and parts short, time gone vs
  coverage, week-on-week heat map, parts short / parts on schedule for all vendors (grouped by vendor), backlog,
  gap causes (Pareto), reasons and actions. It uses the company template imported in Setup, else a company template
  bundled at build time from `android/private/assets/company-template.pptx` (git-ignored, never committed), else the
  neutral `app/src/main/assets/report-template.pptx` (`tools/make-report-template.cjs`). From any .pptx the title /
  "Slide with Title" / final layouts, theme, logo and footer are kept; its own sample and reference slides are dropped.
  Code: `model/Report.kt` (period figures, trend, causes), `report/ReviewDeck.kt` (slides), `report/Pptx.kt` (package).
- Reasons: Status → part → **+ Reason** (gap cause, action, owner, due date), shown in the report.
- Settings (Setup → Open settings): theme (phone / light / dark), app or wallpaper colours, text size; fingerprint
  lock (fingerprint or phone PIN; locks again after a minute in the background); night backup at about 2:30 AM into
  a folder the user picks (WorkManager, last 14 kept); backup password: backups locked with AES-256-GCM, key from
  PBKDF2-HMAC-SHA256 (600,000 rounds, random salt and IV per file); share / save / restore. Code: `data/Backup.kt`.
- Supplier days off: each vendor has its own holidays on top of the holidays for all vendors.
- Month plan (Plan → Whole month): one total per part, split into the month's weeks by dispatch days (holidays and
  days off left out) in whole pieces with running-total rounding, stored as the usual weekly plans. A change goes to
  the weeks to come; weeks already under way keep their plan. Code: `splitMonthPlan` / `levelShares` in `Calc.kt`.
- Vendor Excel import (Share → Supplier Tracker, Entry → Import file, or Setup): reads a supplier's own .xlsx / .csv
  report on the phone (`importer/Xlsx.kt`, no library) and finds the vendor from its part numbers and the layout by
  itself – an invoice list, a column per date (Plan / Dispatch rows) or a column per week (Plan / Actual) – for
  dispatch and plan (`importer/VendorFile.kt`). Every figure is checked against the app before saving; till-date
  vendors get running totals; Undo until anything else changes; each vendor's format is remembered (`Data.importFormats`).
- Notes: dated notes per vendor or part (`Data.notes`, added in 1.5; older files simply have none), editable; reasons
  show the day they were added. Code: `model/PartHistory.kt` (calendar, weeks, months, adherence, Entry box check),
  `ui/screens/StatusScreen.kt`, `PartScreen.kt`, `Notes.kt`.
- Crash report: an uncaught error is written to the app's private storage (`data/CrashLog.kt`, error and phone model
  only); the next launch offers **Send crash report**.
- Help & support (top of Setup): the user guide PDF read inside the app (`PdfRenderer`), shared or opened in a
  PDF app; **Report a problem** shares app version, phone and data counts only (no supplier names or quantities);
  quick fixes. The guide `app/src/main/assets/user-guide.pdf` is built by `tools/make-user-guide.py` from
  screenshots and slides of made-up data (`tools/guide-sample-data.json`, `tools/make-guide-sample-data.py`);
  the script's header lists the steps.
- The APK ships with no vendor data. After installing, Setup → **Import data file** loads vendors, parts,
  plans and past months from a backup-format JSON file (the web app's backups import too).
- Calculations in `app/src/main/java/app/suppliertracker/model/Calc.kt` mirror `src/calc.ts`;
  `CalcTest.kt` runs the same spec section 8 cases.
- Build: open `android/` in Android Studio, or `./gradlew testDebugUnitTest assembleDebug` with `ANDROID_HOME` set.
  Release signing reads a properties file kept outside the repo: `-Psigning.props=/path/keystore.properties`.
