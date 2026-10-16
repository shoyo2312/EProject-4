# Text moderation Phase 2 — classifier, creator filters, strikes, admin terms, limits

Date: 2026-10-14 · Branch: `feat/text-moderation` · Status: approved ("làm phase 2")
Builds on `2026-10-13-text-moderation-design.md` (Phase 1), whose §11 lists this phase.

## 1. Scope

| # | Feature | Where |
|---|---|---|
| 0 | Comment text sanitized and counted in code points — documented in Phase 1, not wired | interaction-service |
| 1 | Text classifier `POST /moderate/text` (`visolex/phobert-v2-hsd`) | moderation-service, moderation-lib |
| 2 | Creator comment filters: spam/offensive switch, hold everything, keywords | user-service, video-service, interaction-service |
| 3 | Strikes and a commenting restriction (`COMMENTING_RESTRICTED`) | interaction-service |
| 4 | Upload rate limit | video-service |
| 5 | A banned account's comments hidden | interaction-service |
| 6 | Admin-managed terms on top of the shipped list, with a preview | admin-service, moderation-lib |

No event changes. Still out of scope (Phase 3): shadow holds, per-video comment audience,
cross-account duplicates, bios and display names, strikes from captions.

## 2. Flags gain a source and a third severity

`Flag(category, severity, rule, source)`; the three-argument constructor keeps `source = RULES`.

| Source | From | Acted on when |
|---|---|---|
| `RULES` | shipped list, admin terms, spam signals | `moderation.text.enforce` |
| `MODEL` | the classifier | its severity says (below) |
| `CREATOR` | the video owner's filters | always — the owner chose them |

Severity 1 (`Flag.NOTE`) is recorded and decides nothing. The classifier writes its opinion as a
note when it is not enforcing, and when it saw offence but not enough to hold. A comment stores its
flags (`mod_reason`, `mod_version`) whenever there are any — held or not — so the admin comment
list shows what was let through and why; that list is how the classifier's precision gets measured
here.

New category `CREATOR_FILTER` for holds a creator's settings caused.

## 3. The classifier

### 3.1 moderation-service

`POST /moderate/text` `{"texts": [...]}` (at most 16) →
`{"results": [{"verdict": "ALLOW|HOLD", "label": "CLEAN|OFFENSIVE|HATE", "score": 0.93,
"scores": {...}}], "model", "modelVersion", "processingTimeMs"}`, one result per text, in order.

- **Model:** `visolex/phobert-v2-hsd` (PhoBERT-base-v2 fine-tuned on ViHSD; CLEAN/OFFENSIVE/HATE),
  pinned to commit `221aca47de6568d519eba61a94d7fdae3ca680ae`, weights checked against their
  SHA-256 at build, baked into the image like the NSFW model.
- **No remote code.** The repository's config points at its own `models.py`, which would need
  `trust_remote_code=True` — running whatever that repository holds, at build and at every start.
  Its model is a RoBERTa encoder, the pooler output, dropout and one linear layer; that is
  rewritten in `text_classifier.py` and the weights are loaded with `safetensors` (no pickle) and
  `strict=True`, so a checkpoint of any other shape fails the build instead of scoring garbage. The
  encoder configuration is phobert-base-v2's, which the checkpoint does not carry.
- **Decision:** HOLD when P(HATE) ≥ `TEXT_HATE_HOLD_THRESHOLD` (0.80); otherwise ALLOW with the
  most likely label. OFFENSIVE never holds: ViHSD's OFFENSIVE is mostly profanity used as an
  intensifier, which the shipped list leaves alone on purpose ("đẹp vãi"), and the rules already
  hold the profanity that is abuse. Thresholds live here, with the model, as for frames.
- **Long text** is cut into 254-token windows (at most 8) and scored by its worst window — a
  description's insult after the first 256 tokens is not missed.
- Blank text is ALLOW without running the model. `TEXT_MODERATION_MODEL=` (empty) switches the
  endpoint off (503); the frame model is unaffected.
- `/config` reports the text model, version and threshold for the admin console.

### 3.2 Callers (moderation-lib `TextClassifier`)

RestClient on `PooledRequestFactory`, `moderation.text.classifier.*`: `enabled` (true),
`base-url`, `timeout` (1s), `enforce` (true), `cooldown` (30s).

- **Fails open to the rules.** The rules have already run when the classifier is asked, so an
  unreachable classifier means Phase 1 behaviour, not "every comment held". After a failure the
  classifier is not asked for `cooldown`, so an outage costs one timeout per 30 s, not one per
  comment. This is the opposite of frames (failure → REVIEW) on purpose: frames have no other
  screen, text does.
- HOLD → `Flag(HATE, enforce ? HOLD : NOTE, "model:HATE:0.93", MODEL)`; ALLOW with a label other
  than CLEAN → the same as a NOTE; CLEAN → nothing. The verdict's version gains `+<modelVersion>`.
- Comments ask only when nothing else has held the comment (§4.3); captions ask for title,
  description and tags in one call, always (they are few, and the admin reviewing one wants every
  opinion).

## 4. Creator comment filters

### 4.1 Settings (user-service, V15)

| Field | Default | Meaning |
|---|---|---|
| `filterSpamOffensive` | true | false: comments are not held for PROFANITY, INSULT or SPAM |
| `filterAllComments` | false | every comment from someone else waits for approval |
| `commentKeywords` | `[]` | a comment containing one waits for approval |

Keywords: at most 50, each sanitized to one line, 1–30 characters, at least one letter or digit,
duplicates (case-insensitive) dropped, stored as typed. They match like the blocklist: whole
syllables, and a keyword written with diacritics matches only the same diacritics.

`filterSpamOffensive=false` cannot switch off REJECT, hate, sexual content, threats, scams or
personal information: those protect the people reading the thread, not the creator's taste.

### 4.2 Relay

`/internal/settings` → video-service `/policy` (`filterSpamOffensive`, `filterAllComments`,
`commentKeywords`, null when user-service did not answer) → interaction-service `CommentPolicy`.
video-service caches the whole policy as JSON under a new key, `user-settings:comment-policy:{id}`,
with the existing TTL; old `comment-audience` keys expire on their own. Fails open to the defaults,
like the audience: an outage must not hold every comment on the platform.

### 4.3 Order inside `CommentScreening`

1. Rules (shipped + admin terms + spam). Enforcing REJECT → strike, 422.
2. The owner's own comment: never held (unchanged), flags recorded.
3. Creator flags: `filterAllComments` → `CREATOR_FILTER:all`; keyword → `CREATOR_FILTER:term:<kw>`.
4. If nothing holds yet: the classifier.
5. Held when any flag at severity ≥ 2 counts: RULES only while enforcing, minus PROFANITY, INSULT and
   SPAM when `filterSpamOffensive` is off; MODEL and CREATOR always.

## 5. Strikes and the commenting restriction

Redis, `interaction.comment.strikes.*`:

- **A strike:** a comment refused by the rules (422), or a comment an admin removed
  (`CommentRemovedEvent`, counted only by the call that deleted it — a redelivery does not strike
  twice).
- **`threshold` (3) strikes within `window` (24h)** restrict commenting for the next duration in
  `restrictions` (1h, 24h, 7d); the step resets after `step-window` (30d) without a restriction.
  The strike count resets when a restriction starts.
- A restricted account posting → `403 COMMENTING_RESTRICTED` with `Retry-After`, checked first,
  before the rate limit. `GET /comment-permission` answers `COMMENTING_RESTRICTED` with
  `restrictedUntil`, so the client can say until when. Likes, deletes and reading are unaffected.
- Admin: `GET` / `DELETE /api/v1/interactions/admin/users/{userId}/comment-restriction` to see and
  lift one (a bad term can restrict innocent accounts).
- Fails open (Redis down → no strike, no restriction), like the rate limit.

## 6. Upload rate limit (video-service)

`app.upload.rate-limit`: `upload-url` 30/hour, 100/day; `publish` 10/hour, 30/day; 0 = off. Same
check-all-then-count Lua as comments, fails open, `429 UPLOAD_RATE_LIMITED` + `Retry-After`. A
publish refused for its caption still spends budget, so captions cannot be probed at speed.

## 7. A banned account's comments

interaction-service consumes `UserBannedEvent` / `UserUnbannedEvent` (already on
`admin.moderation-events`) into Cassandra `user_bans (user_id, banned_until, changed_at)`, guarded
by `changed_at` the way video-service guards `owner_bans` (LWT update, else insert-if-not-exists,
twice). Listings — threads, replies, the owner's held queue — drop comments whose author has
`banned_until > now`, one `IN` on the page's authors. Counters are not touched: a lapsed or lifted
ban brings the comments back with nothing to recount. The admin list shows everything.

## 8. Admin-managed terms

### 8.1 admin-service (V7)

`blocklist_terms (id, term, term_key, category, action, note, created_by, deleted_by, revision,
BaseEntity columns)`; `action` ∈ HOLD, REJECT, EXEMPT. `term_key` is the term as the matcher keys
it (syllables, diacritics kept when written) — unique among live rows. `revision` comes from a
sequence on every insert and every delete; the list's revision is the maximum, deleted rows
included.

| Endpoint (ROLE_ADMIN) | |
|---|---|
| `GET /api/v1/admin/moderation/terms?q=&action=` | page of live terms |
| `GET /api/v1/admin/moderation/terms/summary` | shipped version and size, revision, admin term count |
| `POST /api/v1/admin/moderation/terms` | add; 409 `MODERATION_TERM_EXISTS`; EXEMPT must name a shipped term |
| `DELETE /api/v1/admin/moderation/terms/{id}` | soft delete |
| `POST /api/v1/admin/moderation/text-preview` | `{text, surface}` → the verdict the services would reach, classifier included |

`GET /api/v1/admin/internal/blocklist` (internal token; gateway `denyAll`) → `{revision, terms}`.

### 8.2 Services

moderation-lib's `AdminTermsSync` polls it every `moderation.text.admin-terms.refresh` (60s) on one
daemon thread when `moderation.text.admin-terms.base-url` is set, and rebuilds the matcher only when
the revision moved: shipped list minus EXEMPT terms, plus HOLD/REJECT terms (the higher severity
wins on a clash). Version `rules-<shipped>+admin-<revision>`. Unreachable → the last list stays;
before the first answer the shipped list alone applies. interaction-service, video-service and
admin-service (for the preview) use it.

## 9. Errors

| Code | Status | When |
|---|---|---|
| `COMMENTING_RESTRICTED` | 403 + `Retry-After` | strikes restricted the account |
| `UPLOAD_RATE_LIMITED` | 429 + `Retry-After` | upload-url or publish over a tier |
| `MODERATION_TERM_EXISTS` | 409 | the term (as keyed) is already live |
| `MODERATION_TERM_NOT_FOUND` | 404 | delete of an unknown or deleted term |
| `INVALID_MODERATION_TERM` | 400 | no letters/digits, too long, EXEMPT of a term not shipped |

## 10. Rollout

1. Cassandra: re-run `cassandra-init` (new `user_bans`) before interaction-service.
2. user-service (V15) → video-service (relays) → interaction-service (reads) — each tolerates the
   previous one being old: absent fields read as defaults.
3. admin-service (V7) before the services that poll it; until then they log and use the shipped list.
4. moderation-service image rebuilt (downloads ~540 MB once). `TEXT_CLASSIFIER_ENFORCE=false` for a
   week is recommended: notes land on comments and videos, the admin lists show them, and the
   threshold is tuned on what admins then keep or remove.
5. Licence: the fine-tune is MIT; its base, `vinai/phobert-base-v2`, is AGPL-3.0 on its card.
   Fine for this project; check before a commercial deployment.

## 11. Testing

Python: decision rule, windows, blank text, the endpoint with a stub model, `/config`. Plus one
smoke run against the real weights in the built image. Java: `TextClassifier` against a stub
server (enforce/note mapping, cooldown, fail-open), Blocklist merge/exempt/contains, admin terms
sync, `CommentScreening` (each step of §4.3), strikes Lua against Redis, restriction 403 and
permission, ban filter on listings and queue, keyword validation and relay, upload limiter, admin
term CRUD and preview, internal endpoint authorization, gateway rule.

## 12. Changed during implementation

`docs/moderation.md` describes what was built. Where it differs from the sections above:

- **Windows (§3.1):** 64-token pieces, 48 apart, at most 32 (`TEXT_MAX_WINDOWS`), instead of 254
  tokens × 8. Measured on the built image: one hateful sentence after ~200 tokens of a food review
  scored CLEAN 0.99 as a single 256-token window. The model learned from comments a few dozen tokens
  long. In 64-token pieces the same text holds at 0.96.
- **Threshold (§3.1):** `TEXT_HATE_HOLD_THRESHOLD` defaults to 0.90, not 0.80. It was raised together
  with the shorter windows. In the sample run, the mild `ủa sao ngu vậy trời` scored HATE 0.825, so
  0.80 would have held it.
- **Admin terms table (§8.1):** `moderation_terms` (V7), not `blocklist_terms`. The revision is one
  row in `moderation_terms_revision`, moved in the same transaction as each add and delete, instead
  of a sequence. Its row lock makes revisions commit in order. With a sequence, a change committing
  after a higher revision had already been read would never be fetched, because the pollers only
  compare revisions.
- **Admin terms (§8.1):** a HOLD or REJECT term that the shipped list already flags at least as
  hard is refused (`INVALID_MODERATION_TERM`): the stricter entry wins in the matcher, so the new
  term would look like it works and change nothing.
