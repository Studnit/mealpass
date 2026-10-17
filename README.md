# Hotel Meal Subscription & QR Token Management System

Spring Boot 3.3 · Java 17 · Thymeleaf · PostgreSQL 16 · Flyway · Bootstrap 5 · ZXing

Hotels sell meal-token subscriptions (e.g. Plan A: 30 tokens, 1 meal/day; Plan B: 60 tokens, 2 meals/day).
A customer scans the hotel's QR code, signs in, confirms the meal, and exactly one token is deducted.
Staff can alternatively verify a customer's short-lived pass QR. Four roles: Super Admin, Hotel Owner, Staff, Customer.
UI languages: English, Marathi, Hindi.

> **Status of verification:** the database migrations were applied to a real PostgreSQL 16 and the message bundles
> were checked by script. The Java code and templates were written without access to Maven Central, so
> **they have not been compiled and the test suite has not been run**. Run `mvn verify` first and expect to fix a few
> compile-level slips.

## Features
- **Subscriptions:** customer requests a plan, owner approves, a PENDING payment is created, tokens are credited only after
  payment is confirmed (cash/UPI by owner or permitted staff, or a signed gateway webhook). Free plans auto-activate.
  Suspend, resume, cancel, extend; hourly expiry job.
- **Redemption:** one transaction takes a `SELECT ... FOR UPDATE` row lock on the active subscription, then checks expiry, balance, daily limit,
  duplicate slot and idempotency key, and writes attendance + deduction + ledger entry + audit log + notifications.
  Owner reversal refunds the token through a REVERSAL ledger row (never an update of history).
- **Integrity in the database:** CHECK constraints (no negative tokens, used + remaining = total), partial unique indexes
  (one served attendance per subscription/date/slot, one active subscription per customer+hotel, one active QR per hotel, one reversal per reference),
  and triggers making `token_transactions` and `audit_logs` append-only.
- **Tenant isolation:** the hotel is derived server-side from the logged-in user, never from request parameters; other hotels' rows return "not found".
- **Daily menu:** owners add dishes per day (whole day, or tied to Lunch/Dinner; veg/non-veg; mark sold out; copy the previous day; up to 7 days ahead) under *Daily menu*.
  Customers see today's menu on the hotel page and right after scanning the QR. *Notify customers* sends one notification per active subscriber per day.
- **Notifications (in-app bell):** customer and owner are both notified when a subscription request is submitted and when a meal scan succeeds;
  customers also hear about approvals, payments, activation, low tokens, expiry and the daily menu. `ExternalNotificationSender` is the hook for SMS/WhatsApp/email.
- **Voice announcements** (browser text-to-speech, in English/Marathi/Hindi): after a successful meal scan the customer hears *"<name>, your meal for today is confirmed. Thank you for connecting with <hotel>."*
  and the hotel owner/staff hear *"<hotel> has received the meal request of <name> successfully."* (the same for subscription requests). Everyone can press *Stop audio* while it plays and switch voice alerts off with the speaker button in the top bar (remembered on that device). The same female voice is used on both customer and owner screens (the browser picks a female voice for the language when the device has one), and the speed (Slow / Normal / Fast) is chosen in the user menu.
  Browsers only allow speech after a tap on the page; a *Play audio* button is shown as a fallback. Marathi/Hindi voices must be installed on the device, otherwise the browser reads with an English voice.
- **Reports** (filter, search, sort, paginate, CSV export with spreadsheet-formula protection), dashboards with charts, notifications, audit logs.
- **Security:** BCrypt, CSRF on every form, session-fixation protection, login/redemption rate limiting, non-guessable QR identifiers (256-bit random),
  HMAC-signed 5-minute customer passes, signed payment webhooks, secure cookie flags.

## Requirements
Java 17, Maven 3.9+, PostgreSQL 14+ (16 recommended). Docker is optional (for Compose and for Testcontainers in tests).

## Run locally
1. Create the database:
   ```sql
   CREATE USER mealtoken WITH PASSWORD 'mealtoken';
   CREATE DATABASE mealtoken OWNER mealtoken;
   ```
2. Copy `.env.example` values into your environment (see the table below) and start:
   ```bash
   mvn spring-boot:run
   ```
   Flyway creates the schema on first start; Hibernate only validates it.
3. Open http://localhost:8080 and sign in as the bootstrap Super Admin (`ADMIN_EMAIL` / `ADMIN_PASSWORD`). Change that password.
4. Optional demo data (development only): `SEED_DEMO_DATA=true` creates a hotel, staff, customers and subscriptions.
   Every demo account uses the password `Demo#2026pass`; the addresses are printed in the startup log.

## Configuration
| Variable | Purpose | Default |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL | localhost / mealtoken |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | Bootstrap Super Admin | change in production |
| `PUBLIC_BASE_URL` | Public address (e.g. `https://meals.example.com`) put in hotel QR codes and reset links. Read from config only: change it and restart, nothing in the database changes. Empty = address of the current request | empty |
| `QR_SECRET` | HMAC key for customer passes, at least 16 chars. **Must be unique in production** | dev value (logs a warning) |
| `UPLOAD_DIR` | Hotel logos | `uploads` |
| `COOKIE_SECURE` | `true` behind HTTPS | false |
| `THYMELEAF_CACHE` | `true` in production | false |
| `APP_PAYMENT_WEBHOOK_SECRET`, `APP_PAYMENT_PROVIDER` | Enables `/webhooks/payments` (unset = endpoint returns 404) | unset |
| `SEED_DEMO_DATA` | Demo data | false |

## Online payment webhook
`POST /webhooks/payments` with JSON `{"paymentId":123,"status":"SUCCESS","amount":"100.00","reference":"txn-1"}` and header
`X-Signature: <hex HMAC-SHA256 of the raw body using APP_PAYMENT_WEBHOOK_SECRET>`. Bad signatures get 401; an amount that
differs from the payment gets 422; replays are harmless. `HmacPaymentGateway` is a generic verifier: implement `PaymentGateway`
for a specific provider (Razorpay, Stripe, ...) and add its SDK/redirect flow.

## Install on a phone (PWA) and camera scanning
The app is a Progressive Web App: open it in Chrome (Android) and tap **Install app**, or in Safari (iPhone) use Share > Add to Home Screen.
It then opens full-screen with its own icon.

**The live camera and "Install app" only work over HTTPS** (browsers refuse camera access on plain `http://192.168.x.x` or an IP address; `localhost` is the only exception).
If the camera does nothing, that is the cause. Fixes:
1. Deploy behind HTTPS: `DOMAIN=meals.example.com docker compose --profile https up -d` (Caddy gets a certificate automatically), and set
   `PUBLIC_BASE_URL=https://meals.example.com` and `COOKIE_SECURE=true` so hotel QR codes carry the https address.
2. For quick phone testing from your laptop, use a tunnel such as `cloudflared tunnel --url http://localhost:8080` or `ngrok http 8080` and use that https URL as `PUBLIC_BASE_URL`.
3. Even without HTTPS the **Take a photo of the QR** button works: it opens the phone's own camera app and reads the photo.
The QR contains only this address plus a random hotel code, so after changing the domain the QR shown in the app is correct immediately; QR codes already printed with the old domain must be reprinted.

**Meal suggestion:** after scanning, the meal matching the hotel's local time is pre-highlighted (10:00-17:00 Lunch, 18:00-24:00 Dinner).
It is only a convenience; the customer can pick any other meal. Default slot times for new hotels follow the same windows; owners can edit them.

## Tests
```bash
mvn verify                      # needs Docker: Testcontainers starts PostgreSQL 16
# or, without Docker, point at any empty-ish PostgreSQL database:
TEST_DB_URL=jdbc:postgresql://localhost:5432/mt_test TEST_DB_USERNAME=postgres TEST_DB_PASSWORD=secret mvn verify
```
Covered: subscription lifecycle and payment gating, expiry (with a controllable clock), redemption (success, exhaustion, duplicate slot,
Plan A vs Plan B daily limits, cross-hotel rejection, reversal + audit + idempotency), concurrent redemption (parallel threads racing for the last
token / the same meal), append-only triggers, role-based access, CSRF, receipt and attendance isolation, webhook signatures, i18n completeness,
pass-token and CSV-escaping unit tests. Integration tests create uniquely named data and do not need a clean database.

## Docker
```bash
docker compose up --build        # app on :8080, PostgreSQL volume, uploads volume
```
Set `QR_SECRET`, `ADMIN_PASSWORD`, `DB_PASSWORD`, `PUBLIC_BASE_URL` (and `COOKIE_SECURE=true` behind TLS) in a `.env` file first.
For production put the app behind a TLS-terminating reverse proxy and set `server.forward-headers-strategy=framework` if you need the original scheme/host.

## Operations notes
- Hotel QR: owner > QR page shows, downloads, prints and rotates it. Rotating revokes the old code immediately.
- All date logic (daily limit, expiry, "today") uses the **hotel's** time zone.
- Health: `/actuator/health`.
- Ledger corrections are new rows (REVERSAL / ADJUSTMENT); the database rejects UPDATE and DELETE on the ledger and audit log.

## Known limitations
- Owner "Hotel Settings" are merged into the Hotel Profile page; platform settings and audit views are intentionally simple.
- A customer can renew only when no ACTIVE subscription exists at that hotel.
- Payment gateway support is a generic signed-webhook verifier, not a provider SDK.
- Rate limiting is in-memory (per instance); use a shared store if you scale horizontally.

## Billing

* Each subscription has a fixed **total** (the plan price at approval). **Paid** is the sum of confirmed payments and **Remaining** is total minus paid.
* Customers see Paid / Remaining on every subscription card and on **Billing** (`/customer/billing`), with their payment history.
* Owners (and staff allowed to confirm payments) use **Billing** (`/owner/billing`): totals billed, collected, outstanding and collected today, plus a *Receive* form per subscription.
* A part payment records the amount received and keeps the balance as a new pending payment. The subscription activates when fully paid, or immediately if **Activate now** is ticked. Over-payment is rejected.
* Migration `V4__billing.sql` adds `subscriptions.total_amount` and backfills it from existing payments.

## Sign-up verification, hotel finder and the app QR

* **Verification code.** After registering, a 6 digit code is sent to the user's email (set `SPRING_MAIL_HOST` and friends) and to their mobile (set `OTP_SMS_WEBHOOK_URL`, any gateway that accepts `{"to","message"}` JSON). The user must enter it before signing in. Codes expire in 10 minutes, allow 5 wrong tries and can be re-sent once a minute. With no email or SMS configured, the code is written to the server log (development only). Set `OTP_REQUIRED=false` to turn the check off. Accounts created before this version, and hotels created by the Super Admin, count as verified.
* **City and area.** Hotel owners enter a city and an area when they register (editable under *My hotel*). Customers filter *Find hotels* by city, area and name, open a hotel and subscribe.
* **App QR.** `/app` is a public page with Install, Create account and Sign in buttons and one generic QR code (`/app/qr.png`, printable at `/app/poster`). Owners can print it from *QR code*; when it carries their city and area, new customers land on the hotel finder already filtered to that place. Migration `V5__otp_city_area.sql` adds the new columns and the `otp_codes` table.

### Railway

The Dockerfile has no `VOLUME` instruction because Railway fails validation on it. Uploaded logos live in `/app/uploads`; to keep them across deploys, add a Railway volume mounted at `/app/uploads`. Set `DB_URL` (e.g. `jdbc:postgresql://<host>:5432/<db>` from the Postgres service's *private* or *public* host), `DB_USERNAME`, `DB_PASSWORD`, `PUBLIC_BASE_URL`, `QR_SECRET` and `ADMIN_PASSWORD`.

## Pending balance and held tokens

If a customer has paid only part of the plan price, the unpaid amount shows as **Pending balance** on their Billing page and subscription card, and on the owner's Billing page. While money is still due, the customer's **last 5 tokens are held**: they can use tokens until 5 remain, and the rest unlock as soon as the balance is paid. The owner can change the number (0 turns it off) under *My hotel* ("Tokens held until fully paid"). Migration `V6__token_hold.sql` adds the setting.

### Email sender (Gmail)

Verification codes are emailed from `nkharose.dev@gmail.com`, configured under `spring.mail` in `src/main/resources/application.yml` (Gmail SMTP, port 587). Replace `PUT_APP_PASSWORD_HERE` with a Google **App Password** (Google Account, Security, 2-Step Verification, App passwords; 16 characters, no spaces), or set the `MAIL_APP_PASSWORD` environment variable (recommended on Railway, so the password is not stored in GitHub). The normal account password will not work. Until a password is set, sending fails quietly and the code is written to the server log.
