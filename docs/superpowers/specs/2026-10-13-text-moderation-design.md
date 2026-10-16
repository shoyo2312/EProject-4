# Text moderation, input hygiene and comment limits — design

Date: 2026-10-13 · Branch: `feat/text-moderation` · Status: approved (Phase 1)

## 1. Problem

Comments, video titles, descriptions and tags are stored exactly as typed. Nothing reads them
before they reach a viewer: `docs/moderation.md` lists text as "Not in V1". Around that gap:

- **Validation counts the wrong thing.** `@Size` counts UTF-16 units on the raw string, so an emoji
  costs two, Vietnamese typed in decomposed form (Unikey's "Unicode tổ hợp", text pasted from a
  macOS file name) costs up to three per letter, and zero-width or bidi characters (U+200B, U+202E)
  pass `@NotBlank` — `trim()` does not remove them. A comment made of Hangul fillers is "not blank".
- **The comment limit is per (user, video) only.** 60 an hour on *each* video: a bot spreading the
  same comment over a thousand videos never meets it. The gateway's per-IP limit cannot stand in for
  it either: every web user arrives through the Next.js rewrite, from one IP.
- **A reply can reach someone who blocked its author.** The block check covers the video owner
  (video-service hides the video), not the author of the comment being replied to.

Already in place and unchanged: per-video comments switch, the owner's account audience
(`EVERYONE`/`FRIENDS`/`NO_ONE`), visibility and block checks on the video, `comment-permission`.

## 2. Decisions

Agreed on 2026-10-13:

- **Three outcomes for a comment:** ALLOW (posted as today), HOLD (stored, invisible until the video's
  owner approves it), REJECT (refused, nothing stored).
- **Held comments are transparent.** The author gets `201` with `status: HELD` and the client says the
  comment is waiting for the creator. A TikTok-style shadow hold — the author sees their comment as if
  posted — is later work (§11).
- **Phase 1 is rules only.** A Vietnamese blocklist plus spam heuristics, in-process. A text
  classifier (PhoBERT, in moderation-service) is Phase 2.
- **Text never removes a video by itself.** A caption the rules flag sends the video to admin review
  (`PENDING_REVIEW`) at most. Only the worst terms refuse the *request* (422) — before anything is
  stored, so the uploader edits the caption and publishes the same upload again.
- **Links and phone numbers in a caption send the video to review.** Links to a short list of social
  profile domains (YouTube, Facebook, Instagram, TikTok, X, Threads) do not — creators link their
  other accounts. In a comment every link is held.

Out of scope here, in order of the roadmap (§11): a text classifier, creator filter settings,
strikes and muting, an upload rate limit, hiding a banned user's comments, shadow holds, per-video
comment audience.

## 3. Two forms of the same text

| Form | Used for | Made by |
|---|---|---|
| **Stored** | the database, what viewers see, length limits, a classifier's input | `TextSanitizer`, in the request record's compact constructor |
| **Matching** | the blocklist, duplicate detection | `TextNormalizer`, inside the rule engine |

**Stored form** — never changes what a reader sees, only what a reader cannot see:

- NFC, so a decomposed "ệ" is one code point and matches a blocklist written in NFC.
- Dropped: control characters other than `\n` (`\r\n` and `\r` become `\n`), bidi controls
  (U+061C, U+200E/F, U+202A–U+202E, U+2066–U+2069), zero-width and format characters (U+00AD,
  U+180E, U+200B, U+200C, U+2060–U+2064, U+FEFF), tag characters (U+E0000–U+E007F).
- U+200D (zero-width joiner) is kept only between two pictographs — it is what holds 👨‍👩‍👧
  together — and dropped everywhere else, where all it does is split a word in two.
- Whitespace look-alikes become a space: every Unicode space separator, tab, and the invisible
  fillers used to post "empty" text (U+115F, U+1160, U+3164, U+FFA0, U+2800).
- Runs of spaces collapse to one; lines are trimmed; three or more line breaks collapse to two.
  Single-line fields (title, tag) turn line breaks into spaces.
- Trimmed. A text that was only invisible characters is now empty and fails `@NotBlank`.

Length is then counted in code points (`@MaxCodePoints`), the count a JavaScript client gets from
`[...text].length`: comment 150, title 150, description 2000, tag 50.

**Matching form** — lossy on purpose, never stored:

1. NFKC (full-width letters, mathematical alphanumerics and ligatures become plain letters), then
   lower case (`Locale.ROOT`), then any combining mark NFKC left loose is dropped (a variation
   selector, a line drawn under or through each letter), then look-alikes read as the letter they
   pass for: Cyrillic and Greek to Latin (`о`→`o`, `с`→`c`, …), and `ð`/`ɖ` — the lower case of
   `Ð`/`Ɖ`, both drawn as `Đ` — to `đ`.
2. Split into syllables on anything that is not a letter or digit.
3. Inside a syllable that has letters: `0→o 1→i 3→e 4→a 5→s 7→t`, and `@ $ !` between two letters
   read as `a s i`. A syllable of digits only is left alone — "2026" is a year, not a word.
4. A letter repeated three times or more collapses to one: `nguuuu` → `ngu`. Doubles stay — `xoong`
   and `good` are words.
5. Two variants of the sequence: **accented** (as above) and **folded** (diacritics removed, `đ`→`d`).
6. A run of two or more one-letter syllables is also read joined: `đ.m`, `v c l`, `đ ị t` → `đm`,
   `vcl`, `địt`.

## 4. The rule engine (`libs/moderation-lib`)

New shared library, auto-configured like `kafka-lib`. interaction-service and video-service use it;
user-service can reuse it for bios and display names later.

### 4.1 Blocklist

`moderation/blocklist-vi.txt` on the classpath, one entry per line: `term | CATEGORY | severity`,
plus a `version:` line. **How a term is written decides how it matches**, and this is the rule to
keep in mind when adding to the list:

- Written **with** diacritics (or `đ`) → matched against the accented text. `cặc` never matches
  `các`, `lồn` never `lớn`, `buồi` never `buổi`, `đĩ` never `đi`.
- Written in **plain ASCII** → matched against the folded text. `dm` catches `đm`, `Đ.M`, `đ m`.

Folding everything would be the obvious design and it is wrong for Vietnamese: the folded forms of
the worst words are among the most common words in the language. ASCII entries are therefore kept
to forms with no innocent reading (`dm`, `dcm`, `dit me may`).

Matching is by whole syllables, one to three in a row — a dictionary lookup per window, never a
substring scan, so `ngu` matches the syllable "ngu" and not "nguyên". Terms are normalised with the
same function as the text when the list is loaded.

Mild intensifiers that are everyday Vietnamese (`vãi`, `vl`, `vcl`, `đéo`) are deliberately *not*
listed: holding them would hold a large share of ordinary comments.

### 4.2 Spam signals

In code, not in the list:

| Signal | Rule | Category |
|---|---|---|
| Link | `http(s)://`, `www.`, or `name.tld` for common TLDs; also `(.)com`, and `chấm com`/`dot com` via the list | `SPAM` |
| Phone number | Vietnamese mobile, `0`/`84`/`+84` then `3/5/7/8/9` and 8 digits, separators allowed | `PERSONAL_INFO` |
| Betting brand | a syllable like `fb88`, `sv388`, `789bet`, `f8bet` (letters, at most one digit, then `88`; digits+`bet`), plus named brands in the list. `minh1988` is a birth year, not a brand | `SCAM` |
| Mention flood | 5 or more `@name` in one text | `SPAM` |

A flag records the domain of a link, never the phone number.

### 4.3 Verdict

Categories: `PROFANITY`, `INSULT`, `HATE`, `SEXUAL`, `VIOLENCE`, `SCAM`, `SPAM`, `PERSONAL_INFO`.
Severity 3 means refuse, 2 means a human decides. Spam signals are severity 2.

```
TextVerdict(decision, flags, version)
  decision = REJECT  if any flag has severity 3
             HOLD    if any flag at all
             ALLOW   otherwise
  flag     = CATEGORY:rule        e.g. PROFANITY:term:đm, SPAM:link:bit.ly, PERSONAL_INFO:phone
  version  = rules-<blocklist version>
```

Bump the list's `version:` whenever it changes — the version travels onto every held comment and
every video, and without it a decision cannot be attributed to the list that made it.

### 4.4 Enforcement switch and metrics

`moderation.text.enforce` (default `true`). With `false` every verdict is computed, logged and
counted but nothing is held or refused — for measuring false positives on real traffic before
switching on. Every verdict increments `text_moderation_decisions_total{surface, decision, category,
enforced}` (`category` = the most severe flag's, or `NONE`).

## 5. Comments (interaction-service)

### 5.1 Order of checks

```
POST /videos/{id}/comments
 1. Sanitize + validate (record)                       → 400 VALIDATION_ERROR
 2. Rate limit, all tiers in one Redis script           → 429 COMMENT_RATE_LIMITED + Retry-After
 3. Video visible to the caller               (as now)  → 404 VIDEO_NOT_FOUND
 4. Comments switch / account audience        (as now)  → 403 COMMENTS_DISABLED / COMMENTS_NOT_ALLOWED
 5. Parent live and visible; its author has not blocked → 404 COMMENT_NOT_FOUND / 403 REPLY_NOT_ALLOWED
    the caller and the caller has not blocked them
 6. Rules                                               → 422 COMMENT_REJECTED
 7. Duplicate claim                                     → 429 DUPLICATE_COMMENT
 8. ALLOW → stored and announced as now
    HOLD  → stored HELD: no index row, no counters, no event → 201 { status: "HELD" }
```

The rate limit moves ahead of both video-service calls: a bot is stopped in Redis without costing a
round trip, and since it is ahead of the rules, a refused comment still spends budget — the
blocklist cannot be probed at speed.

The video owner's own comments are never held — they are the one who would review them — but are
refused like anyone's: severity 3 is abuse whoever writes it.

### 5.2 Rate limit tiers

One Lua script checks every tier first and increments them only if all pass, keeping
`InteractionRateLimiter`'s TTL healing. Fails open when Redis is down, as every limiter here does.
`Retry-After` is the remaining TTL of the tier that refused. Configurable under
`interaction.comment.rate-limit`:

| Tier | Key | Default | Stops |
|---|---|---|---|
| Cooldown | `interaction:comment-rate:cooldown:{u}` | 1 per 3s | rapid fire |
| Minute | `interaction:comment-rate:m:{u}` | 10 | bursts |
| Hour, all videos | `interaction:comment-rate:h:{u}` | 100 | spreading over many videos — the gap today |
| Day | `interaction:comment-rate:d:{u}` | 500 | slow drip |
| Hour, one video | `interaction:comment-rate:{u}:{v}` | 60 | flooding one thread (today's key) |

### 5.3 Duplicates

Hash of the matching form (so `Hay quá!!!` and `hay qua` are the same text), never the text itself:

- the same text in the **same** place within 10 minutes → `DUPLICATE_COMMENT`. A place is a
  video's thread, or one comment on it replied to: answering two people with the same words is a
  conversation, answering one of them twice is a copy;
- the same text in **more than 5** places within 10 minutes → `DUPLICATE_COMMENT` — replies under
  one video count, or pasting under every comment of a viral video would go free;
- never asked of the video owner on their own video: thanking each fan with the same "cảm ơn bạn" is
  how creators answer, and there it is spam to no one.

Claimed after the rules pass — a refused comment never takes a claim, so resending it gets
`COMMENT_REJECTED` again rather than a misleading "duplicate" — and released when the save fails, so
a retry after a 500 is not refused as its own duplicate. Under `interaction.comment.duplicate`.

### 5.4 Held comments

**Storage.** `comments_by_video` gains `status` (`VISIBLE`/`HELD`, null = `VISIBLE` for every row
written before this), `mod_reason` (the flags) and `mod_version`. A new table is the owner's queue:

```sql
CREATE TABLE IF NOT EXISTS held_comments_by_video (
    video_id   bigint,
    comment_id bigint,
    PRIMARY KEY ((video_id), comment_id)
) WITH CLUSTERING ORDER BY (comment_id DESC);
```

A held comment has no `comment_index` row — the listings read that table, so it cannot leak into
them — and touches no counter and publishes no event. Its row and its queue row are written
together; if the queue row fails the comment row is removed, because a held comment nobody can find
is a comment stuck forever.

The console's thread view reads `comment_index` as well, so it could never show a held top-level
comment. `GET /admin/videos/{videoId}/comments?filter=HELD` scans `comments_by_video` for what still
waits, top-level and replies alike, the way the Replies and Removed views already scan it.

**Owner endpoints** (both fail closed: an owner that cannot be confirmed is `403 NOT_VIDEO_OWNER` —
the queue holds text that has not been cleared for anyone):

| Endpoint | Answer |
|---|---|
| `GET /videos/{videoId}/held-comments?cursor=&size=` | `CommentPageResponse`, newest first, `status: HELD`. Replies whose thread was deleted meanwhile are left out |
| `POST /videos/{videoId}/comments/{commentId}/approve` | `CommentResponse`, `status: VISIBLE`. Already visible → the same answer (a second tap is not an error). Gone, unknown, or a reply whose thread was deleted → `404 COMMENT_NOT_FOUND` |
| `DELETE /videos/{videoId}/comments/{commentId}` | rejecting is deleting: the existing endpoint, which already lets the owner delete any comment on their video |

Not under `/comments/` for the listing, where the segment after it is a comment id.

**Approval** is an LWT, `SET status = 'VISIBLE' … IF status = 'HELD' AND deleted_at = null`, and only
the caller it applies for writes the index row, moves the counters, publishes `CommentCreatedEvent`
and drops the queue row — the same shape as `markDeletedIfNotDeleted`, so two taps or two devices
count the comment once. Any failure after the LWT takes the counters back and puts the status back to
`HELD`.

**Everything else becomes status-aware:**

1. **Deleting a held comment must not touch the counters.** `softDelete` takes them down on every
   delete, and a held comment was never counted — deleting one through the current code leaves
   `commentCount` low for good. Held comments take their own path: an LWT conditioned on
   `status = 'HELD'` (so it cannot cross an approval in flight), no counters, no event, the queue
   row dropped. This covers the author, the owner and `removeByAdmin`.
2. **`CommentCreatedEvent` only for visible comments.** The six consumers (video counter, search,
   trending, analytics, notification, chat's live comment frames) need no change, and a held
   comment never reaches a socket.
3. **Listings filter on visibility** as well as deletion, behind the index that already excludes
   held comments.
4. **A held comment cannot be liked or replied to** — both currently check `deletedAt` only.
5. **`CommentIndexBackfill`** skips held comments.
6. The admin listing shows held comments with their `status` and `moderationReason`.

`ponytail:` approving a top-level comment announces it then, so notification-service tells the
owner about a comment they just approved. A flag on the event would let it skip that; it costs a
contract change for a redundant notification.

### 5.5 Reply blocks

After the parent lookup, `BlockClient` asks `/api/v1/users/internal/blocks` about the author of the
comment being replied to — the person a reply notifies. A block in either direction is
`403 REPLY_NOT_ALLOWED`, which does not say who blocked whom. Fails open, like video-service's copy:
this is on every reply, and a user-service outage must not stop all of them.

## 6. Video captions (video-service, media-worker)

### 6.1 Screening at publish

`CreateVideoRequest` sanitizes title (single line), description and each tag. `publish()` screens the
title, the description and the final tag list — tags too, because they feed search, tag pages and
recommendation's content profile:

- Any field at severity 3 → `422` with that field's code (`VIDEO_TITLE_REJECTED`,
  `VIDEO_DESCRIPTION_REJECTED`, `VIDEO_TAGS_REJECTED`, checked in that order). Nothing is stored; the
  raw upload stays where it is and is published again once the caption is fixed. One code per field
  because the error envelope has no field slot.
- Otherwise the result is stored on the video before the insert:

```
textModeration: { verdict: APPROVED | REVIEW, flags: ["DESCRIPTION:SPAM:link:bit.ly"],
                  version, enforced, checkedAt }
```

Admin reads only, like `moderation`: the console needs it to explain a `PENDING_REVIEW` video whose
frames were fine, and the public must not watch it move.

### 6.2 Where the two verdicts meet

The obvious place to combine the text verdict with the visual one is `Video.outcomeOf`. It is the
wrong place: search-service and recommendation-service read `VideoModerationCompletedEvent`
themselves and publish on `APPROVED`, so a video held in video-service for its caption would still be
searchable and on the For You candidates. The verdict has to be combined **before** that event exists:

```
video-service   VideoPublishedEvent.textVerdict  (APPROVED | REVIEW; null = not screened)
media-worker    copies it onto VideoTranscodedEvent.textVerdict (success only)
media-worker    visual APPROVED and text REVIEW → REVIEW,
                reason "Caption held for review by text moderation", scores kept
                → VideoModerationCompletedEvent (unchanged shape)
```

Everything downstream of the verdict — video-service's status, search, recommendation, chat's
frames, media quarantine — then agrees without a line changed. Text never turns a verdict into
`REJECTED`; a visual `REVIEW` or `REJECTED` stays what it was. With `moderation.text.enforce=false`
video-service sends no text verdict. With media-worker's `MODERATION_ENABLED=false` the text verdict
still applies — it was decided elsewhere.

## 7. Errors

| Code | HTTP | Raised by |
|---|---|---|
| `COMMENT_REJECTED` | 422 | `POST …/comments` — severity 3. Does not say which word |
| `DUPLICATE_COMMENT` | 429 | `POST …/comments` — §5.3 |
| `COMMENT_RATE_LIMITED` | 429 | `POST …/comments` — any tier of §5.2, now with `Retry-After` |
| `REPLY_NOT_ALLOWED` | 403 | `POST …/comments` with a `parentId` — §5.5 |
| `NOT_VIDEO_OWNER` | 403 | held-comment listing and approval |
| `VIDEO_TITLE_REJECTED`, `VIDEO_DESCRIPTION_REJECTED`, `VIDEO_TAGS_REJECTED` | 422 | `POST /videos` |

`Retry-After` needs a header on a `DomainException`: common-lib's handler gains an overridable
`getHeaders()`, empty by default.

## 8. Configuration

| Property | Default | Service |
|---|---|---|
| `moderation.text.enforce` | `true` | interaction, video |
| `moderation.text.blocklist` | `classpath:moderation/blocklist-vi.txt` | interaction, video |
| `moderation.text.caption-link-allowlist` | youtube.com, youtu.be, facebook.com, fb.com, instagram.com, tiktok.com, x.com, twitter.com, threads.net | video |
| `moderation.text.mention-flood` | `5` | interaction, video |
| `interaction.comment.rate-limit.*` | §5.2 | interaction |
| `interaction.comment.duplicate.window` / `.max-copies` | `10m` / `5` | interaction |

## 9. Testing

- **moderation-lib** — sanitizer (NFC, every dropped class, ZWJ kept inside an emoji and dropped
  inside a word, fillers, line collapsing, idempotence), code-point validator, normalizer
  (look-alikes, leet, squeeze, joined letters), the accented/ASCII rule with the collisions in §4.1
  as negative cases, every spam signal with near misses (years, prices, `alphabet`, allow-listed
  domains), verdict precedence, and the list loading its own version.
- **interaction-service** — each rate-limit tier and its `Retry-After`; duplicates on one video and
  across videos, a released claim taken again; reject and hold; the owner's own comment never held;
  held comments invisible to listings, likes and replies, and announcing nothing; approval once
  under a double tap, and back to held when its event is refused; deleting a held comment leaves the
  counters alone (author, owner, admin); approve and listing refuse a non-owner and a failed
  ownership lookup; a held reply whose thread was deleted; the reply block, and `BlockClient`
  failing open; measuring-only mode; the existing suite with the rate limits off in the test
  profile.
- **video-service** — 422 per field with nothing stored, `textModeration` and the event's
  `textVerdict` for clean / review / not enforced, admin response carries it and the public one
  does not.
- **media-worker** — the verdict carried from publish to transcode, and the combination table.
- **event-schema** — the two changed events re-pinned in `event-json.json`.

## 10. Rollout

- **Cassandra first:** re-run `cassandra-init` (`ALTER TABLE … ADD IF NOT EXISTS`, new table) before
  interaction-service starts — Spring's `CREATE_IF_NOT_EXISTS` creates tables but adds no columns,
  and every comment write would fail on the missing `status`.
- **Order:** event-schema, then media-worker (carries and combines; an absent verdict changes
  nothing), then video-service (starts sending), then interaction-service — independent of the other
  three.
- Events written before this read with a null `textVerdict`, which changes nothing. Comment rows
  written before this read as `VISIBLE`.
- Consider a week with `moderation.text.enforce=false` and the counter in §4.4 before enforcing, and
  keep a labelled set of real Vietnamese comments to re-run whenever the list changes.

## 11. Later phases

- **Phase 2:** `POST /moderate/text` in moderation-service with `visolex/phobert-v2-hsd` (holds only
  until its precision is measured here); creator settings `filterSpamOffensive`,
  `filterAllComments`, `commentKeywords` (user-service V15, relayed through `/policy`); strikes and
  muting (`COMMENTING_RESTRICTED`); an upload rate limit; hiding banned users' comments
  (`UserBannedEvent` on `admin.moderation-events`); an admin-managed blocklist.
- **Phase 3:** shadow holds, per-video comment audience, cross-account duplicate detection, the
  same screening for bios and display names.

## 12. Documents to update with the code

`docs/moderation.md` (text replaces its "Not in V1" entry), `interaction-service-api.md` (§3.4,
held comments, errors, rate limits), `video-service-api.md` (§3.2, errors, the event payload),
`CLAUDE.md` (the new lib, and the text verdict in the moderation rules), and the Postman collection
(the held-comment flow, and a wait before any request that posts a second comment within the
cooldown).
