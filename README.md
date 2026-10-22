# Smart Scheduler

Shift-scheduling app for teams. Pilot: an ER doctors team in Portugal.

| File | What it is |
|---|---|
| [EARLY_PRODUCT_DISCOVERY.md](EARLY_PRODUCT_DISCOVERY.md) | Market research, competitors, MUST / SHOULD / COULD features |
| [MVP_BREAKDOWN.md](MVP_BREAKDOWN.md) | Decisions log, pilot rules, epics E1–E13, release plan, deferred features |
| [DATA_MODEL.md](DATA_MODEL.md) | MVP data model v2: 10 tables, what's computed at runtime, Auth0 login |
| [UI_MOCKS.md](UI_MOCKS.md) | Tailwind v4 tokens, component class recipes, React component list |
| `diagrams/data-model-diagram.html` | Entity-relationship diagram (open in a browser) |
| `ui-mocks/` | Source of the interactive mocks (`.dc.html` artboards + `canvas.json`) |

Online versions (private until shared):
- Interactive mocks: https://claude.ai/artifact/84XeQxyAobznCFrLN3Y4un
- Data model diagram: https://claude.ai/artifact/4z5pTWS8bvrVR6opjRzgJg

The `.dc.html` files need the design canvas to run (they load its `support.js`), so open them through the link above. Their inline styles map one-to-one to the Tailwind classes in `UI_MOCKS.md`.

## Run locally

Requirements: Docker, Java 21 (see `.tool-versions`).

```bash
docker compose up -d          # Postgres 17 on localhost:5432 (db/user/pass: scheduler)
cd backend && set -a && . ./.env && set +a && ./gradlew run   # API on http://localhost:8080 (Flyway runs on start)
```

- Copy the client secret from the Auth0 dashboard (Applications → Smart Scheduler → Settings) into `backend/.env`.
- Seed the invite: `docker compose exec -T db psql -U scheduler -d scheduler < backend/dev-seed.sql`.
- Login needs a verified email (Google login, or verify the email of a database-connection user), because the backend only claims invites for verified emails.
- Frontend: `cd frontend && npm run dev`, then open http://localhost:5173.

Backend env vars (defaults in `backend/src/main/resources/application.conf`):

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/scheduler` | JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `scheduler` / `scheduler` | Database login |
| `AUTH0_DOMAIN` | `example.eu.auth0.com` | Auth0 tenant domain (issuer and JWKS) |
| `AUTH0_CLIENT_ID` | `dev-client-id` | Auth0 application client ID (ID token audience) |
| `AUTH0_CLIENT_SECRET` | `dev-client-secret` | Auth0 application client secret |
| `PUBLIC_URL` | `http://localhost:5173` | Frontend origin; OAuth callback is `$PUBLIC_URL/auth/callback`. Vite proxies `/auth` and `/api` to :8080 |
| `SESSION_ENCRYPT_KEY` | **required** | Hex AES key for the session cookie (16, 24 or 32 bytes) |
| `SESSION_SIGN_KEY` | **required** | Hex key that signs the session cookie |

Generate session keys with `openssl rand -hex 16` (encrypt) and `openssl rand -hex 32` (sign). Startup fails if they are unset.

Frontend: see `frontend/README`.

Tests: `cd backend && ./gradlew test` (needs Docker for Testcontainers).
