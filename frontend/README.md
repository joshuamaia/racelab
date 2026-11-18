# RaceLab — frontend

Angular 21 (standalone, signals, zoneless) + PrimeNG 21 + Chart.js + Monaco. Talks only to same-origin `/api/**`
(Nginx → NestJS BFF). Contract: `../docs/API-CONTRACT.md`.

| Command | What it does |
|---|---|
| `npm start` | dev server on http://localhost:4200, `/api` proxied to the BFF on http://localhost:3000 (`proxy.conf.json`) |
| `npm run build` | production build → `dist/frontend/browser` |
| `npm test` | unit tests (Vitest via `ng test`), single run |
| `npm run e2e` | Playwright e2e against the full docker stack on http://localhost:4200 (`npx playwright install chromium` once) |
| `npm run e2e:typecheck` | type-check the e2e suite without running it |

Docker: `docker build -t racelab-frontend .` (context `./frontend`), Nginx config in `nginx/default.conf`
(mirrored at `../infrastructure/nginx/nginx.conf`). Container listens on port 80.

Layout: `src/app/core` (models, API client, interceptors, SSE, stores), `src/app/shared` (timeline, lock graph, Monaco,
tags…), `src/app/features/<page>` (lazy routes).
