# Story interactions and motion — design

**Date:** 2026-10-28
**Repos:** `tiktok-cloned` (web, most of the work), `tiktok-backend/services/story-service` (reactions only)
**Builds on:** `2026-10-22-story-rings-and-notifications-design.md`

## Intent

The story player works but feels inert: nothing to do while watching, no way to hold a frame, no
feedback when posting, rings that snap between two flat colours. This pass gives the viewer a pause,
a reaction bar, motion on the composer, the player and the rings, and a toast for the two moments a
poster cares about (posted; live). Reactions are the only part that needs the backend.

## Goals

1. Pause/resume a story without leaving it: press-and-hold, a button, and the space bar.
2. React to someone else's story with one of five emotions; the owner sees who reacted how.
3. Motion that reads as the product's, not a library's: composer in/out, posted state, viewer open/close,
   reaction fly-up, ring transitions. Every animation respects `prefers-reduced-motion`.
4. A toast when the story is posted, and another when moderation decides.
5. Landscape media no longer floats in a black box.

## Non-goals

- Replying to a story by message. Notifications for reactions (see Decisions).
- Reaction counts for anyone but the owner.
- Mobile app. Changing moderation, visibility or expiry rules.

## Anti-goals

- A new `NotificationType` (the mobile inbox renders an error screen for unknown types).
- Animations that delay the thing the user asked for (opening a story, posting) by more than ~300ms.
- Reactions that outlive the story, or that count for an owner on their own story.

## Order of delivery

Frontend first, one PR: toasts → pause → rings → composer motion → blurred backdrop → viewer open/close.
Reactions last: backend, then web, each its own PR.

---

## Part A — Web (`tiktok-cloned`)

Existing pieces to reuse, not rewrite: `modalOverlayMotion` / `modalCardMotion` / `MODAL_EXIT_MS` from
`ui/modal.tsx` (the plain `Modal` shell itself has no motion; `AvatarLightbox` shows how a dialog applies
the helpers on its own), `usePresence`, `tw-animate-css` (`animate-in`, `fade-in`, `zoom-in-95`…), keyframes
`tt-heart-pop` / `tt-badge-pop` in `globals.css`, `toast` from `@/components/ui/toast` (sonner, mounted
in the root layout), `usePlaybackHeld`, the `paused` expression in `StoryViewer`.

### A1. Toasts

- After `StoryComposer.onPosted`: `toast.success("Story posted — it'll go live after a quick review")`.
  The composer still closes and the tray refreshes as today.
- Verdict: `StoryBar` already tracks `wasAwaiting → !awaiting`. At that transition compare the owner's
  group with the previous one (kept in a ref) by story id: a story that was `PENDING_MODERATION` and is
  now `ACTIVE` → `toast.success("Your story is live")`; now `HELD` / `REJECTED` →
  `toast.warning("Your story couldn't be shown", { action: { label: "See why", onClick: open own group } })`.
  Comparing by id keeps a second pending story from retriggering for the first.
- Only the tray polls, so a poster who leaves the Following page before the verdict gets no toast. Accepted.

### A2. Pause / resume

In `StoryViewer`:

- New state `userPaused`; `paused = busy || hidden || held || userPaused`. The clock already resumes
  from `progress` when `paused` flips back, and the `<video>` effect already pauses/plays.
- **Hold**: `onPointerDown` on the media area starts a 200ms timer; when it fires, `userPaused` is set
  and the gesture is flagged as a hold. `onPointerUp` / `onPointerCancel` / `onPointerLeave` clear the
  timer and `userPaused`. The prev/next click handlers ignore a click that ends a hold (`click` fires
  after `pointerup`; the flag resets on the next `pointerdown`). `touch-action: manipulation` and
  `onContextMenu` preventDefault on the area, so a long press neither scrolls nor opens a menu.
- **Button**: ⏸ / ▶ next to the mute button toggles a sticky `userPaused`; `aria-pressed`.
- **Keyboard**: space toggles the sticky pause (same `TYPING` guard as the arrow keys).
- Stepping to another story clears `userPaused`.
- Visuals while paused: header and caption fade to 40% over 200ms; a centred ▶ (lucide `Play`, 48px,
  white, in a black/40 disc) pops in with `tt-badge-pop` and stays while paused.

### A3. Rings

`StoryRing` keeps its API (`state`, `thin`, `fill`, `className`) and gains `pending?` and `pop?`.
Internally it becomes a span with two absolutely-positioned ring layers under the avatar layer.

- `unseen`: a conic gradient (`--tt-red` → `#ff2d55` → `#ff7a45` → `--tt-red`) layer that rotates 360°
  over 3s, linear, infinite. Only that layer rotates. `motion-safe:` only; without motion it is static.
  **Rotation only on full-size rings** (the tray bubbles and the profile header): `thin` rings — the
  side-nav lists, comment rows, activity list, explore grid, which stay mounted all session — keep the
  gradient but never animate. Dozens of continuously composited 1px rings is cost and noise for nothing.
- `seen`: the gradient layer fades out over 400ms (`opacity` transition) over a grey layer, so the colour
  change `syncRings` triggers no longer snaps.
- `pending`: grey ring plus a dashed white/40 stroke rotating over 2s. Reads as "working", no text.
  `StoryTray` knows this from `awaitingReview`; `ProfileStoryAvatar` from the stories' `status`.
- `pop`: one-shot `scale 1 → 1.18 → 1` with a fading glow (`box-shadow 0 0 0 6px` red/30), 500ms.
  The tray's own bubble fires it after a post and when a pending story goes live (the same transition
  as the toast).
- Hover on any bubble that opens stories: `scale 1.05`, 150ms.

### A4. Composer motion and posted state

In `StoryComposer`:

- Placeholder: dashed `border-white/15` box with icon + text; choosing a file cross-fades to the media
  (`animate-in fade-in zoom-in-95 duration-300`). The Post button plays `tt-badge-pop` once when it
  becomes enabled.
- Posting: progress bar as today; the preview dims to 60%.
- Open/close: the composer applies `modalOverlayMotion` / `modalCardMotion` itself (as `AvatarLightbox`
  does) and its mounting sites keep it with `usePresence(composing, MODAL_EXIT_MS)`. The shared `Modal`
  shell stays as it is: giving it motion would restyle ten unrelated dialogs.
- Posted: the form is replaced inside the same card by a check (lucide `Check`, 56px, in a red disc,
  `tt-badge-pop`) and "Posted" for 700ms, then the card runs that exit. `onPosted` fires immediately
  (ring invalidation, tray refresh, toast); only the card lingers.
  "Fly to the bubble" is dropped: the composer also opens where no bubble is on screen.

### A5. Blurred backdrop

Behind the media in `StoryViewer`:

- IMAGE: `<img aria-hidden src={mediaUrl}>` with `object-cover scale-110 blur-2xl opacity-60`, under the
  real image. Same URL, so no extra request.
- VIDEO: a second decoding `<video>` is too costly; use `bg-[radial-gradient(ellipse_at_center,#2a2a2a,#111)]`.

### A6. Viewer open / close

The dark layer fades in over 300ms; the 9:16 card scales from `0.92` with `fade-in`. Closing: the three
mounting sites (`StoryBar`, `ProfileStoryAvatar`, `StoriesProvider`) wrap the unmount with
`usePresence(open, 200)` and pass `closing` down, so the card scales back to `0.92` and fades.
Open-from-avatar is dropped: three callers, three ways to find the avatar's rect, for ~200ms of effect.

---

## Part B — Reactions

### B1. story-service

- The reaction lives on the existing `StoryView` document: one row per (storyId, viewerId) already
  exists, already expires with the story, and the viewers list already reads it. New fields
  `reaction: StoryReaction` (enum `LIKE, LOVE, WOW, ANGRY, SAD`) and `reactedAt: Instant`, null until
  the viewer reacts.
- `PUT /api/v1/stories/{storyId}/reaction`, body `{reaction}` → `204`. The story must be visible to the
  caller (`findVisibleTo`), so the audience rules are the view rules. The owner is refused:
  `OwnStoryReactionException` → `400 STORY_OWN_REACTION`.
  Write: one upsert on `(storyId, viewerId)` setting `reaction`, `reactedAt`, with `$setOnInsert`
  `viewedAt`, `expiresAt`. A reaction that arrives before the view was counted (race, or a client that
  reacts first) creates the view row too, and `viewCount` is incremented iff the upsert inserted
  (`UpdateResult.getUpsertedId() != null`). No read-then-write.
- `DELETE /api/v1/stories/{storyId}/reaction` → `204`, `$unset reaction, reactedAt`. Idempotent.
- `StoryResponse` gains `myReaction` (nullable; the owner's is always null) and, owner only,
  `reactionCounts: Map<StoryReaction, Long>` (null for viewers, like `viewCount`). `myReaction` comes
  from the `seenAmong` lookup that already loads the viewer's `StoryView` rows; `reactionCounts` from
  one aggregation on `story_views` grouped by `reaction` (`StoryViewRepositoryCustom.countReactions`),
  run only in `ownerView`.
- `StoryViewerResponse` gains `reaction` (nullable). Order unchanged (by `viewedAt`).
- Security: both routes fall under `anyRequest().authenticated()`. Gateway already routes
  `/api/v1/stories/**`.
- Docs: `docs/story-service-api.md` §4 gets the two rows and the new fields. `StoryServiceImplTest`:
  owner refused; upsert inserts the view and increments the count once; a repeat reaction does not
  increment; delete is idempotent; counts appear only in the owner view.

### B2. Web

- `lib/api/stories.ts`: `setStoryReaction(id, reaction)`, `clearStoryReaction(id)`; types updated.
- `StoryViewer`, someone else's story: a bar at the bottom of the card over the caption gradient, five
  buttons 👍 ❤️ 😮 😡 😢 (native emoji, 28px, in a `bg-white/10` pill). The chosen one is scaled 1.2
  with a white ring; tapping it again clears, tapping another replaces. Optimistic update of
  `myReaction` in the viewer's local copy, request after, revert + `toast.error` on failure.
  A tap also spawns 4–6 copies of the emoji rising from the button over 900ms with random x-drift and
  fade (`tt-reaction-float`, `motion-safe:`). The bar sits above the prev/next halves (`z-20`) and
  stops propagation, so a tap never steps the story.
- Own story: `StoryOwnerPanel` shows, next to the view count, a chip per non-zero reaction (`❤️ 12`),
  and the viewers list shows each viewer's reaction after their name.
- `markSeen` and the rings are untouched by reactions.

## Decisions

- **No notification for reactions.** A new type breaks the mobile inbox; reusing `LIKE` points the tap
  at a video. Owners see reactions in their viewers list. Revisit when the apps can render a story reference.
- **Reaction on `StoryView`, not a new collection.** Same key, same lifetime, same index.
- **Hold-to-pause at 200ms**; a shorter press still steps. Space and the button are sticky.
- **Dropped**: fly-to-bubble on post; open-from-avatar on the viewer. Cost out of proportion to the effect.
- **Builder's calls after review**: the composer owns its motion instead of the shared `Modal` shell
  gaining some; the unseen ring rotates only at full size, never on `thin` rings.

## Left to the builder

- Exact gradient stops and easing for the ring; keep the red dominant.
- Where the new keyframes go in `globals.css` (next to `tt-heart-pop`).
- Tests: Vitest + Testing Library like the existing story tests; one test per behaviour above, not per
  component.
