# Deactivate or delete account — design

Date: 2026-10-17 · Branch: feat/moderation-tools-and-boot-4.1 · Status: implemented; §2 accepted as
proposed. Where the build departed from the draft, the text below says what was built.

## 1. Problem

The web client's `/setting` page lists "Deactivate or delete account" under Account, and it is
inert: the row has no action, because no service can close an account. `docs/user-settings.md` §7
already names `deactivate`/`delete` as endpoints of a later tier. This is that tier.

What exists to build on:

- **Bans** already make an account disappear in most places. auth-service flips `status` and
  revokes every session (`SessionRevoker`). video-service (`owner_bans`), search-service
  (`owner_bans` index) and interaction-service (`UserBanRegistry`) each keep their own record and
  filter at read time. Deactivation needs the same reach, but not the same record (§5).
- **user-service does not know about bans.** A banned account's profile still opens and still
  turns up in account search. Deactivation has to close that gap for itself (§6.1).
- **There is no "prove it is you" step** for a signed-in account. A password is changed through
  the forgot-password OTP flow, and social-only accounts may have no password and no email.

## 2. Decisions (proposed, matching TikTok)

To confirm before implementation:

- **Deactivate** hides the account from everyone: profile, videos, comments, search, follow lists.
  It lasts until the owner signs in again, which reactivates it after one confirmation. Nothing is
  deleted.
- **Delete** deactivates at once and schedules deletion **30 days** later. Signing in within those
  30 days offers to reactivate, which cancels the deletion. After 30 days it cannot be undone.
- **Both require reconfirming identity**: the password if the account has one, otherwise a code
  mailed to its verified address, otherwise a fresh Google/Facebook sign-in (§4.2).
- **Other accounts' counters do not move while an account is deactivated.** B's follower count
  still includes a deactivated A, as on TikTok. They are corrected when A is deleted.
- **Admin accounts cannot close themselves** through this, for the same reason they cannot be
  banned: the console must not lose its last admin.
- **Deletion means hidden for good plus credentials freed**, not a physical purge of every row in
  every service. See §7 and the `ponytail` notes there.

Out of scope: direct messages (§6.6), data export, a reason survey before deleting (the UI may
collect one; nothing stores it).

## 3. States

auth-service `UserStatus` gains `DEACTIVATED`.

| From | Action | To | `deletion_scheduled_at` |
|---|---|---|---|
| ACTIVE | deactivate | DEACTIVATED | null |
| ACTIVE | delete | DEACTIVATED | now + 30 days |
| DEACTIVATED | reactivate (sign-in) | ACTIVE | cleared |
| DEACTIVATED, deletion due | sweep (§7) | deleted (`deleted_at` set) | — |

**Interaction with bans.** A banned account cannot sign in, so it cannot deactivate. An admin can
ban a deactivated account: `ban()` sets BANNED and leaves `deactivated_at` and
`deletion_scheduled_at` alone. `unban()` today always sets ACTIVE; it becomes "DEACTIVATED if
`deactivated_at` is set, else ACTIVE", so lifting a ban never reopens an account its owner closed.
A deletion that falls due while banned still runs: the owner asked for it.

**`V17__account_deactivation.sql`** (auth-service): `deactivated_at TIMESTAMPTZ`,
`deletion_scheduled_at TIMESTAMPTZ`, and a partial index on `deletion_scheduled_at WHERE
deleted_at IS NULL AND deletion_scheduled_at IS NOT NULL` for the sweep.

## 4. auth-service API

### 4.1 Closing

| Endpoint | Body | Answer |
|---|---|---|
| `POST /auth/account/closure-code` | — | `200`. Mails a code (§4.2); only for an account with a verified address |
| `POST /auth/account/deactivate` | `Reconfirmation` | `200` |
| `POST /auth/account/delete` | `Reconfirmation` | `200 { deletionScheduledAt }` |

Both closing calls, in one transaction: check the role (`403 ACCOUNT_CLOSURE_NOT_ALLOWED` for
ADMIN), check the reconfirmation, set the columns in §3, and queue `AccountStateChangedEvent`
(§5). After commit they call `SessionRevoker.revokeAllSessions`, which ends every refresh token and
blacklists access tokens at the gateway, security-lib and auth-service, as a ban does. The caller's
own session ends with the rest; the client signs out locally (§8).

### 4.2 Reconfirmation

```json
{ "password": "…" }                       // account with a password
{ "otp": "123456" }                       // after /closure-code
{ "provider": "google", "token": "…" }    // social-only account with no address
```

Exactly one of the three, else `400 VALIDATION_ERROR`. Failures:

- **password**: through `LoginRateLimiter` keyed on the account, like `/login`. Wrong is
  `403 INVALID_CREDENTIALS`, not 401: the caller is signed in, and the web client reads a 401 on
  an authenticated call as an expired session, refreshes and sends it again, spending a second
  guess. The limit is `429 TOO_MANY_LOGIN_ATTEMPTS`.
- **otp**: `OtpService` with a new `VerificationTokenType.ACCOUNT_CLOSURE`. It gets its own type for
  the reason SOCIAL_LINK and ADMIN_LOGIN do: it is mailed to an already-verified address to
  authorise a different action, and must never be spendable as another kind of code. Failures are
  `INVALID_OTP` / `TOO_MANY_OTP_REQUESTS`, as elsewhere.
- **provider token**: the same verifier `/auth/oauth/{provider}` uses, and the identity it names
  must be linked to the caller and not waiting on a reconfirmation. Otherwise
  `403 INVALID_SOCIAL_TOKEN`, for the reason the password is 403.

The UI picks the method from `/auth/me`, whose `UserResponse` already carries `hasPassword` and
`emailVerified`. It gains `providers` (the linked `AuthProvider`s), so a social-only account
without an address knows which button to show. The gateway's `/me` aggregate passes
`emailVerified` and `providers` through.

### 4.3 Reactivating at sign-in

Every sign-in path treats DEACTIVATED differently from BANNED or LOCKED, and only after the
credential has been checked, so the answer reveals nothing about an account the caller cannot
open:

- `/login` with the right password, and `/auth/oauth/{provider}` with a valid token for a linked
  identity, answer `403 ACCOUNT_DEACTIVATED`. When `deletion_scheduled_at` is set they answer
  `403 ACCOUNT_DELETION_PENDING` instead. The wrong credential is `401 INVALID_CREDENTIALS` exactly
  as now.
- **As built, there is no `/reactivate` endpoint.** `LoginRequest` and `SocialLoginRequest` gain
  `reactivate: boolean`, and the client sends the refused sign-in again with it set. The request
  goes through every check a sign-in does — rate limit, verified address, provider link — and
  `AccountClosureService.admit` then sets ACTIVE, clears both columns and queues the event in one
  transaction, and the sign-in answers its normal `TokenResponse`. A separate endpoint would have
  had to repeat all of those checks. `/oauth/{provider}/link` never reopens; a closed account
  there is told so, and the client reopens it with a sign-in, which finds the link confirmed.

So the client resends the credential it still holds in the form rather than carrying a
reactivation token, and there is nothing to store or expire. `OAuthServiceImpl.requireStillActive`
and the `status == ACTIVE` check in `AuthServiceImpl.login` are the two places that change.
Admin MFA is unaffected: admins cannot be DEACTIVATED.

## 5. Events

New outbox aggregate `ACCOUNT` → topic **`auth.account-events`**, keyed by `userId`. One event type,
following this service's one-type-per-topic routing (§4.3 of the private-account design):

```java
// libs/event-schema, com.tiktok.event.user — beside auth-service's other events
record AccountStateChangedEvent(
    String eventId, Instant occurredAt, Long userId,
    AccountState state,              // DEACTIVATED | ACTIVE | DELETED
    Instant deletionScheduledAt      // set only with DEACTIVATED after a delete request
)
```

Every consumer applies it as a **state assignment guarded by `occurredAt`**: it records the state
unless it already holds a newer one. A redelivery repeats it, and a late re-send changes nothing,
the same guarantee `OwnerBans` and `UserBanRegistry` give for bans. No inbox claims, except for the
DELETED work in §7, which is not one assignment.

**Why not reuse the ban record.** It is keyed by user and holds one decision. If deactivation wrote
into it, an admin's unban would reopen an account its owner closed, and a reactivation would lift
a ban. Each record therefore gains a second, independent pair of fields:

| Field | Meaning |
|---|---|
| `deactivatedAt` | null when the account is open |
| `accountStateChangedAt` | guard for the field above, separate from the ban's `changedAt` |

An owner is **hidden** when `bannedUntil > now` **or** `deactivatedAt != null`. Every read that asks
"is this owner banned" asks this instead, on the row it already loads, so no read path gains a
query.

Each guard has to accept null as well as an older time: a row the other decision created has
never had this guard set. Mongo matches `changedAt is null or lte`, Cassandra adds an
`UPDATE … IF col = null` before the insert. Without it the closure of a banned owner (or the ban
of a closed one) was refused for good. The ban's guard in video-service had the same gap and was
fixed with this change.

## 6. Consumers

### 6.1 user-service

**`V16__add_account_state_to_user_profiles.sql`**: `deactivated_at TIMESTAMPTZ`,
`account_state_changed_at TIMESTAMPTZ`. New `AccountStateEventConsumer` on `auth.account-events`.

A deactivated profile is treated as **not there for anyone else**:

| Read | Behaviour |
|---|---|
| `GET /users/{id}`, `/by-username/{u}` | `404 USER_PROFILE_NOT_FOUND`, the same as a block, so it confirms nothing |
| `GET /users?ids=` batch | omitted, as an unknown id is |
| `GET /users/search`, `/internal/search-ids` | excluded, so search-service's account tab follows without its own change |
| followers / following / follow-requests lists | the account is left out of the page |
| `POST /users/{id}/follow` | `404`, through the existing "both profiles exist" check |
| `/friendship`, internal follow checks | unchanged; edges still exist and are only hidden |

Counters are not touched (§2).

### 6.2 video-service

`OwnerBan` gains the §5 pair. The places that ask `bannedAmong` (`listByUser`, `getById` /
`isVisibleTo`, batch, both feeds, stats) and the feed's "every ban in force" query use the
**hidden** rule instead. The repository query becomes `bannedUntil > now OR deactivatedAt != null`,
with `deactivatedAt` indexed beside `bannedUntil`.

### 6.3 search-service

The `owner_bans` index gains the §5 pair, and the video query's exclusion uses the hidden rule.
Account search needs nothing here (§6.1).

### 6.4 interaction-service

`user_bans` gains `deactivated_at` and `account_state_changed_at`, with an LWT on the latter, as
`updateIfNotNewer` does for the ban. Comments by a hidden author leave every listing. As with a ban,
counters keep them, so reactivating brings them back with nothing to recount.

### 6.5 Unchanged

- **recommendation-service**: candidates are hydrated through video-service's batch, which drops
  hidden owners. `ponytail:` a page can come back short. That is fine at this scale.
- **notification-service**: a notification whose actor is hidden resolves to a blank profile
  through the existing `blankProfile` path. Nothing new is sent to anyone.
- **chat-service**: realtime frames are built from video-service and user-service answers, which
  already hide the account.
- **api-gateway**: `/api/v1/auth/**` is already routed.

### 6.6 Direct messages, deferred

Conversations with a deactivated account stay and show a blank member. Sending to one is not
refused yet. `ponytail:` chat-service would need the same consumer to refuse new messages with
`MESSAGING_NOT_ALLOWED`.

## 7. Deletion

`AccountDeletionSweep` in auth-service, hourly, shaped like `ExpiredBanSweep`. For each row with
`deletion_scheduled_at < now` and not yet deleted, in its own transaction:

1. **Free the credentials**: null the email, password hash and username, delete every
   `UserIdentity`, set `deleted_at`. The address and the Google/Facebook accounts can then sign up
   again as new accounts, and the username can be taken.
2. Queue `AccountStateChangedEvent(DELETED)`.

Consumers of DELETED. These take an inbox claim on the eventId, because they are fan-outs:

- **user-service**: removes every follow edge in both directions and decrements the other side's
  counter for each, publishing `UserFollowChangedEvent(followed=false)` per edge, so chat-service's
  stats frames and every follow cache see the change. It also deletes pending requests both ways,
  blocks, mutes and settings, and soft-deletes the profile. `ponytail:` one transaction; an account
  with tens of thousands of edges would want a batched job.
- **video-service**: moves every live video to the trash, as if its owner had deleted each one.
  The deletion outbox announces them, which withdraws them from search and recommendation, and the
  trash retention purges the media later. No claim: the move only matches videos not in the trash.
- **interaction-service, notification-service**: nothing more. The hidden flag already stays set
  forever. `ponytail:` the comments, likes and inbox rows remain in storage, and like and comment
  counts on other people's videos still include them. A physical purge and a recount are a
  separate piece of work.

## 8. Frontend (tiktok-cloned)

- **Settings**: the row gets `action: "close-account"` and opens a flow:
  1. Choose Deactivate or Delete, each with the consequences from §2.
  2. Reconfirm, using the method §4.2 picks from `/auth/me`: a password field, "Send code" plus a
     code field, or "Continue with Google/Facebook".
  3. Confirm.

  On success the client clears its session locally (the server has already revoked it), shows a
  toast and goes to `/`.
- **Sign-in**: `ACCOUNT_DEACTIVATED` and `ACCOUNT_DELETION_PENDING` open a "Reactivate your
  account?" step instead of an error. Confirming sends the same sign-in again with
  `reactivate: true`, then continues as a normal sign-in. Cancelling leaves the account closed.
- **API**: functions in `lib/api/auth.ts`, copy in `lib/api/errors.ts`. The form schema is zod, as
  for the other auth forms.

### 8b. Admin console (tiktok-admin) — added after the build

The draft left the console out; two things broke without it.

- **Names.** The console named every user through the public `GET /users?ids=`, which now drops a
  closed account, so the reports, uploads and page of exactly that account lost its handle.
  user-service gained `GET /users/admin?ids=` (ROLE_ADMIN): the same batch with no viewer rules, a
  closed account included, a deleted one absent. The console's `getUserProfiles` uses it.
- **Status.** `DEACTIVATED` is a status, tab and badge ("Deactivated"). `AdminUserResponse` carries
  `deactivatedAt` and `deletionScheduledAt`, and the directory row and the account page say when the
  owner closed it and when the deletion runs — on a BANNED account too, since a ban does not stop
  it (§3).

## 9. Errors

| Code | HTTP | Raised by |
|---|---|---|
| `ACCOUNT_DEACTIVATED` | 403 | `/login`, `/auth/oauth/{provider}`, with a valid credential |
| `ACCOUNT_DELETION_PENDING` | 403 | the same, when deletion is scheduled |
| `ACCOUNT_CLOSURE_NOT_ALLOWED` | 403 | deactivate / delete by an ADMIN |
| `INVALID_CREDENTIALS`, `INVALID_SOCIAL_TOKEN` | 403 | a wrong reconfirmation (§4.2); still 401 at sign-in |
| `INVALID_OTP`, `TOO_MANY_*` | as today | reconfirmation and reactivate |

## 10. Testing

In each service's existing style:

- **auth-service**
  - Each reconfirmation method, and exactly-one validation.
  - ADMIN refused.
  - Deactivate and delete set the right columns, queue one event, and revoke sessions.
  - Login and OAuth answer `ACCOUNT_DEACTIVATED` / `ACCOUNT_DELETION_PENDING` only after a valid
    credential, and `INVALID_CREDENTIALS` before one.
  - Reactivate clears both columns.
  - `unban()` returns a deactivated account to DEACTIVATED.
  - The sweep frees credentials and runs on a banned account too.
  - Outbox routing for `ACCOUNT`.
- **user-service**: each read in §6.1 for a deactivated profile; the `occurredAt` guard; DELETED
  removes edges and decrements both sides, idempotently under redelivery.
- **video-service, search-service, interaction-service**: the hidden rule over all four
  combinations of banned and deactivated; that an unban leaves a deactivated owner hidden and that
  reactivation leaves a banned owner hidden; the guard on a late event.
- **Frontend**: unit tests for any pure helpers (choosing the reconfirmation method); a component
  test for the reactivate step.

Every touched service's full suite passes before its commit.

## 11. Rollout

- All migrations are additive and nullable; every existing account reads as open. A record without
  the new fields reads `deactivatedAt = null`.
- **Order**:
  1. `event-schema`.
  2. The consumers: user-service, video-service, search-service, interaction-service.
  3. auth-service.
  4. The frontend.

  The consumers sit idle until auth-service publishes, so the first deactivation is enforced
  everywhere as it happens. auth-service shipped first would hide nothing for up to a deploy.
- Deploy auth-service stop-then-start, for the reason user-service is in the private-account
  rollout: an old instance does not know the `ACCOUNT` aggregate and would drop its outbox rows.

## 12. Documents to update with the code

`auth-service-api.md` (endpoints, errors, the new topic), `user-service-api.md` (§6.1 behaviour,
the consumer), `video-service-api.md` and `search-service-api.md` (the hidden rule), and
`user-settings.md` §7, which should point here instead of listing the endpoints as deferred.
