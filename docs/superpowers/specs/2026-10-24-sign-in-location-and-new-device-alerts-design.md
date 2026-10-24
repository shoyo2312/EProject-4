# Sign-in location + new-device alerts — design

Date: 2026-10-24 · Status: approved
Repos: `tiktok-backend` (auth-service, event-schema, notification-service), `tiktok-cloned` (web),
`tiktok-admin` (console). Not `tiktok_mobile`.

## 1. Why

Zalo and TikTok tell you when your account is signed in somewhere new, and say where: "Bạn đã
đăng nhập Zalo Web trên Mac OS (Chrome) … Tại: Long An, Việt Nam". Here, nobody is told anything.

auth-service already records every sign-in with the address and the device
(`auth_security_events`, written by `SecurityEventRecorder`), and the admin activity page already
lists them ("Chrome on macOS 27 · ::1"). Two things are missing:

- **The address is never turned into a place.** The activity page says so in its footnote:
  "Locations are not shown — the address is kept, but resolving one to a place needs a geo-IP
  database the platform does not have."
- **The account owner is never told.** An account signed into with a stolen password stays
  taken over until the owner notices something else.

## 2. Decisions

Decided by the user:

- **Both audiences.** The admin sees the approximate place on the activity timeline. The account
  owner gets an inbox notification modelled on TikTok's "Account updates" notice:
  > Your account logged in from a new device
  > Your account logged in from device Chrome on 10/19 13:16 +07 in Hồ Chí Minh. If this wasn't
  > you, secure your account immediately.
- **No login-history page for the owner** (Zalo's "Mở lịch sử đăng nhập").
- **Web only.** The mobile app is not changed (consequences: §6.4, §7.3).

Proposed in the conversation and not objected to:

- **Only a new device alerts.** Signing in again on a browser the account has used before is
  silent; another machine, or a private window, alerts. Clients keep a device id and send it
  when signing in.
- **The notification leads to changing the password** (§8.3 says how).
- **The place is resolved at sign-in and stored with the log entry**, so the owner and the admin
  see the same value and the history does not change when the lookup file is updated.
- **The lookup is an offline file inside auth-service.** No address ever leaves the server.
- **An address that cannot be resolved shows no place**, never a guess. Locally every address is
  `::1`, so nothing resolves there (§11 shows how to try it).
- **Failed sign-ins do not alert.**

Builder's calls (made in this draft, flagged so they can be checked):

- **Data: DB-IP "IP to City Lite"** (CC BY 4.0, monthly, MMDB ~121 MB, no account), not MaxMind
  GeoLite2 (needs an account and a license key, and its EULA requires old copies to be destroyed
  within 30 days of a new release). Both are read by MaxMind's own `maxmind-db` reader. City-level
  accuracy in Vietnam is poor with either: mobile networks often resolve to Hà Nội or TP.HCM
  wherever the phone is (§5.5).
- **English place names, "City, Country"** — "Ho Chi Minh City, Vietnam". The file has no
  Vietnamese names (it has en, fr, de, es, pt, zh, ja, ru, fa, ko), and the inbox is English.
  Country alone when the city is unknown.
- **Time in the message is Vietnam time, `MM/dd HH:mm +07`**, as in TikTok's sample.
- **The first device an account is ever seen on is remembered without an alert** (§6.3). This is
  what keeps sign-up, and every existing account's first device after this ships, quiet.
- **A browser that is already signed in becomes known without signing in again.** The web also
  sends its device id when it refreshes a session, and a refresh remembers the device silently,
  never alerting (§6.3). A session refreshes for months without a new sign-in, so otherwise most
  accounts would have no known device for weeks after the deploy — and the first sign-in in that
  window, an attacker's included, would be silent and remembered.
- **The web keeps its device id in a cookie its own server sets** (§8.1), not in localStorage.
  WebKit (Safari, and every browser on iOS) deletes script-written storage after seven days of
  use without a visit, so each return after a week would alert about the owner's own browser.
- **The alert names the device in fixed words only** (§7.1): "Chrome on macOS 27". It never
  includes the device model, and includes the OS version only when that is digits and dots. Both
  come from client hints, which the client writes freely; otherwise an attacker could put
  "routine security check, no action needed" into the owner's alert. The security log keeps the
  full label.
- **ADMIN accounts are left out of the new-device step**: no device rows, no alert. They sign in
  only in the console, which does not show notification-service's inbox. The web cannot sign them
  in either (no Turnstile, no OTP step), so an alert would land where nobody can read it. Their
  sign-ins still get a place in the log.
- **The alert is a `SYSTEM` notification, not a new type** (§7.3): the mobile app fails its whole
  inbox on a type it does not know.
- **The admin timeline marks the sign-in that raised an alert** with the detail "New device".
- **Every security-log entry gets a place**, failed sign-ins included: an admin looking at ten
  failed attempts wants to know where they came from.
- **Device ids are stored hashed, at most 50 per account, and forgotten 180 days after their last
  use.** 180 days is what the privacy and data-deletion pages promise for sign-in records (§6.2).
- **Tapping the notice expands it** — like every other system notice, so the time and place can
  be read — **and it carries a "Secure your account" link to Settings**, where "Change password"
  is (§8.3).
- **The GeoIP file reaches production through a one-shot container** that downloads it into a
  volume on deploy (§5.1). Coolify does not mount files from the repo checkout.
- **Attribution** "IP Geolocation by DB-IP" (required by CC BY) on the admin activity page and on
  the web privacy page.

Correction to the conversation: the alert was described as "pushed to the phone". The mobile app
has no push support at all (no Firebase in it, no token registration), so nothing reaches a phone.
notification-service still sends FCM to whatever tokens are registered, as it does for every
notification; on the web the alert arrives like any other notification (§8).

## 3. Non-goals

- Any change to `tiktok_mobile`: the header, the inbox, push, a password screen.
- A login-history page, a device list, or "log out of this device".
- Alerts for failed sign-ins, password changes, email changes, or a refresh. A refresh still
  writes no security-log entry (`TokenIssuer.reissue`, on purpose); it only remembers the device
  (§6.3).
- New-device alerts for ADMIN accounts (§2).
- Email alerts.
- A change-password endpoint for a signed-in user. There is none today; §8 uses what the web has.
- Vietnamese place names, or mapping to the provinces as merged in 2025.
- Refreshing the lookup file on a schedule. A deploy refreshes it once it is a month old (§5.1).
- Blocking or challenging a sign-in because of where it comes from.
- Using the place anywhere outside the security log and this alert.
- Any change to how the web and the gateway work out the client's address (§8.2).

## 4. Anti-goals — would make this a failure even if it works

- **A sign-in that fails, or waits, because of the lookup or the alert.** The lookup is an
  in-memory read; a missing or broken file means no place, not an error, and never a failed
  startup. The alert is one row in the sign-in's own transaction.
- **A user's address sent to a third party** (ip-api.com and the like).
- **An alert about a browser the owner keeps using**: on every sign-in from it, after a week away
  from it (§8.1), or after the deploy (§6.3). An alert that fires all the time teaches people to
  ignore it.
- **An alert right after signing up**, or **a burst of alerts on deploy**.
- **An alert lost after its device was remembered.** If the device is recorded as known but the
  alert never goes out, that device never alerts again. The device row and the outbox row commit
  together (§6.5).
- **Words in the alert chosen by whoever signs in.** The place is resolved only from the address
  the gateway established — `X-Client-Ip`, believed only beside the internal token, unchanged.
  The device is named from fixed lists (§7.1).
- **Every web alert naming the web server's city.** That is what happens if the gateway sees the
  Next server as the client (§8.2); the post-deploy check is there to catch it.
- **Sign-in records kept longer than the site says**: 180 days, on the privacy and data-deletion
  pages. Erasing an account removes them (§6.2).
- **The new event on `auth.user-events`.** `UserRegisteredEventConsumer` reads every message
  there as a registration and would send a "Welcome" note for each alert (see the comment in
  `OutboxPublisher.TOPICS`).
- **A new `NotificationType`.** The mobile app parses `type` with `$enumDecodeNullable` and no
  unknown value: an unrecognised type throws, and the whole inbox shows an error screen
  (`tiktok_mobile` `notification_model.dart:22-24`). Every installed app would break the first
  time its owner got an alert.

## 5. Place

### 5.1 The file

DB-IP City Lite, MMDB format. ~121 MB, so it is not in git and not in the shared Java
`Dockerfile` (every service's image would carry it).

- `scripts/geoip/download.sh <dir>` fetches
  `https://download.db-ip.com/free/dbip-city-lite-YYYY-MM.mmdb.gz` for the current month, falling
  back to the previous month when the current one is not published yet.
  - It downloads and unpacks to a temporary file, then renames it to
    `<dir>/dbip-city-lite.mmdb`. A failed download never leaves a half-written file where
    auth-service reads.
  - It does nothing when the file there is less than a month old.
  - A failed download prints a warning and exits 0. No file means no places, never a failed
    deploy.
- Dev: `make geoip` runs it into `data/geoip/` (git-ignored). `make run-auth` finds the file
  there without configuration (a default path or an env var — builder's choice).
- Prod: Coolify does not mount files from the repo checkout (see the comment above
  `cassandra-init` in `docker-compose.prod.yml`).
  - The script is baked into a one-shot `geoip-init` container, the way `cassandra-init` is
    built, and runs into a named volume.
  - auth-service mounts that volume read-only and gets the path in its environment.
  - auth-service starts after `geoip-init` completes (`service_completed_successfully`, as with
    `minio-init`).
  - Refreshing is redeploying: a deploy more than a month after the last download fetches the
    file again. The script renames over the old file while auth-service still has it mapped,
    which is safe; auth-service reads the new one when it restarts.

### 5.2 Reading it

- `com.maxmind.db:maxmind-db` (4.2.0 at the time of writing; pure Java, Java 17+, no runtime
  dependencies — confirm with `dependency:tree` that no Jackson 2 comes with it). Version in the
  root `pom.xml`'s `dependencyManagement`.
- One `Reader`, opened at startup, memory-mapped (the default), shared — it is thread-safe. The
  mapped file is page cache, not heap.
- Property `auth.geoip.database` (a path). Unset, missing or unreadable: the lookup is disabled,
  one WARN at startup says so, and auth-service runs as today. When the file opens, one INFO line
  gives its build date from the file's metadata, so a deploy can be checked from the log.

### 5.3 Resolving an address

`locate(String address)` returns the place or nothing, and never throws (lookup errors are logged
at debug and answered with nothing).

- **Parse the address as a literal only.** `InetAddress.getByName` on something that is not an
  IP literal does a DNS lookup, on the sign-in path. (`InetAddress.ofLiteral` is Java 22; this is
  Java 21.)
- **Not looked up:** loopback, site-local, link-local, any-local, and IPv6 unique-local
  (`fc00::/7`, which `isSiteLocalAddress` does not cover).
- Built from `city.names.en` and `country.names.en`: "Ho Chi Minh City, Vietnam"; country alone
  when there is no city; nothing when there is no country.

### 5.4 Storing it and showing it

- Flyway (next free number, `V18__…` today): `auth_security_events.location VARCHAR(128)`, null
  for every existing row ("not recorded").
- `SecurityEventRecorder` fills it for every entry that has an address, from the same address it
  records. The sign-in path computes the request's address, device and place once and both the
  log entry and the alert use that (§6.5).
- `SecurityEventResponse` gains `location`.

### 5.5 Accuracy

Country is reliable. City is not, in Vietnam especially: Viettel/VinaPhone/MobiFone 4G addresses
often resolve to Hà Nội or TP.HCM wherever the phone is, and fixed broadband is better but not
exact. Zalo does better because it has data of its own; a free file cannot. The admin console
shows the place with "≈" and says so in its footnote (§9). The owner's message states the city as
TikTok's does.

## 6. New device

### 6.1 The device id

A random id the client generates once and keeps (a UUID v4), sent as the request header
**`X-Device-Id`** on:

- every call that can end in a sign-in: `POST /api/v1/auth/login`, `/register/complete`,
  `/oauth/{google,facebook}` and `/oauth/{google,facebook}/link`;
- `POST /api/v1/auth/refresh`.

All of these end in `TokenIssuer` (`issue` for a sign-in, `reissue` for a refresh), so that is
where it is read. The admin OTP step (`/login/otp`) is ADMIN-only and skipped (§2).

It is **not a credential**. Knowing it only suppresses an alert; signing in still takes the
password (or the provider). It is also not the admin console's "remember this device" token,
which skips the OTP and is a credential — the two stay separate.

auth-service accepts 16–64 characters of `[A-Za-z0-9-]`. Anything else counts as absent.

### 6.2 Storage

Two infrastructure tables, like `remembered_devices`: no `BaseEntity`, no soft delete,
hard-deleted. Add both to the CLAUDE.md §6 list of hard-delete exceptions.

**`known_devices`** — the devices an account has used:

| column | |
|---|---|
| `id` | BIGINT, Snowflake |
| `user_id` | BIGINT NOT NULL, references `users` |
| `device_hash` | VARCHAR(64) NOT NULL — `HashUtils.sha256` of the id |
| `last_seen_at` | TIMESTAMPTZ NOT NULL |

`UNIQUE (user_id, device_hash)`.

- **Forgotten 180 days after last use.** `ExpiredRecordCleanup` deletes them on
  `auth.retention.security-event-grace`, the clock the security log already runs on. It is also
  the 180 days the privacy page (`privacy/page.tsx:110`) and the data-deletion page
  (`data-deletion/page.tsx:62-65`) promise for sign-in records. A device in use is touched at
  every sign-in and refresh, so only abandoned ones age out.
- **At most 50 per account**, by `last_seen_at`: inserting a new device deletes the account's
  oldest beyond 50, in the same transaction. Every private window that signs in adds a row.

**`known_device_accounts`** (name builder's) — one row per account that has ever had a known
device: `user_id` BIGINT PRIMARY KEY, references `users`.

- Inserted with the account's first remembered device, and never swept.
- It answers §6.3's "has this account had a known device before", rather than `known_devices`.
  The sweep can empty `known_devices` for an account nobody used for six months; an empty set
  would then read as a first device, and that account's takeover would be silent.

**Erasing an account deletes its rows in both**: `AccountClosureServiceImpl.eraseIfDue` removes
them beside the provider links it already removes. The `users` row stays (it is soft-deleted), so
the foreign keys are fine.

### 6.3 The rule

On each sign-in and refresh (ADMIN accounts skip all of it):

| call | device id | known for this account? | remembered? | alert? |
|---|---|---|---|---|
| sign-in | present | yes | `last_seen_at` updated | no |
| sign-in | present | no | yes | **only if the account had a known device before** |
| sign-in | absent | — | no | **only if the account has had a known device** |
| refresh | present | either | yes, or `last_seen_at` updated | never |
| refresh | absent | — | no | never |

"Had a known device" means the account has a row in `known_device_accounts`.

What follows from it:

- Sign-up by any path is silent: the account has no devices yet.
- Each existing account's first device after this ships is silent and remembered. A web session
  that is already signed in refreshes within about 15 minutes of use (the access token's
  lifetime). The browsers people are using therefore become known soon after the deploy, before
  anyone signs in anew.
- An account whose owner only ever used clients that send no id never alerts. Accepted: every web
  client sends one (§8).
- Whoever holds a stolen refresh token can make their device known by refreshing. Accepted: they
  already hold the session, and a stolen session is not what this alert is for (§3).

The database decides both "known or not" and "first device or not": the unique keys and the row
counts of `INSERT … ON CONFLICT DO NOTHING`, not a read followed by a write.

- Two simultaneous sign-ins from the same new browser (a double submit) alert once.
- Two different new devices in the very first instant of an account can both pass as first
  device. Accepted.

### 6.4 Clients that send no id

The mobile app (out of scope) and hand-made calls (Postman, curl) send none. On an account that
has had a known web device, every sign-in from them alerts. That is true — nothing says it is a
device seen before — but noisier than it will be once the mobile app sends the header, which is
the fix when the app is next worked on.

### 6.5 Transaction

Remembering the device and writing the alert's outbox row both happen **inside the transaction
that writes the refresh-token row** (`TokenIssuer.mint`). A sign-in that rolls back therefore
leaves no alert, and an alert cannot be lost after its device was remembered.

The security-log entry stays where it is today — its own `REQUIRES_NEW` transaction after the
tokens commit — and carries the detail "New device" when the sign-in raised an alert. A refresh
writes no entry, as today.

The request's address, device label, place and device id are read once per sign-in and handed to
both. How that is factored (a small request-context value, a recorder method that takes it) is
left to the builder; the recorder's other callers keep working unchanged.

## 7. The alert

### 7.1 Event

`NewDeviceSignInEvent(eventId, occurredAt, userId, deviceLabel, location)` in event-schema,
package `com.tiktok.event.user`. `occurredAt` is the sign-in; `deviceLabel` and `location` may be
null. Pinned in `EventJsonCompatibilityTest` (`-Devent-json.record=true`, review the new entry).

`deviceLabel` is not the security log's label:

- **The log's label takes client hints as sent.** `DeviceLabel.of` appends `Sec-CH-UA-Model` and
  keeps `Sec-CH-UA-Platform-Version`, up to 128 characters, and the client sets both freely.
- **The alert's label takes only fixed words.** It is the browser and the platform from
  `DeviceLabel`'s fixed lists. The version is included only when it is digits and dots (Windows
  maps to 10/11 as today), and the model never is: "Chrome on macOS 27", "Safari on iOS".

### 7.2 Topic

**`auth.new-device-events`**, through auth-service's outbox: a new aggregate type in
`UserEventProducer` and its topic in `OutboxPublisher.TOPICS`. Key: `userId`. Its own topic, so no
consumer needs `eventType` routing (§4). No new `OutboxBacklog` bean: the auth outbox is one poll
over all aggregate types.

### 7.3 notification-service

`NewDeviceSignInEventConsumer` on `auth.new-device-events`, group `notification-service`, claims
the `eventId` with `IdempotentEventProcessor` and calls

```
notificationService.create(userId, null, NotificationType.SYSTEM, title, body, "account-security")
```

- **`SYSTEM`, not a new type**: §4, last point. The mobile app shows it like the other system
  notices (title, body, ★) and does nothing on tap — acceptable, it is out of scope.
- **`referenceId = "account-security"`** is how the web tells this notice from the others (§8).
  `referenceId` is opaque to the service by design ("the client decides what it means"); every
  other `SYSTEM` notice carries null or a video id, so a non-numeric value cannot collide.
- `SYSTEM` does not collapse repeats (`collapsesRepeats()`), which is right: two alerts are two
  sign-ins.

### 7.4 Wording

- Title: `Your account logged in from a new device`
- Body: `Your account logged in from device {deviceLabel} on {MM/dd HH:mm} +07 in {place}. If this
  wasn't you, secure your account immediately.`
  - No device label: `… from a new device on …`
  - No place: the ` in {place}` part is left out.
  - Time: `occurredAt` in `Asia/Ho_Chi_Minh`.

Example: "Your account logged in from device Chrome on macOS 27 on 10/23 21:42 +07 in Ho Chi
Minh City, Vietnam. If this wasn't you, secure your account immediately."

### 7.5 Delivery

Nothing new: `NotificationService.create` already stores the entry, publishes
`notification.created` (chat-service pushes it down the web's socket) and sends FCM to the
account's registered tokens.

## 8. Web (`tiktok-cloned`)

### 8.1 Device id

- **An HttpOnly cookie set by the web's own server, not localStorage.**
  - WebKit's tracking prevention deletes script-written storage, localStorage included, after
    seven days of browser use without a visit. That covers Safari, and every browser on iOS.
  - The refresh token also lasts seven days. A Safari user back after a week would have lost
    both, and their sign-in would alert about their own browser.
  - A cookie set in an HTTP response from the site's own server is not under that limit.
- **`src/proxy.ts`** (Next 16's middleware; the web has none today), matcher
  `/api/v1/auth/:path*`:
  - It reads the cookie (name builder's). When the cookie is absent or malformed, it generates a
    UUID (`crypto.randomUUID()`) and sets the cookie on the response: HttpOnly, SameSite=Lax,
    `Path=/api/v1/auth`, one year, Secure in production.
  - It adds `X-Device-Id` with the cookie's value to the request it passes on, overwriting any
    the browser sent.
- **The cookie goes only to `/api/v1/auth/*`.** That means sign-in, sign-up, social sign-in,
  refresh and logout, never the feed or anything else a guest browses.
- **Nothing in `src/lib/api/` changes.** The sign-in calls and the refresh (`client.ts`
  `performRefresh`, a plain same-origin `fetch`) already go to `/api/v1/auth/*`.
- **Logout, password reset and closing the account leave the cookie.** The quiet re-sign-in
  after a password change (`PasswordModal.tsx:110`) therefore raises no alert.
- **The builder verifies the mechanism before relying on it**, using `next start` and curl to a
  local echo server. Two things must make it through the external rewrite
  (`next.config.ts:118-125`): the added request header reaching the gateway, and the
  `Set-Cookie` reaching the browser. If either does not, `proxy.ts` rewrites these paths itself
  (`NextResponse.rewrite` with the modified request headers) instead of leaving them to
  `next.config.ts`.

### 8.2 The browser's address — no change, two deployment requirements

How the address reaches auth-service today:

1. The browser calls `/api/*` on the web's own origin.
2. Next proxies the call to `API_GATEWAY_URL` with the incoming headers. It sets
   `X-Forwarded-For` to the address it received the request from **only when the header is
   absent** (`??=`, Next 16.4 `base-server.js`).
3. The gateway walks `X-Forwarded-For` from the right, past trusted proxies
   (`docs/api-gateway.md` §5), and hands the result to auth-service as `X-Client-Ip`.

So the address is the browser's only when a proxy in front of Next writes it, as Cloudflare does,
and every hop between that proxy and the gateway is trusted.

- `docs/api-gateway.md` §5 already requires this, and warns that a Next server exposed directly
  passes a browser-made header through.
- This design does not change it, and `proxy.ts` does not strip the header. The value reaching
  `proxy.ts` may be Next's own, and dropping it would put every web visitor on the Next server's
  address: one rate-limit bucket for the whole site.

**Assumed: the web runs self-hosted, behind the same Cloudflare Tunnel as the API.** At the time
of writing both `nowaa.site` and `api.nowaa.site` answer with Cloudflare's tunnel error 1033. On
Vercel, the gateway would see Vercel's changing egress addresses as the client, with no fixed
address to trust. That would need a new way for the gateway to trust the web server, which is not
scoped here.

For production (`docs/operations.md`):

- **The web calls the gateway over Coolify's internal network**, not through
  `https://api.nowaa.site`. `API_GATEWAY_URL` is a build argument of the web's image.
  - Through the public domain, the first untrusted hop the gateway finds is the server's own
    public address. Every web user would share one rate-limit bucket, and every alert would name
    the data centre's city.
  - If the public domain has to be used, that address goes into `RATE_LIMIT_TRUSTED_PROXIES`.
- **Coolify's proxy (Traefik) must keep the `X-Forwarded-For` that Cloudflare wrote.** Traefik
  drops that header from a peer outside its `forwardedHeaders.trustedIPs` and writes the peer's
  address instead. The peer here is the tunnel connector, so it must be trusted.
- **Check after deploying:** sign in on the web from a phone on 4G. The admin timeline must show
  the carrier's address and a place, not a private address or the server's.

### 8.3 The notice in the inbox

- `SYSTEM` rows already show the title as the heading and the body under it, and expand on click
  to the full body (`ActivityDrawer.tsx:410, 413, 500-508`) — that is where the time, device and
  place are read.
- New: an expanded `SYSTEM` notice whose `referenceId` is `"account-security"` shows a **"Secure
  your account" link to `/setting`**.
  - `/setting` opens on Manage account with "Change password" on screen
    (`site-config.ts:180-200`).
  - For a social-only account it reads "Set password". With no email it is disabled with "Add an
    email address to your account first" (`PasswordModal.tsx:42-61`).
  - Completing it logs out every other device (`PasswordModal.tsx:128`). That is what securing
    the account needs, since there is no "log out everywhere".
- The `"account-security"` constant sits beside the notification types in
  `src/lib/api/notifications.ts`, with the backend constant named in a comment.
- Already safe, no change: `openNotification` returns for `SYSTEM` before it would navigate to a
  video (`ActivityDrawer.tsx:147`), and `unresolvedVideoIds` skips `SYSTEM`
  (`use-notifications.ts:241-242`), so a non-null `referenceId` on `SYSTEM` causes no wrong route
  and no video fetch. A test pins both.
- Unchanged: the filter chips (`SYSTEM` appears under "All activity" only) and the desktop
  notification (it shows title and body for every frame; its click focuses the tab).
- Builder's call: the link does not open the password modal by itself. That needs a deep-link
  parameter and a Suspense boundary for `useSearchParams` (see `search/page.tsx:12-24`), for one
  click saved.

### 8.4 Privacy page

`src/app/privacy/page.tsx` gains two sentences:

- sign-in locations are approximate and come from the address, with "IP Geolocation by DB-IP"
  linked;
- the browser keeps a sign-in device cookie, so that an alert goes out only for a new device.

## 9. Admin console (`tiktok-admin`)

- **Place on each security row.** `lib/api/types.ts` `SecurityEventResponse` gains
  `location: string | null`; `lib/activity.ts` `securityEvent()` appends it to the detail line as
  `≈ {location}`: "Chrome on macOS 27 · 14.161.12.34 · ≈ Ho Chi Minh City, Vietnam". The
  "New device" detail (§6.5) already shows through `event.detail`.
- **Footnote** on `app/(admin)/users/[id]/activity/page.tsx`: replace "Locations are not shown …"
  with: places are approximate, looked up from the address when the event happened; mobile
  networks often resolve to Hà Nội or TP.HCM wherever the phone is; entries from before this
  change have none. Plus the attribution link
  `<a href="https://db-ip.com">IP Geolocation by DB-IP</a>`.
- Mock data (`lib/mock/security-events.ts`) gains a few locations.
- No device id: the new-device step skips ADMIN accounts (§2), so the console's own sign-ins do
  not need one.

## 10. Tests — one runnable check per non-trivial piece

- **auth-service**
  - Place lookup: an IPv4 and an IPv6 public address resolve to "City, Country"; loopback,
    private, unique-local, a hostname and garbage give nothing and do no DNS lookup; no file means
    disabled, not an exception. Needs a small MMDB fixture (MaxMind publishes test databases;
    check the license before adding one to `src/test/resources`).
  - The §6.3 table, against Postgres (Testcontainers is already set up in auth-service):
    - first device silent; same device silent;
    - second device alerts, with one outbox row on `auth.new-device-events`;
    - absent id alerts only once a device has been known;
    - a refresh with a new id remembers it and writes no outbox row;
    - a double submit from one new device writes one outbox row;
    - the 51st device evicts the oldest;
    - an ADMIN sign-in writes no device row and no outbox row.
  - The sweep removes devices unused past the grace and keeps the marker; the next new device
    still alerts. Erasing an account removes both tables' rows.
  - A sign-in that fails after the device step (a rollback) leaves neither the device nor the
    outbox row.
  - The alert's label: a model, and a version that is not digits and dots, never reach the event.
  - `location` reaches the log row and `SecurityEventResponse`.
- **event-schema**: the pinned JSON for `NewDeviceSignInEvent`.
- **notification-service**: the consumer writes one `SYSTEM` entry with the §7.4 wording, in its
  three shapes (full, no label, no place); a redelivered event writes nothing.
- **Web** (Vitest, `npm run check` clean):
  - `proxy.ts`:
    - a request without the cookie gets one (HttpOnly, `Path=/api/v1/auth`) and passes
      `X-Device-Id` with its value on;
    - a request with the cookie passes the same value on and sets nothing;
    - a browser-sent `X-Device-Id` is overwritten;
    - a path outside `/api/v1/auth` is untouched.
  - An `"account-security"` notice expands and shows the link to `/setting`, another `SYSTEM`
    notice shows none, and neither fetches a video or navigates.
- **Admin console**: the location in the detail line.

## 11. Trying it locally

Every local request comes from `::1`, which resolves to nothing. The gateway believes
`X-Forwarded-For` from a loopback peer, so a sign-in sent straight to it can choose its address:

```
curl -X POST localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -H 'X-Forwarded-For: <a public Vietnamese address>' \
  -H 'X-Device-Id: test-device-aaaaaaaa' \
  -d '{"usernameOrEmail":"…","password":"…"}'
```

Sign in with one device id (silent, remembered), then another (alert in the web inbox, with the
place), then the first again (silent). The admin activity page shows the places.

## 12. Docs

- `docs/auth-service-api.md`: `X-Device-Id` on the sign-in endpoints and `/refresh`; `location`
  on the admin security-event list.
- `docs/operations.md`:
  - the GeoIP file: `geoip-init`, refreshed by a deploy once it is a month old, the startup log
    line to check, the attribution;
  - the §8.2 deployment requirements and the post-deploy check.
- `CLAUDE.md`:
  - `auth.new-device-events` and its consumer;
  - `known_devices` and `known_device_accounts` in the hard-delete exceptions;
  - one line on the GeoIP file, where the place is resolved, and why the alert's device label is
    not the log's.
- Postman (`postman/`): `X-Device-Id: {{deviceId}}` on the sign-in requests and `/refresh`;
  `deviceId` in the environment.
- Web: the device cookie in `docs/TAI_LIEU_DU_AN.md`, beside the `/api/*` proxy it describes.

## 13. Left to the builder

- Class names and how the per-request sign-in context is factored (§6.5).
- How dev finds the file (§5.1), the exact compose wiring, and the download script's age check.
- The SQL for the 50-device bound, and the names of the marker table and of the web's cookie.
- Whether `proxy.ts` leaves the proxying to the rewrite or rewrites these paths itself (§8.1).
