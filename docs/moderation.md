# Automatic Video Moderation

Every uploaded video is screened for NSFW imagery before anyone can see it. The check runs after
transcoding and before publication, so a video that fails it was never on a feed at any point.
Its title, description and tags are read too, at publish, and so is every comment before it is
posted — see [Text: captions and comments](#text-captions-and-comments).

Nothing here costs money: both classifiers — frames and text — are open-weight models running on
CPU inside a container this repo builds, and the text rules are a word list read in process.

## The pipeline

```
video-service          media-worker                moderation-service        video-service
─────────────          ────────────                ──────────────────        ─────────────
VideoPublishedEvent ─► transcode ─► VideoTranscodedEvent ────────────────────► PENDING_MODERATION
                       │
                       └─ (second consumer group on the same topic)
                          sample 10 frames ─► POST /moderate ─► verdict
                                                                │
                          VideoModerationCompletedEvent ◄───────┘
                                                │
                                                └──────────────────────────► PUBLISHED
                                                                             PENDING_REVIEW
                                                                             REJECTED
```

Two events, not one. Transcoding and moderation fail for unrelated reasons — a classifier that is
down says nothing about whether the file is playable — and folding the verdict into
`VideoTranscodedEvent` would mean a video could not be recorded as transcoded until the model had
also answered. Keeping them apart also lets a moderation problem be replayed without re-encoding
anything: the two consumer groups on `media.video-transcoded-events` move their offsets
separately.

| Topic | Producer | Consumers |
|---|---|---|
| `media.video-transcoded-events` | media-worker | video-service (`video-service`), media-worker (`media-worker-moderation`) |
| `media.video-moderation-events` | media-worker | video-service (`video-service`), chat-service (`realtime-<uuid>`, `admin-realtime-<uuid>`) |

## Statuses

| Status | Meaning | Visible? |
|---|---|---|
| `PROCESSING` | uploaded, transcoding | no |
| `PENDING_MODERATION` | transcoded, waiting on a verdict | no |
| `PENDING_REVIEW` | the model was unsure, or could not run, or the caption was held — an admin decides | no |
| `PUBLISHED` | approved | **yes** |
| `REJECTED` | removed by the model, no human involved | no |
| `TAKEN_DOWN` | removed by an admin | no |

Every read path filters `status == PUBLISHED` rather than excluding a list of hidden states, so a
new status is invisible to viewers until something deliberately lets it through.

`REJECTED` stays distinct from `TAKEN_DOWN` because the two are worth counting apart: one is a
model's decision whose thresholds are still being tuned, the other is a person's and is not in
question.

## The decision rule

`services/moderation-service/verdict.py`. Ten frames are sampled evenly across the whole video —
not the first ten seconds, or a video that only turns explicit near the end is screened on
nothing — and each is scored for NSFW.

| Condition | Verdict | Status |
|---|---|---|
| `maxScore < 0.60` | `APPROVED` | `PUBLISHED` |
| `maxScore >= 0.90` **and** at least 2 frames over 0.60 | `REJECTED` | `REJECTED` |
| anything else | `REVIEW` | `PENDING_REVIEW` |

The second condition is two conditions on purpose. A classifier run over ten frames of an
ordinary video will occasionally put one frame over 0.9 — a skin-toned close-up, a bad crop, a
frame caught mid-cut — and rejecting on that alone removes real videos. Requiring a second frame
to agree costs almost nothing on genuinely explicit content, which is rarely explicit in exactly
one sampled frame out of ten.

These are opening numbers, not settled ones. Tune them against `video.moderation.maxScore` on
real uploads, which is why the scores are stored on the video document rather than only logged:

```javascript
// videos the model was unsure about, worst first
db.videos.find({ status: "PENDING_REVIEW" }).sort({ "moderation.maxScore": -1 })

// how confident was the model about videos an admin later restored?
db.videos.find({ statusBeforeTakedown: "REJECTED" }, { "moderation.maxScore": 1 })
```

## Failure is never a pass

The rule is that no failure publishes a video and no failure removes one:

| What broke | What happens |
|---|---|
| moderation-service unreachable or slow | 3 attempts, then `REVIEW` with the reason attached |
| no frame could be decoded | `REVIEW` immediately — retrying produces the same nothing |
| a verdict string this build does not know | `REVIEW` |
| the model's labels do not include `nsfw` | every frame scores 1.0 — a misconfiguration goes to a human |
| an admin took the video down mid-check | the takedown wins; the verdict is recorded as what a restore returns to |
| the verdict is read before the transcode result (separate topics, e.g. a consumer restarting with both waiting) | the verdict is kept on the video and applied when the transcode result lands; a second verdict is ignored |
| media-worker cannot reach Kafka | the transcode event is redelivered and the check re-runs |

There is no path that leaves a video at `PENDING_MODERATION` with nothing to move it. A video
sitting there is a bug, and worth alerting on.

## Running it

```bash
make moderation-up      # build + start (first build downloads both models' weights, ~2 GB image)
make moderation-test    # the decision rules' tests — no model needed
make moderation-score-texts FILE=comments.txt   # the text classifier's precision on judged comments
```

The text model can be left out of an image with `--build-arg TEXT_MODERATION_MODEL=`;
`/moderate/text` then answers 503 and the services screen text with the rules alone.

The container publishes `127.0.0.1:8099` only. That differs from rank-service, which publishes
nothing: media-worker runs on the host via `make run-media` rather than in compose, and with no
mapping at all every upload would fail its check and land in the admin queue.

Set `MODERATION_ENABLED=false` for media-worker to skip the check entirely and approve everything
— for a local run with no moderation container up. It is an explicit operator choice; nothing
turns it off on its own when the service is down.

## Tuning

Thresholds live in moderation-service's environment, not in media-worker, so changing them is a
restart of one container rather than a rebuild of a Java service:

| Variable | Default |
|---|---|
| `MODERATION_REVIEW_THRESHOLD` | `0.60` |
| `MODERATION_REJECT_THRESHOLD` | `0.90` |
| `MODERATION_MIN_REJECT_FRAMES` | `2` |

Bump `MODERATION_MODEL_VERSION` whenever either the weights or the thresholds change. It travels
on every verdict and onto the video document, and without it a decision made months ago cannot be
attributed to the setup that made it.

## Second opinion: Azure AI Content Safety

The local classifier settles both ends on its own — an ordinary video scores about `0.01`, explicit
content about `0.99`. The `0.60`–`0.90` band is the part it is genuinely bad at, and the part that
costs a moderator their afternoon. That band, and only that band, is sent to Azure.

```
local verdict REVIEW ──▶ top 3 frames by local score ──▶ Azure image:analyze
                                                              │
                        severity ≥ 4 ──▶ REJECTED  ◀──────────┤
                        severity ≤ 0 ──▶ APPROVED  ◀──────────┤
                        severity 2   ──▶ REVIEW    ◀──────────┤
                        no answer    ──▶ REVIEW    ◀──────────┘
```

Escalating every video instead would spend the whole month's allowance on the cases that never
needed it: 5,000 images is 500 videos at ten frames each. Escalating only the uncertain videos, and
only the frames that made them uncertain, stretches the same quota across far more uploads.

| Variable | Default | Note |
|---|---|---|
| `AZURE_CONTENT_SAFETY_ENDPOINT` | *(blank)* | Blank turns the whole feature off |
| `AZURE_CONTENT_SAFETY_KEY` | *(blank)* | Blank turns the whole feature off |
| `MODERATION_ESCALATE_FRAMES` | `3` | Frames per escalated video, highest local score first |
| `MODERATION_ESCALATE_BUDGET` | `10` | Seconds for the whole second opinion. Out of time is no verdict — `REVIEW`, recorded as `+azure-slow`. Keep it at most a third of media-worker's `MODERATION_TIMEOUT_MILLIS` (30s): past that, media-worker stops waiting, retries from scratch and bills the same frames again |
| `MODERATION_AZURE_REJECT_SEVERITY` | `4` | Azure's scale is 0/2/4/6 |
| `MODERATION_AZURE_APPROVE_SEVERITY` | `0` | Anything between the two still gets a human |
| `MODERATION_AZURE_CATEGORIES` | `Sexual,Violence,SelfHarm,Hate` | All four come back in one call at no extra quota |

Setting up an account:

1. Azure portal → create an **AI Content Safety** resource, pricing tier **F0** (free).
2. Copy *Endpoint* and *Key 1* into `.env`.
3. `docker compose up -d --force-recreate moderation-service`.

F0 stops at 5,000 images a month and returns HTTP 429 rather than billing, so it cannot run up a
charge. An exhausted quota is recorded on the video as `modelVersion: nsfw-v1+Azure quota exhausted
for this period` — a month of unexplained `PENDING_REVIEW` should be readable off the videos, not
only off a log that has since rolled.

**Frames leave the machine.** This sends stills from user uploads to Microsoft. Fine for a local
build; if this ever serves real users it belongs in the privacy terms.

Failure is never a pass here either. Quota exhausted, network down, a response shape this build
does not recognise — all three leave the video exactly where the local model left it: `REVIEW`.

## Text: captions and comments

Three layers, in this order, each with its own switch:

1. **Rules**, in process (`libs/moderation-lib`): a Vietnamese word list plus spam signals, read by
   video-service and interaction-service. No network call — nothing to be down. Admins add to the
   list, or take terms out of it, from the console without a deploy.
2. **A video owner's own filters**, comments only: hold everything, hold keywords, or waive spam and
   offence on their own videos.
3. **A classifier**, `POST /moderate/text` in moderation-service: a Vietnamese hate-speech model
   behind the rules. It can hold, never refuse.

Design: `docs/superpowers/specs/2026-10-13-text-moderation-design.md` (rules, held comments) and
`2026-10-14-text-moderation-phase2-design.md` (everything else here).

### The rules

`moderation-lib/src/main/resources/moderation/blocklist-vi.txt`, one `term | CATEGORY | severity`
per line. Severity 3 refuses the text; severity 2 sends it to a person. **How a term is written
decides how it matches:** with diacritics, it matches only text with the same diacritics; in plain
ASCII, it matches text with every diacritic removed. Folding everything is the obvious design and
it is wrong for Vietnamese — `cặc`/`các`, `lồn`/`lớn`, `buồi`/`buổi`, `đĩ`/`đi` fold to the same
letters, and the second of each pair is an everyday word. Matching is by whole syllables, after
look-alike letters, leetspeak, long repeats and spelled-out letters (`đ.m`) are undone. The file's
header has the rest; read it before adding a term, and run `ShippedListRegressionTest` after.

In code rather than the list, each severity 2: links (`SPAM`; in a caption, links to YouTube,
Facebook, Instagram, TikTok, X and Threads pass — `moderation.text.caption-link-allowlist`),
Vietnamese mobile numbers and e-mail addresses (`PERSONAL_INFO`), betting brands like `fb88` or
`789bet` (`SCAM`), five or more @mentions in a comment or description (`SPAM`).

| Verdict | A comment | A title, description or tag |
|---|---|---|
| any severity 3 | `422 COMMENT_REJECTED`, nothing stored, a strike | `422 VIDEO_TITLE_/DESCRIPTION_/TAGS_REJECTED`, nothing stored |
| severity 2 only | held for the video's owner | the video goes to `PENDING_REVIEW` |
| nothing | posted | no effect |

Text never removes a video. The worst terms refuse the *request* — the upload stays in MinIO and is
published again once the caption is fixed — and everything else waits for a human.

### Admin-managed terms

The shipped file changes with a release; a false positive found on a Friday night, or a new scam
brand, cannot wait for one. admin-service keeps `moderation_terms` (V7), the console edits it, and
every service that screens text polls the live list:

| Endpoint (ROLE_ADMIN) | |
|---|---|
| `GET /api/v1/admin/moderation/terms?q=&action=&page=&size=` | live terms, newest first |
| `GET /api/v1/admin/moderation/terms/summary` | shipped version and size, admin revision, the version a verdict records |
| `POST /api/v1/admin/moderation/terms` `{term, category, action, note}` | 201; `action` HOLD, REJECT or EXEMPT |
| `DELETE /api/v1/admin/moderation/terms/{id}` | soft delete |
| `POST /api/v1/admin/moderation/text-preview` `{text, surface}` | what the services would make of a text right now, classifier included |

- **HOLD / REJECT** add a term (severity 2 / 3) with a category. One the shipped list already flags at
  least as hard is refused (`INVALID_MODERATION_TERM`): the stricter entry wins in the matcher, so it
  would look like it works and change nothing. Making a shipped HOLD a REJECT is fine.
- **EXEMPT** takes a shipped term out — the fix for a false positive until the file is corrected. It
  must name a term the shipped list has; remember that `nhà cái` and `nha cai` are different terms.
- Terms are sanitized and keyed the way the matcher reads them (`Blocklist.keyOf`): two spellings with
  the same key are one term, and a second live one is `409 MODERATION_TERM_EXISTS`.
- Every add and delete moves `moderation_terms_revision` in the same transaction; the row lock makes
  changes commit in revision order.

`AdminTermsSync` (moderation-lib) asks `GET /api/v1/admin/internal/blocklist` — internal token,
`denyAll` at the gateway — every `moderation.text.admin-terms.refresh` (60 s) and rebuilds the
matcher when the revision moved: shipped list minus EXEMPT, plus HOLD/REJECT. An unreachable
admin-service keeps the last list; before the first answer the shipped list alone applies. Verdicts
record `rules-<shipped version>+admin-<revision>`. Polled rather than pushed: every instance needs
every change, and a Kafka consumer group hands each event to one instance.

### The classifier

`visolex/phobert-v2-hsd` — PhoBERT-base-v2 fine-tuned on ViHSD (CLEAN / OFFENSIVE / HATE), served by
moderation-service at `POST /moderate/text` `{"texts": [...]}` (at most 16), one result per text:
`{verdict: ALLOW|HOLD, label, score, scores}`.

- **Pinned and checked.** Commit `221aca4…` (the image build fetches that commit, never `main`), and
  the weights' SHA-256 is checked at build. Only the files that hold no code are fetched.
- **No remote code.** The repository's config points at its own `models.py`, which would need
  `trust_remote_code=True` — running whatever the repository holds, at every build and start. Its
  model is the RoBERTa encoder, the pooler output, dropout and one linear layer; that is rewritten in
  `text_classifier.py`, and the weights load with `safetensors` and `strict=True`, so a checkpoint of
  another shape fails the build.
- **Only HATE holds**, at `TEXT_HATE_HOLD_THRESHOLD` (0.90). ViHSD's OFFENSIVE is mostly profanity as
  an intensifier, which the word list leaves alone on purpose; it comes back as the label and is
  recorded as a note.
- **Short windows.** Text is cut into 64-token pieces, 48 apart (at most 32), and scored by its worst
  piece. Measured: one hateful sentence after ~200 tokens of a food review scored CLEAN 0.99 as one
  256-token window — the model learned from comments a few dozen tokens long. In 64-token pieces the
  same text holds at 0.96, and a 2,000-character clean review stays CLEAN 0.997.
- **Fails open to the rules.** The rules have run by then; an unreachable classifier is Phase 1, not
  every comment held. After a failure the callers stop asking for `moderation.text.classifier.cooldown`
  (30 s), so an outage costs one timeout (`timeout`, 1 s) per cooldown. The opposite of frames on
  purpose: frames have nothing else screening them.
- What it says becomes one flag from the `MODEL` source: `HATE:model:HATE:0.97` at severity 2 while
  `moderation.text.classifier.enforce` (env `TEXT_CLASSIFIER_ENFORCE`), severity 1 (a note, decides
  nothing) otherwise and whenever it saw offence below the threshold. Verdicts record
  `+<TEXT_MODERATION_MODEL_VERSION>` when it answered.

A sample through the built image, before any tuning (one comment ≈ 40 ms on CPU):

| Text | Verdict |
|---|---|
| `Video hay quá, cảm ơn bạn đã chia sẻ`, `con chó nhà tôi dễ thương quá`, `giết thời gian bằng cách lướt tiktok`, `người miền Bắc nói chuyện dễ thương ghê` | ALLOW, CLEAN ≥ 0.99 |
| `đẹp vãi cả lồn`, `game này khó vãi` | ALLOW, CLEAN ≥ 0.98 |
| `ủa sao ngu vậy trời` | ALLOW, a note: HATE 0.825 |
| `thằng này ngu như bò, đồ óc lợn`, `mày nên chết đi cho xã hội sạch sẽ`, `bọn bắc kỳ chó má cút về quê đi` | HOLD, HATE ≈ 0.97–0.98 |

Nine sentences prove the wiring, not the precision. Before enforcing on real traffic, run
`TEXT_CLASSIFIER_ENFORCE=false` for a while: the notes land on comments (`mod_reason`) and videos
(`textModeration.flags`), the admin lists show them, and `make moderation-score-texts FILE=…` measures
precision on a file of comments you have judged (`+ ` should hold, `- ` should not). Change the
threshold or the weights → bump `TEXT_MODERATION_MODEL_VERSION`. Licence: the fine-tune is MIT; its
base, `vinai/phobert-base-v2`, is AGPL-3.0 on its card — fine for this project, check before a
commercial deployment.

### Captions: where the two verdicts meet

```
video-service    screens at POST /videos ── rules severity 3 ──▶ 422, nothing stored
                 │  then the classifier, title + description + tags in one call
                 └─ video.textModeration, and VideoPublishedEvent.textVerdict (APPROVED | REVIEW)
media-worker     copies it onto VideoTranscodedEvent.textVerdict
media-worker     frames APPROVED + caption REVIEW ──▶ REVIEW  ("Caption held for review by text moderation")
                 ──▶ VideoModerationCompletedEvent, as before
```

The verdict is combined in media-worker, not in `Video.outcomeOf`, because video-service is not the
only reader of `VideoModerationCompletedEvent`: search-service and recommendation-service publish a
video on `APPROVED` themselves. Held only in video-service, the video would still be searchable and
among the For You candidates. Combined before the event exists, every reader — video-service's
status, search, recommendation, chat's frames, the media quarantine — holds it the same way with no
change of their own. A caption verdict never turns frames into `REJECTED`, and frames already at
`REVIEW` or `REJECTED` stay there. `MODERATION_ENABLED=false` on media-worker does not skip it: the
caption was judged elsewhere. `textModeration.enforced` is true when the rules enforce, or when the
classifier enforced a hold of its own while the rules were only measuring.

### Comments: held for the owner

A held comment is stored with `status = HELD` and nothing else: no `comment_index` row (so no
listing can show it), no counter, no `CommentCreatedEvent` (so chat never pushes it to a panel and
nobody is notified). Its author gets `201` with `status: HELD`. The owner reviews
`GET /videos/{id}/held-comments` and approves (`POST …/comments/{id}/approve`, which indexes,
counts and announces it exactly as a new comment) or deletes it. See `interaction-service-api.md`
§3.4b–§3.4c.

`CommentScreening` decides, in order: the rules (an enforced REJECT ends it); the owner's own comment
is never held; the owner's filters — `filterAllComments` (`CREATOR_FILTER:all`) and
`commentKeywords` (`CREATOR_FILTER:term:<keyword>`, matched like the word list); then the classifier,
only if nothing holds the comment yet. A flag holds at severity 2 and up: the rules' while
`moderation.text.enforce`, the model's and the owner's always. An owner with `filterSpamOffensive`
off waives `PROFANITY`, `INSULT` and `SPAM` on their videos — not hate, sexual content, threats,
scams or someone's phone number, which protect the people reading the thread, and never a REJECT.

The owner's filters live in user-service (`user_settings`, V15), reach video-service's `/policy`
through `/users/internal/settings` (cached as JSON, `user-settings:comment-policy:{id}`, a few seconds)
and arrive with the policy interaction-service already reads. Unknown — user-service down — they are
their defaults: spam and offence held, nothing else. See `docs/user-settings.md`.

Every comment with a flag keeps it (`mod_reason`, `mod_version`), held or posted: a link the owner
waived, the owner's own rude comment, a classifier note. `GET /api/v1/interactions/admin/videos/{id}/comments`
shows `moderationReason`; a posted comment with a reason is something a filter let through.

### Strikes and the commenting restriction

A comment refused by the rules (422) or removed by an admin (`CommentRemovedEvent`, counted only by
the call that deleted it) is a strike against its author. `interaction.comment.strikes.threshold` (3)
strikes within `window` (24h) restrict commenting for the next duration in `restrictions` (1h, 24h,
7d — the last repeats); the step goes back to the first after `step-window` (30d) without one, and
the strike count starts over at each restriction. Held comments are not strikes — a hold is a
question for the owner — and neither is anything the classifier said.

A restricted account gets `403 COMMENTING_RESTRICTED` with `Retry-After` on every comment, checked
before anything else; `GET /videos/{id}/comment-permission` answers `COMMENTING_RESTRICTED` with
`restrictedUntil`. Liking, deleting and reading are unaffected. The console reads and lifts it at
`GET` / `DELETE /api/v1/interactions/admin/users/{userId}/comment-restriction` (lifting clears the
strikes and the step too — for when a bad term restricted innocent accounts). Redis, and fails open
like the rate limits: Redis down means no strike and no restriction.

### A banned account's comments

interaction-service records `UserBannedEvent` / `UserUnbannedEvent` (already on
`admin.moderation-events`) in Cassandra `user_bans`, guarded by when admin-service decided — the way
video-service keeps `owner_bans`. Every listing — threads, replies, the owner's held queue — drops
comments whose author's `banned_until` is in the future, with one read for the page's authors.
Counters keep them: a ban that lapses (auth-service lifts temporary ones without an event) or is
lifted brings them back with nothing to recount, at the cost of a total that counts comments nobody
can see while the ban lasts. The admin list shows everything.

### Upload limits

Not text, but the same family: video-service limits `POST /videos/upload-url` (30/hour, 100/day) and
`POST /videos` (10/hour, 30/day) per account — `app.upload.rate-limit`, `429 UPLOAD_RATE_LIMITED`
with `Retry-After`. A publish refused for its caption spends budget too, so captions cannot be
probed at speed.

### Measuring before enforcing

`moderation.text.enforce=false` (env `TEXT_MODERATION_ENFORCE`, on both services) computes, logs and
counts every rules verdict and acts on none: nothing is held or refused, and videos carry no text
verdict to media-worker. `moderation.text.classifier.enforce=false` does the same for the model.
The owner's own filters always act. Every verdict, enforced or not, is counted:

```
text_moderation_decisions_total{surface="comment|video_title|video_description|video_tags",
                                decision="allow|hold|reject", category="SPAM|PROFANITY|…|NONE",
                                enforced="true|false"}
text_classifier_opinions_total{surface, label="clean|offensive|hate", verdict="allow|hold", enforced}
text_classifier_unavailable_total
```

Each flagged comment keeps `mod_reason` (every flag) and `mod_version`; each video keeps
`textModeration.flags` and `.version`. Bump the list's `version:` line on every change, for the same
reason as `MODERATION_MODEL_VERSION`.

```javascript
// videos held for their caption alone — the frames were fine
db.videos.find({ "textModeration.verdict": "REVIEW", "moderation.verdict": "REVIEW",
                 "moderation.reason": /^Caption held/ })
// what the classifier noted on captions while only being measured
db.videos.find({ "textModeration.flags": /model:HATE/ })
```

## Not in V1

- **Violence, weapons, gore.** Add a scorer to `SCORERS` in `app.py`; the severity ordering that
  combines several labels is already in `verdict.py`.
- **Audio and speech.** Nothing listens to the video.
- **Text, the rest of it.** Shadow holds (the author sees a held comment as posted), a per-video
  comment audience, the same text from many accounts, bios and display names, strikes from captions.
  The classifier is not a second opinion on frames, and Azure Content Safety is none for text:
  Vietnamese is not among its supported languages.
- **Mobile.** `tiktok_mobile` has not been told about the new statuses. If it parses status into a
  Dart enum, `PENDING_MODERATION` and `REJECTED` may throw rather than degrade.
