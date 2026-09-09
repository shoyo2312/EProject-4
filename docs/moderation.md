# Automatic Video Moderation

Every uploaded video is screened for NSFW imagery before anyone can see it. The check runs after
transcoding and before publication, so a video that fails it was never on a feed at any point.

Nothing here costs money: the classifier is an open-weight model running on CPU inside a
container this repo builds.

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
| `media.video-moderation-events` | media-worker | video-service (`video-service`) |

## Statuses

| Status | Meaning | Visible? |
|---|---|---|
| `PROCESSING` | uploaded, transcoding | no |
| `PENDING_MODERATION` | transcoded, waiting on a verdict | no |
| `PENDING_REVIEW` | the model was unsure, or could not run — an admin decides | no |
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
| media-worker cannot reach Kafka | the transcode event is redelivered and the check re-runs |

There is no path that leaves a video at `PENDING_MODERATION` with nothing to move it. A video
sitting there is a bug, and worth alerting on.

## Running it

```bash
make moderation-up      # build + start (first build downloads the weights, ~1.5 GB image)
make moderation-test    # the decision rule's tests — no model needed
```

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

## Not in V1

- **Violence, weapons, gore.** Add a scorer to `SCORERS` in `app.py`; the severity ordering that
  combines several labels is already in `verdict.py`.
- **Audio and speech.** Nothing listens to the video.
- **Text.** Titles and descriptions are not screened. A Vietnamese blocklist belongs at the
  `video-service` create path, before the upload is even transcoded, and should flag for review
  rather than reject — a blocklist has no notion of context.
- **Mobile.** `tiktok_mobile` has not been told about the new statuses. If it parses status into a
  Dart enum, `PENDING_MODERATION` and `REJECTED` may throw rather than degrade.
