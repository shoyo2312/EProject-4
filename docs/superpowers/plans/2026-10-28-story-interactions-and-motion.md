# Story Interactions and Motion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pause, reactions, motion and toasts for stories on the web, plus the story-service reaction endpoints.

**Architecture:** Part A is web-only and touches the four story components (`StoryViewer`, `StoryComposer`, `StoryRing`, `StoryTray`) plus the three places that mount the viewer. Part B adds a `reaction` field to the existing `story_views` document in story-service (two endpoints, one upsert, one aggregation) and a reaction bar in the viewer that calls them.

**Tech Stack:** Web: Next 16, React 19, Tailwind 4 + `tw-animate-css`, sonner, Vitest + Testing Library. Backend: Spring Boot 4, Spring Data Mongo (`MongoTemplate`), JUnit 5 + Mockito + AssertJ.

**Spec:** `docs/superpowers/specs/2026-10-28-story-interactions-and-motion-design.md`

**Repos:** Tasks 0–6 and 8 run in `/Users/minhhung/Projects/EProject/EProject4/tiktok-cloned`; Task 7 in `/Users/minhhung/Projects/EProject/EProject4/tiktok-backend`.

## Global Constraints

- Every animation is `motion-safe:` (Tailwind variant) or inside `@media (prefers-reduced-motion: no-preference)`.
- No new npm or Maven dependency.
- The `unseen` ring rotates only when `thin` is false. `thin` rings never animate.
- The shared `Modal` shell in `ui/modal.tsx` is not modified.
- No new `NotificationType`; reactions produce no notification.
- Reaction enum, both repos, exact spelling: `LIKE, LOVE, WOW, ANGRY, SAD`.
- Error code for an owner reacting to their own story: `400 STORY_OWN_REACTION`.
- Copy, verbatim: `Story posted — it'll go live after a quick review`, `Your story is live`, `Your story couldn't be shown`, action label `See why`, `Couldn't send your reaction`.
- Hold-to-pause threshold: 200ms. Posted check lingers 700ms. Viewer exit 200ms. Composer exit `MODAL_EXIT_MS` (300).
- Web commits go on branch `feat/story-polish` (Tasks 0–6) and `feat/story-reactions` (Task 8); backend on `feat/story-reactions`.
- Run `npm test`, `npm run typecheck`, `npm run lint` before each web commit; `./mvnw -q test -pl services/story-service` before each backend commit.

## Review Focus

1. A pointer that goes down on the media, is held, and is released outside the card (`pointerleave` fires, `pointerup` does not): playback must resume. Pinned in Task 2.
2. The owner's group holding two pending stories where one goes live first: exactly one "Your story is live" toast, none for the still-pending one. Pinned in Task 1.
3. A reaction tap while signed out (`ProfileStoryAvatar` shows PUBLIC stories to guests): the bar must not call the API; it opens login. Pinned in Task 8.
4. A reaction `PUT` that races the view `POST`: `viewCount` increments exactly once across both. Pinned in Task 7.
5. A story whose media is unavailable (`mediaUrl` null after `recover` gives up): no blurred backdrop image either. Pinned in Task 5.

---

## Part A — Web

### Task 0: Branch and the pre-existing avatar-menu work

The working tree on `feat/sign-in-alerts` holds uncommitted, tested work from an earlier session (`ProfileStoryAvatar` menu, `AvatarLightbox`, two tests). Commit it on its own first so later diffs are clean.

**Files:**
- Already modified/untracked: `src/components/stories/ProfileStoryAvatar.tsx`, `src/components/profile/AvatarLightbox.tsx`, `src/components/stories/__tests__/ProfileStoryAvatar.test.tsx`, `src/components/stories/__tests__/StoriesProvider.test.tsx`

- [ ] **Step 1: Branch** — `git checkout -b feat/story-polish`

- [ ] **Step 2: Verify green** — `npm test -- src/components/stories && npm run typecheck && npm run lint`

- [ ] **Step 3: Commit it alone**

```bash
git add src/components/stories/ProfileStoryAvatar.tsx src/components/profile/AvatarLightbox.tsx src/components/stories/__tests__/ProfileStoryAvatar.test.tsx src/components/stories/__tests__/StoriesProvider.test.tsx
git commit -m "feat(profile): avatar menu — view avatar, view story"
```

---

### Task 1: Toasts for posted and decided

**Files:**
- Modify: `src/components/stories/StoryComposer.tsx` (`post()`, after `onPosted(story)`)
- Modify: `src/components/stories/StoryTray.tsx` (`StoryBar`, the `wasAwaiting` effect)
- Create: `src/lib/stories/verdicts.ts`
- Test: `src/lib/stories/__tests__/verdicts.test.ts`, `src/components/stories/__tests__/StoryTray.test.tsx`

**Interfaces:**
- Produces: `decidedSince(previous: readonly StoryGroup[], current: readonly StoryGroup[], ownerId: string): { live: StoryResponse[]; hidden: StoryResponse[] }` — stories of `ownerId` that were `PENDING_MODERATION` in `previous` (matched by `id`) and are now `ACTIVE` (`live`) or `HELD`/`REJECTED` (`hidden`).

- [ ] **Step 1: Failing tests for `decidedSince`**

```ts
it("reports a story that went from pending to active, once, by id", () => {
  const before = [group("1", [pending("a"), pending("b")])];
  const after = [group("1", [active("a"), pending("b")])];
  expect(decidedSince(before, after, "1")).toEqual({ live: [active("a")], hidden: [] });
});
it("reports held and rejected as hidden", ...);            // a → HELD, b → REJECTED
it("ignores other owners and stories that were not pending before", ...);
```

- [ ] **Step 2: Run, expect FAIL** — `npx vitest run src/lib/stories/__tests__/verdicts.test.ts`
- [ ] **Step 3: Implement `decidedSince` in `src/lib/stories/verdicts.ts`.**
- [ ] **Step 4: Run, expect PASS.**

- [ ] **Step 5: Failing tests for the toasts** in `StoryTray.test.tsx`. Add `vi.mock("@/components/ui/toast", () => ({ toast: { success: vi.fn(), warning: vi.fn() } }))`. `mine(status)` there derives its id from the status; change it to the fixed id `"mine"` so before/after match (no existing test depends on the id).

```ts
it("toasts once when a pending story of yours goes live", () => {
  feed.groups = [mine("PENDING_MODERATION")];
  const { rerender } = render(<StoryBar followedIds={[]} />);
  feed.groups = [mine("ACTIVE")];
  rerender(<StoryBar followedIds={[]} />);
  expect(toast.success).toHaveBeenCalledWith("Your story is live");
  rerender(<StoryBar followedIds={[]} />);
  expect(toast.success).toHaveBeenCalledTimes(1);
});
it("toasts only for the story that went live when another is still pending", ...);   // Review Focus 2: two pending, one → ACTIVE, awaiting stays true → no toast yet; then second → HELD → one success already? No: the effect only runs on wasAwaiting→!awaiting, so expect: no toast after the first flip (still awaiting), then success + warning after the second. Assert exactly that.
it("toasts a warning with a 'See why' action when it is held", ...);
  // expect toast.warning("Your story couldn't be shown", expect.objectContaining({ action: expect.objectContaining({ label: "See why" }) }))
it("toasts success after the composer posts", ...);
  // mock StoryComposer to call onPosted on mount; click "Create a story"; expect toast.success("Story posted — it'll go live after a quick review")
```

- [ ] **Step 6: Run, expect FAIL.**

- [ ] **Step 7: Implement**
  - `StoryComposer.post()`: after `onPosted(story)`, `toast.success("Story posted — it'll go live after a quick review")`.
  - `StoryBar`: `previousGroups = useRef(groups)`. In the `wasAwaiting` effect, when `wasAwaiting.current && !awaiting`: `const { live, hidden } = decidedSince(previousGroups.current, groups, user.userId)`; `live.length > 0` → `toast.success("Your story is live")`; `hidden.length > 0` → `toast.warning("Your story couldn't be shown", { action: { label: "See why", onClick: () => setOpen({ group: ownIndex, story: indexOfStory(hidden[0].id) }) } })`. Set `previousGroups.current = groups` at the end of every run.

- [ ] **Step 8: Run tests + typecheck + lint, expect PASS.**
- [ ] **Step 9: Commit** — `feat(stories): toast when a story is posted and when review decides`

---

### Task 2: Pause and resume

**Files:**
- Modify: `src/components/stories/StoryViewer.tsx`
- Test: `src/components/stories/__tests__/StoryViewer.test.tsx`

**Interfaces:**
- Produces: a button `aria-label` `Pause` / `Play` with `aria-pressed`; the media area carries `data-story-media`; the pause glyph `data-paused-glyph`.

- [ ] **Step 1: Failing tests** (fake timers + `performance.now`, as the file's clock tests already do)

```ts
describe("pausing", () => {
  it("holds the clock while the pointer is held past 200ms and resumes on release");   // pointerDown [data-story-media], +250ms → progress frozen; pointerUp → moving
  it("still steps forward on a press shorter than 200ms");                             // pointerDown, +100ms, pointerUp, click next half → advanced
  it("does not step on the click that ends a hold");
  it("resumes when the pointer leaves the card mid-hold");                              // Review Focus 1
  it("toggles a sticky pause with the button and with Space");                          // click "Pause" → aria-pressed true + [data-paused-glyph]; key " " → resumed
  it("clears the sticky pause when stepping to the next story");
});
```

- [ ] **Step 2: Run, expect FAIL.**

- [ ] **Step 3: Implement**
  - State `userPaused`; refs `holdTimer`, `wasHold`.
  - `paused = (busyStory !== null && busyStory === storyId) || hidden || held || userPaused`.
  - Media container: `data-story-media`, `onPointerDown` (`wasHold=false`; timer 200ms → `setUserPaused(true); wasHold=true`), `onPointerUp`/`onPointerCancel`/`onPointerLeave` (clear timer; if `wasHold` → `setUserPaused(false)`), `onContextMenu` preventDefault, classes `touch-manipulation select-none`.
  - Half-buttons' `onClick`: `if (wasHold.current) return;` first.
  - Button beside mute: lucide `Pause`/`Play`, `aria-label={userPaused ? "Play" : "Pause"}`, `aria-pressed={userPaused}`.
  - Key handler: `" "` → `preventDefault(); setUserPaused(v => !v)` (same `TYPING` guard).
  - `useEffect(() => setUserPaused(false), [storyId])`.
  - Visuals when `userPaused`: header + caption `transition-opacity duration-200 opacity-40`; glyph `<div data-paused-glyph className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center"><span className="rounded-full bg-black/40 p-4 motion-safe:animate-[tt-badge-pop_300ms_ease-out]"><Play className="h-12 w-12 text-white" /></span></div>`.

- [ ] **Step 4: Run tests + typecheck + lint, expect PASS.**
- [ ] **Step 5: Commit** — `feat(stories): hold, button and Space pause the story`

---

### Task 3: Ring motion

**Files:**
- Modify: `src/components/stories/StoryRing.tsx`
- Modify: `src/app/globals.css` (next to `tt-badge-pop`)
- Modify: `src/components/stories/StoryTray.tsx` (`pending`, `pop`, hover)
- Modify: `src/components/stories/ProfileStoryAvatar.tsx` (`pending`)
- Test: `src/components/stories/__tests__/StoryTray.test.tsx`, `src/components/stories/__tests__/StoryAvatar.test.tsx`

**Interfaces:**
- Produces: `StoryRing` props `pending?: boolean`, `pop?: boolean` beside `state`, `thin`, `fill`, `className`. DOM contract: root span `data-ring={state}`, `data-pending` when pending, `data-pop` when pop; class `tt-ring-spin` iff `state === "unseen" && !thin`.
- CSS: `@keyframes tt-ring-spin` (rotate 0→360deg), `tt-ring-pop` (scale 1→1.18→1; `box-shadow 0 0 0 6px rgb(254 44 85 / .3)` → `0 0 0 10px transparent`), class `.tt-ring-spin` animating only under `prefers-reduced-motion: no-preference`.

- [ ] **Step 1: Failing tests**

```ts
// StoryTray.test.tsx
it("spins the ring of a bubble with unseen stories");                       // ringOf(bubble) contains "tt-ring-spin"
it("shows a pending ring on your bubble while your only story is in review"); // own ring has data-pending
it("pops your bubble after you post, then settles");                        // StoryBar: composer's onPosted → data-pop; +600ms → gone
// StoryAvatar.test.tsx
it("never spins a thin ring");                                              // unseen + thin → no "tt-ring-spin"
```

- [ ] **Step 2: Run, expect FAIL.**

- [ ] **Step 3: Implement**

```tsx
<span data-ring={state} data-pending={pending || undefined} data-pop={pop || undefined}
  className={cn("relative inline-flex shrink-0 rounded-full", padding, fill && "flex h-full w-full",
    pop && "motion-safe:animate-[tt-ring-pop_500ms_ease-out]", className)}>
  <span aria-hidden className={cn("absolute inset-0 rounded-full bg-white/30 transition-opacity duration-400", state === "none" && "opacity-0")} />
  <span aria-hidden className={cn("absolute inset-0 rounded-full transition-opacity duration-400",
    "bg-[conic-gradient(var(--tt-red),#ff2d55,#ff7a45,var(--tt-red))]",
    state === "unseen" ? "opacity-100" : "opacity-0", state === "unseen" && !thin && "tt-ring-spin")} />
  {pending && <span aria-hidden className="absolute inset-0 rounded-full border border-dashed border-white/40 motion-safe:animate-[tt-ring-spin_2s_linear_infinite]" />}
  <span className={cn("relative inline-flex rounded-full bg-[var(--tt-page)]", padding, fill && "h-full w-full")}>{children}</span>
</span>
```

`StoryTray`: own bubble `pending={awaiting}` (compute `awaitingReview([groups[ownIndex]], user.userId)` inside `StoryTray`), `pop={popping}`; `StoryBar` holds `popping`, set true for 600ms after the composer's `onPosted` and when Task 1's `live.length > 0`. Every bubble button: `motion-safe:transition-transform duration-150 hover:scale-105`.
`ProfileStoryAvatar`: `pending={own && state !== "unseen" && stories.some(s => s.status === "PENDING_MODERATION")}`.

- [ ] **Step 4: Run tests + typecheck + lint, expect PASS.** If an existing tray test asserts the literal class `bg-[var(--tt-red)]`, switch it to `data-ring="unseen"`.
- [ ] **Step 5: Commit** — `feat(stories): rings spin while unseen, dash while pending, pop after a post`

---

### Task 4: Composer motion and posted state

**Files:**
- Modify: `src/components/stories/StoryComposer.tsx`
- Modify: every mount site of `<StoryComposer` (grep; `StoryBar` in `StoryTray.tsx` is one)
- Create: `src/components/stories/__tests__/StoryComposer.test.tsx`

**Interfaces:**
- Produces: `StoryComposer` prop `closing: boolean`. Renders its own portal, overlay and card with `modalOverlayMotion(closing)` / `modalCardMotion(closing)` following `AvatarLightbox`'s structure (not `Modal`). Posted state: element `data-posted` with text `Posted`.
- Consumes: `usePresence` (`@/hooks/use-presence`), `MODAL_EXIT_MS`, `modalCardMotion`, `modalOverlayMotion` (`@/components/ui/modal`).

- [ ] **Step 1: Failing tests** (mock `@/lib/api/stories`, `@/lib/api/videos`, `@/components/ui/toast`, `@/components/stories/StoriesProvider`)

```ts
it("shows the posted check and calls onPosted at once, then asks to close after 700ms");  // choose image, click Post → onPosted called, getByText("Posted"); +700ms → onClose called
it("is inert with the card shrinking while closing");                                     // closing → dialog inert, card class contains "scale-10"
it("dims the preview while posting");                                                     // opacity-60 while progress !== null
```

- [ ] **Step 2: Run, expect FAIL.**

- [ ] **Step 3: Implement**
  - Replace `<Modal>` with `createPortal(<div role="dialog" aria-modal inert={closing||undefined} className={cn("fixed inset-0 z-[3001] flex items-center justify-center", modalOverlayMotion(closing))}><button aria-label="Close" className="absolute inset-0 bg-black/[0.85]" onClick={posting ? undefined : onClose}/><div className={cn("relative w-[420px] rounded-[12px] p-6", /* same bg as Modal's card */, modalCardMotion(closing))}>…</div></div>, document.body)`. `useEscapeKey(onClose, !closing && !posting)`; `useMounted()` guard as `AvatarLightbox`.
  - Placeholder: `border border-dashed border-white/15`. Media wrapper: `key={preview}` + `motion-safe:animate-in motion-safe:fade-in motion-safe:zoom-in-95 duration-300`; `posting && "opacity-60"` with `transition-opacity`.
  - Post button: `key={String(Boolean(file) && checked)}` + `motion-safe:animate-[tt-badge-pop_300ms_ease-out]`.
  - State `posted`. After `createStory`: `invalidateOwnRing(); onPosted(story); setPosted(true); window.setTimeout(onClose, 700)`. When `posted`, the card shows `<div data-posted className="flex flex-col items-center gap-3 py-10"><span className="flex h-16 w-16 items-center justify-center rounded-full bg-[var(--tt-red)] motion-safe:animate-[tt-badge-pop_300ms_ease-out]"><Check className="h-8 w-8 text-white" /></span><p className="text-[17px] font-semibold text-[var(--tt-text)]">Posted</p></div>` instead of the form.
  - Mount sites: `const composer = usePresence(composing, MODAL_EXIT_MS)`; `composer.mounted && <StoryComposer closing={composer.closing} onClose={() => setComposing(false)} onPosted={refreshEtc} />`. `onPosted` no longer closes; `onClose` does, 700ms later.

- [ ] **Step 4: Run tests + typecheck + lint, expect PASS.**
- [ ] **Step 5: Commit** — `feat(stories): composer fades in, shows a posted check, fades out`

---

### Task 5: Blurred backdrop

**Files:**
- Modify: `src/components/stories/StoryViewer.tsx`
- Test: `src/components/stories/__tests__/StoryViewer.test.tsx`

- [ ] **Step 1: Failing tests**

```ts
it("blurs the image behind itself");                            // IMAGE → img[aria-hidden][data-backdrop] with the story's src
it("draws a gradient, not a second video, behind a video");     // VIDEO → [data-backdrop] not an img; exactly one <video>
it("draws no backdrop image once the media is unavailable");    // Review Focus 5: error twice → no img[data-backdrop]
```

- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** First child of the media container: IMAGE with `mediaUrl` → `<img aria-hidden data-backdrop src={mediaUrl} alt="" className="pointer-events-none absolute inset-0 h-full w-full scale-110 object-cover opacity-60 blur-2xl" />`; VIDEO → `<div aria-hidden data-backdrop className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,#2a2a2a,#111)]" />`; `!mediaUrl` → nothing. Real media gets `relative`.
- [ ] **Step 4: Run tests + typecheck + lint, expect PASS.**
- [ ] **Step 5: Commit** — `feat(stories): blurred backdrop behind letterboxed media`

---

### Task 6: Viewer open and close motion

**Files:**
- Modify: `src/components/stories/StoryViewer.tsx` (`closing?: boolean`, `export const VIEWER_EXIT_MS = 200`)
- Modify: `src/components/stories/StoryTray.tsx`, `src/components/stories/ProfileStoryAvatar.tsx`, `src/components/stories/StoriesProvider.tsx`
- Test: `src/components/stories/__tests__/StoryViewer.test.tsx`, `src/components/stories/__tests__/StoryTray.test.tsx`

**Interfaces:**
- Produces: `StoryViewer` prop `closing?: boolean`. While closing: dialog `inert`, card `scale-[0.92] opacity-0`, Escape and arrows off, clock paused.

- [ ] **Step 1: Failing tests**

```ts
// StoryViewer.test.tsx
it("is inert and shrinking while closing, and the clock stops");
// StoryTray.test.tsx
it("keeps the player mounted for 200ms after it closes");   // open bubble, click Close → dialog present; +200ms → gone
```

- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement**
  - `StoryViewer`: root `inert={closing || undefined}` + `motion-safe:transition-opacity duration-300 starting:opacity-0` + `closing && "opacity-0"`; card `motion-safe:transition-[transform,opacity] duration-200 ease-out starting:scale-[0.92] starting:opacity-0` + `closing && "scale-[0.92] opacity-0"`. `paused` also when `closing`. `useEscapeKey(onClose, !closing)`; key handler returns when `closing`.
  - `StoryBar`: `player = usePresence(open !== null, VIEWER_EXIT_MS)`; `lastOpen` ref keeps the last non-null position; render `player.mounted && lastOpen.current && <StoryViewer closing={player.closing} start={lastOpen.current} … />`.
  - `ProfileStoryAvatar` (`open`) and `StoriesProvider` (`viewing`, kept in a ref for the closing frame): same pattern.
- [ ] **Step 4: Run tests + typecheck + lint, expect PASS.**
- [ ] **Step 5: Commit** — `feat(stories): the player fades and scales in and out`
- [ ] **Step 6: Manual check in Chrome** at `http://localhost:3000/following`, signed in: ring spins, hold pauses, posting shows the check and the toast, landscape media has a blurred backdrop, player fades in/out. Fix what looks wrong before Part B.

---

## Part B — Reactions

### Task 7: story-service reaction endpoints

**Files:**
- Create: `entity/StoryReaction.java` (enum `LIKE, LOVE, WOW, ANGRY, SAD`)
- Modify: `entity/StoryView.java` (`StoryReaction reaction`, `Instant reactedAt`)
- Create: `repository/StoryViewRepositoryCustom.java`, `repository/StoryViewRepositoryImpl.java`
- Modify: `repository/StoryViewRepository.java` (extends the custom interface)
- Create: `exception/OwnStoryReactionException.java` (`extends BadRequestException`, code `STORY_OWN_REACTION`, message `You can't react to your own story`)
- Create: `dto/request/ReactToStoryRequest.java` (`record ReactToStoryRequest(@NotNull StoryReaction reaction)`)
- Modify: `dto/response/StoryResponse.java` (`StoryReaction myReaction`, `Map<StoryReaction, Long> reactionCounts`; `forViewer()` keeps `myReaction`, nulls `reactionCounts`)
- Modify: `dto/response/StoryViewerResponse.java` (`StoryReaction reaction`)
- Modify: `mapper/StoryMapper.java` (`toResponse(story, mediaUrl, viewedByMe, myReaction, reactionCounts)`)
- Modify: `service/StoryService.java`, `service/StoryServiceImpl.java` (`react`, `unreact`; `seenAmong` becomes `viewsAmong(viewerId, stories): Map<String, StoryView>`; `ownerView` adds counts; `updateVisibility`'s manual copy carries the two new fields)
- Modify: `controller/StoryController.java` (`PUT` / `DELETE /{storyId}/reaction`, both `204`)
- Modify: `docs/story-service-api.md` §4
- Test: `src/test/java/com/tiktok/storyservice/service/StoryServiceImplTest.java`

All paths under `services/story-service/src/main/java/com/tiktok/storyservice/` unless stated.

**Interfaces:**
- `StoryViewRepositoryCustom`:
  - `boolean upsertReaction(String storyId, Long viewerId, StoryReaction reaction, Instant expiresAt, Instant now)` — `mongoTemplate.upsert(query(storyId, viewerId), new Update().set("reaction", reaction).set("reactedAt", now).setOnInsert("viewedAt", now).setOnInsert("expiresAt", expiresAt), StoryView.class)`; returns `getUpsertedId() != null`.
  - `void clearReaction(String storyId, Long viewerId)` — `$unset reaction, reactedAt`.
  - `Map<StoryReaction, Long> countReactions(String storyId)` — aggregation `match(storyId, reaction ne null)` → `group("reaction").count().as("n")` → map; empty when none.
- `StoryService`: `void react(Long viewerId, String storyId, StoryReaction reaction)`, `void unreact(Long viewerId, String storyId)`.

- [ ] **Step 1: Failing tests** (mock style of the `recordView` tests, lines ~416–460)

```java
@Test void react_ownerReactingToOwnStory_isBadRequest();        // OwnStoryReactionException; verify no upsertReaction
@Test void react_firstTouchInsertsTheViewAndCountsIt();         // upsertReaction → true; verify incrementViewCount("s1") once   (Review Focus 4)
@Test void react_afterAViewDoesNotCountAgain();                 // upsertReaction → false; never incrementViewCount
@Test void react_onAStoryTheViewerMayNotSee_isNotFound();       // StoryNotFoundException
@Test void unreact_isIdempotent();                              // twice → clearReaction twice, no throw
@Test void getById_asViewer_carriesMyReactionAndNoCounts();     // viewsAmong row with LOVE → myReaction()==LOVE, reactionCounts()==null
@Test void getById_asOwner_carriesCountsAndNoMyReaction();      // countReactions → {LOVE=2}
@Test void listViewers_carriesEachViewersReaction();
```

- [ ] **Step 2: Run, expect compile failure** — `./mvnw -q test -pl services/story-service -Dtest=StoryServiceImplTest`
- [ ] **Step 3: Implement.** `react`: `Story story = findVisibleTo(viewerId, storyId); if (isOwner(viewerId, story)) throw new OwnStoryReactionException(); if (storyViewRepository.upsertReaction(storyId, viewerId, reaction, story.getExpiresAt(), Instant.now())) storyRepository.incrementViewCount(storyId);`. `unreact`: `findVisibleTo`; owner → return; else `clearReaction`.
- [ ] **Step 4: Run the module** — `./mvnw -q test -pl services/story-service` — PASS.
- [ ] **Step 5: Docs** — `docs/story-service-api.md` §4: rows `PUT /{storyId}/reaction | cần | {reaction: LIKE\|LOVE\|WOW\|ANGRY\|SAD} | 204; chủ story → 400 STORY_OWN_REACTION; phản ứng trước khi view được ghi thì tạo luôn view` and `DELETE /{storyId}/reaction | cần | — | 204, idempotent`. `StoryResponse` gains `myReaction` (null với chủ), `reactionCounts` (chỉ chủ). Viewers row: `[{viewerId, viewedAt, reaction}]`.
- [ ] **Step 6: Commit** on `feat/story-reactions` (branched from `dev`):

```bash
git checkout -b feat/story-reactions dev
git add services/story-service docs/story-service-api.md
git commit -m "feat(story): react to a story with one of five emotions"
```

---

### Task 8: Reaction bar on the web

**Files:**
- Modify: `src/lib/api/types.ts` (`StoryReaction` union; `StoryResponse.myReaction: StoryReaction | null`, `reactionCounts: Partial<Record<StoryReaction, number>> | null`; `StoryViewer.reaction: StoryReaction | null`)
- Modify: `src/lib/api/stories.ts` (`setStoryReaction(storyId, reaction): Promise<void>` → `PUT /stories/{id}/reaction`, `clearStoryReaction(storyId): Promise<void>` → `DELETE`)
- Create: `src/lib/stories/reactions.ts` (`REACTIONS: readonly { type: StoryReaction; emoji: string; label: string }[]` — LIKE 👍 Like, LOVE ❤️ Love, WOW 😮 Wow, ANGRY 😡 Angry, SAD 😢 Sad; `emojiOf(type)`)
- Create: `src/components/stories/ReactionBar.tsx`
- Modify: `src/components/stories/StoryViewer.tsx` (bar for others' stories; overlay `reactions: Record<string, StoryReaction | null>`)
- Modify: `src/components/stories/StoryOwnerPanel.tsx` (chips; reaction beside each viewer)
- Modify: `src/app/globals.css` (`@keyframes tt-reaction-float`: 0% `translate:0 0; scale:.6; opacity:0` → 15% `opacity:1; scale:1.1` → 100% `translate: var(--drift) -140px; scale:.9; opacity:0`)
- Test: `src/components/stories/__tests__/ReactionBar.test.tsx`, `src/components/stories/__tests__/StoryViewer.test.tsx`
- Fixtures: every `StoryResponse` literal in `StoryViewer.test`, `StoryTray.test`, `ProfileStoryAvatar.test`, `StoriesProvider.test` gains `myReaction: null, reactionCounts: null`.

**Interfaces:**
- `ReactionBar({ value, onChange, signedIn, onRequireLogin }: { value: StoryReaction | null; onChange: (next: StoryReaction | null) => void; signedIn: boolean; onRequireLogin: () => void })` — five buttons `aria-label={label}` `aria-pressed={value === type}`; click → `onChange(value === type ? null : type)`, or `onRequireLogin()` and nothing else when `!signedIn`; spawns 4–6 `<span aria-hidden data-float style={{ "--drift": …px }}>` (drift in [-24, 24]) removed after 900ms; `onPointerDown` and `onClick` on the bar root `stopPropagation()`.
- `StoryViewer` owns the request: optimistic overlay, then `setStoryReaction` / `clearStoryReaction`; on rejection restore and `toast.error("Couldn't send your reaction")`. Signed-in from `useSession().user !== null`; login opener: grep the app for how `AuthModal` is opened (e.g. `openLogin`) and reuse it.

- [ ] **Step 1: Failing tests**

```ts
// ReactionBar.test.tsx
it("renders five reactions in order and marks the chosen one pressed");
it("clears when the chosen one is tapped again and replaces when another is");
it("asks to sign in instead of reacting when signed out");          // Review Focus 3
it("does not let a tap reach the story's step buttons");            // wrapping onClick spy not called
it("floats copies of the emoji that disappear after 900ms");        // [data-float] count > 0, then 0
// StoryViewer.test.tsx
it("sends the reaction after showing it, and reverts when the request fails");  // setStoryReaction rejects → aria-pressed false again, toast.error called
it("shows no reaction bar under your own story");
```

- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** Bar in `StoryViewer`: inside the media container, `absolute inset-x-0 bottom-0 z-20 flex justify-center pb-4`; caption `pb-16` when the bar shows. Bar: `flex gap-2 rounded-full bg-white/10 px-3 py-1.5 backdrop-blur-sm`; button `relative rounded-full text-[28px] leading-none motion-safe:transition-transform duration-150 hover:scale-110 aria-pressed:scale-125 aria-pressed:ring-2 aria-pressed:ring-white`; float span `pointer-events-none absolute left-1/2 top-0 -translate-x-1/2 text-[24px] motion-safe:animate-[tt-reaction-float_900ms_ease-out_forwards]`. `StoryOwnerPanel`: chips `rounded-full bg-white/10 px-2 py-0.5 text-[13px]` for `reactionCounts` entries `> 0`, in `REACTIONS` order; viewer row shows `emojiOf(viewer.reaction)` before the time.
- [ ] **Step 4: Run tests + typecheck + lint, expect PASS.**
- [ ] **Step 5: Commit** on web `feat/story-reactions` — `feat(stories): react to a story; owners see who reacted how`
- [ ] **Step 6: Manual check in Chrome** with Task 7 running locally: react, change, clear, then as the owner see the chip and the viewer row.
