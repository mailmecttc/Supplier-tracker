# Supplier Dispatch Tracker

Mobile-first PWA to track daily supplier dispatch against weekly plans. Data stays on the device (IndexedDB); back up to a JSON file.

- `npm install`, `npm run dev` for local development
- `npm test` runs the calculation tests (spec section 8 acceptance cases)
- `npm run build` writes a static site to `dist/` (works from any sub-path, e.g. GitHub Pages)

Code map: `src/calc.ts` (all calculations, pure), `src/store.ts` (IndexedDB + backup validation),
`src/exports.ts` (CSV/JSON), `src/screens/*` (Entry, Status, Plan, Issues, All vendors, Setup, Weekly review).

No company data is in the code. Vendors, parts and plans are entered in the app.
