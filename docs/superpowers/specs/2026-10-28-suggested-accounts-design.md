# Suggested accounts on a profile — design

Date: 2026-10-28 · Status: draft, awaiting approval
Spans both repos: user-service here, the profile page in `tiktok-cloned`.

## 1. Why

The add-friend button beside **Message** on someone else's profile does nothing
(`tiktok-cloned/src/components/profile/ProfileHeader.tsx:172` only calls `requireSignIn`). On
tiktok.com that button opens a row of "Suggested accounts" under the bio. We want the same row, built
from people the viewer may actually know.

There is no "suggest users" endpoint anywhere today. The `/following` empty state and the Suggested
tab of the Followers dialog both fall back to "creators who posted to the public feed recently"
(`useSuggestedCreators`). That list has nothing to do with the viewer, and the dialog's copy doesn't
even leave out the viewer or the accounts they already follow.

## 2. Decisions

Decided by the user:

- **Copy TikTok's panel 1:1**, open/close animation and component sizes included (§3 holds the
  measurements).
- **"People you may know" from the real follow graph**:
  1. people who follow the viewer and whom the viewer does not follow back;
  2. people followed by the accounts the viewer follows.

  No more random creators from the feed.
- **Same list on every profile.** It depends on the viewer, not on whose profile is open.

Proposed in the conversation and not objected to (flag if wrong):

- Never suggested:
  - the viewer;
  - anyone the viewer follows or has a pending follow request to;
  - anyone across a block, in either direction;
  - a closed (deactivated) or soft-deleted account;
  - the owner of the profile being viewed.
- Order: tier 1 (follows you) first, then tier 2 by number of mutual connections, most first.
- The line under each name gives the real reason: **"Follows you"** / **"Followed by {name}"**.
  TikTok's web shows a generic "People you may know"; we have the real reason, so we show it.
- **View all** opens the existing Followers dialog on its **Suggested** tab, and that tab switches to
  the same source so the two places agree. `/following` and `/friends` stay as they are.
- Private accounts can be suggested. Their Follow turns into "Requested", as everywhere else.
- A signed-out visitor pressing the button gets the login modal, as today.
- Known gap: **banned accounts are not filtered.** user-service holds no ban data at all, and the
  followers/following lists don't filter bans either. See §7.

My calls while writing this (check these):

- **Top-up from the most-followed accounts in user-service**, reason `POPULAR`, label **"Suggested
  for you"**. This applies when the graph gives fewer than the limit, a new account included. In the
  conversation I said the fallback would be the feed creators; I changed it because user-service can
  apply every exclusion above in the same query. The feed-derived list can't know who the viewer
  follows, and the one thing this row must never do is offer Follow on someone already followed.
- The row is **topped up to 20**, not only filled when the graph is empty.
- Fetched **on first open**, not on page load. Most profile views never open it.
- Following someone from a card keeps the card in place and flips its button. No card is removed or
  replaced.
- Card avatars get the story ring (`StoryAvatar`), like every other avatar since the story-rings work.
- The toggle carries `aria-expanded`/`aria-controls`. The collapsed panel is `inert`, because it stays
  mounted and its links and buttons would otherwise be tabbable at zero height.
- The tooltip copy is ours (§5.5). It describes how our suggestions are actually picked.
- Accounts the viewer **muted** are left out too. Mute means "keep this account out of my feed", and
  suggesting it would contradict that.
- The panel starts closed on every profile. Moving from one profile to another closes it.

## 3. What TikTok does

Measured on 2026-10-28 on `tiktok.com/@tiktok`:

- Viewport 1920×936, DPR 1, signed in.
- Rects come from `getBoundingClientRect`.
- Animations were read from `document.getAnimations()` while clicking.

### Behaviour

- The button (`data-e2e="show-suggested-accounts"`, `aria-label="Suggested accounts"`) is a toggle.
  It looks the same in both states: no pressed style, fill stays `rgba(255,255,255,.13)`.
- The panel sits **between the header and the tab bar**.
- While the panel is open, the header's `margin-bottom` goes 20px → 12px. The change is instant, not
  animated.
- Closing does not unmount the panel. It stays in the DOM at `max-height: 0; opacity: 0`.
- **View all** opens the Followers/Following dialog with its **Suggested** tab selected.
- Hovering the "Suggested accounts" label or its ⓘ shows a tooltip explaining where suggestions come
  from.
- Cards have **no dismiss ✕**.

### Open/close animation

The wrapper has `overflow: hidden` and two transitions on it:

| | closed | open | transition |
|---|---|---|---|
| `max-height` | `0` | `300px` | `400ms ease` |
| `opacity` | `0` | `1` | `400ms ease` |

The animation is symmetric. The content is 282px tall, so opening reaches full height before 400ms,
and closing visibly starts ~30ms late (the 300→282 part is invisible). Keep the 300px value: it is
what produces that feel.

**`ease` is CSS's `cubic-bezier(.25,.1,.25,1)`, not Tailwind's default curve.** Use `ease-[ease]`.

### Layout

The panel is full width of the content column (1296 at 1920). Its height is 282 = title row 40 +
card row 227 + bottom spacer 15.

```
DivUserCardListContainer         position: relative; width: 100%; min-height: 265px
├ title row                      height 40; flex; space-between; align-items center;
│                                padding-left 14; padding-bottom 8; z-index 10
│ ├ label group                  flex; align-items center; gap 8; cursor pointer
│ │ ├ "Suggested accounts"       14px / 18.2 / 700, rgba(255,255,255,.6)
│ │ └ ⓘ                          14×14, rgba(255,255,255,.6)
│ └ "View all"                   13px / 16.9 / 700, rgba(255,255,255,.6); wrapper padding-right 12
├ card row (DivFadeScrollContainer)
│                                flex; align-items center; gap 10; overflow-x scroll;
│                                scrollbar hidden; padding-bottom 5; max-height 250; min-height 100;
│                                first and last card inset 10 from the ends
│ └ card × 20                    see below
├ bottom spacer                  15px
└ arrow × 2                      absolute over the whole container, see below
```

**Edge fade:**

```
mask-image: linear-gradient(to right, rgba(255,255,255,S) 0%, #000 125px,
                            #000 calc(100% - 125px), rgba(255,255,255,E) 100%)
```

- **S** is 1 while scrolled to the start, otherwise 0. So the left edge only fades once you have
  scrolled.
- **E** is 1 at the end, otherwise 0.
- TikTok declares a 150ms transition on these two custom properties, but never registers them
  (`@property`), so the fade switches instantly. Do the same.

**Card** (`DivCardContainer`): fixed 160×222 (`width`/`height` plus their min/max), `padding 16`,
`border-radius 12`, `background #1E1E1E`. On hover it becomes `rgba(255,255,255,.13)` (our
`--tt-field`), instantly. Flex column, centred, gap 14.

| Part | Measurement |
|---|---|
| Avatar | 96×96 circle, a link to the profile |
| Text block | width 132; flex column, centred, gap 4 |
| Name | 14px / 18.2 / **500**, `#FAFAFA`, one line, ellipsis; a link to the profile (TikTok appends a 14px verified tick — we have no verification) |
| Reason | 12px / 15.6 / 400, `rgba(255,255,255,.6)`, in a 20px-tall row, one line, ellipsis |
| Follow | 110×28 pill, `#FE2C55` (`--tt-red`), white 13px / 16.9 / 500, padding `1px 8px` |

The parts add up to 16 + 96 + 14 + 42.2 + 14 + 28 + 16 = 226.2, but the card is 222. The button's
bottom ends up 12px from the card edge. Reproduce it by fixing the height, not by trimming padding.

**Arrows:**

- Each arrow container is `position: absolute; top: 0; height: 100%` of the panel, with
  `padding-top: 15px` (from `padding: inherit`) and `align-items: center`. That puts the 32px icon
  about 2px above the card centre.
- Each container is 37px wide: a 5px inset plus the icon.
- Icon: 32×32 circle, `#1E1E1E`, no shadow, 16px chevron. The chevron is offset inside the circle
  with `padding-left 3.555px` on the right arrow, and `12.445px` plus `rotateY(180deg)` on the left.
- No hover change: TikTok's `:hover` rule loses to its own inline background.
- Visibility: the right arrow shows while not at the end; the left arrow only once scrolled.
- Click: `scrollBy({ left: ±clientWidth / 4, behavior: "smooth" })`, which is 324px at 1296. It lands
  in ~300ms.

**Tooltip:**

| Property | Value |
|---|---|
| Background, radius | white, 12px |
| Width | `max-width 240px` |
| Padding | `9px 12px` |
| Text | 14px / 18.2 / 500, black |
| Shadow | `0 2px 10px rgba(0,0,0,.21)` |
| Placement | right of the label group, top-aligned, 4px gap |
| Appearance | instant. The computed `transition` is a bare `opacity`, which means a 0s duration |

## 4. Backend — user-service

### Endpoint

`GET /api/v1/users/me/suggested-accounts?limit=20` → `ApiResponse<List<SuggestedAccountResponse>>`

- **Auth.** Requires a token. The gateway's catch-all already covers it: only numeric
  `/users/{id}`, `/users` and `/users/by-username/*` are public, and user-service's own
  `anyRequest().authenticated()` covers it too. **No gateway change.**
- **`limit`.** Default 20, clamped to 1–50 in the service, the way `storyAudience` clamps its `size`.

```java
public record SuggestedAccountResponse(
        UserProfileResponse profile,
        Reason reason,             // nested enum like FollowResponse.Status: FOLLOWS_YOU | FOLLOWED_BY | POPULAR
        String followedBy          // FOLLOWED_BY only: display name (or username) of one account
                                   // the viewer follows that follows this one; null otherwise
) {}
```

### Selection

Three tiers, concatenated in this order. Each account appears at most once, under the first tier
that found it.

1. **FOLLOWS_YOU**: live follows *into* the viewer whose follower the viewer does not follow back.
   Newest follow first.
2. **FOLLOWED_BY**: accounts followed (live edge) by at least one account the viewer follows.
   - Only followings with a live profile count as sources: not soft-deleted, not deactivated.
     Otherwise "Followed by {name}" would name a closed account that answers 404 to everyone, and
     that account would still add to the mutual count.
   - Ranked by how many of the viewer's followings follow them, most first.
   - Ties go to the higher `follower_count`, then the lower `user_id`.
   - `followedBy` names one of those followings, picked deterministically. Lean: the one the viewer
     followed most recently.
3. **POPULAR**: tops the list up to `limit`, by `follower_count` desc, then `user_id`.

Excluded from every tier, in the query rather than filtered afterwards (a list filtered after the
fact comes back short — same reasoning as `findFollowerIdsVisibleTo`):

- the viewer;
- live follow viewer → candidate;
- live row in `user_follow_requests` viewer → candidate;
- live block in either direction;
- live mute viewer → candidate. `user_mutes` is indexed from the muter, the side the viewer is on (see
  `UserMuteRepository`);
- candidate profile missing, soft-deleted or `deactivated_at IS NOT NULL`.

**Privacy check.** "Followed by A" reveals A's follow of C. The viewer has a live follow of A, which
means an approved one if A is private. The viewer can therefore already read A's following list. A
pending request is not a live follow, so it never makes A a source.

Profiles for the final ids are loaded in one batch: `findByUserIdInAndDeletedAtIsNullAndDeactivatedAtIsNull`
plus `UserProfileMapper`, as in `UserProfileBatchAssembler`. Keep the tier order. An id that lost its
profile in between is dropped.

### Shape

Project conventions apply: controller → `SuggestedAccountService` interface + impl → repository
queries, constructor injection, record DTOs. Whether the tiers are one native CTE or three
repository queries merged in the service is the builder's call. Lean: three queries, each with its
own test. Each tier asks for `limit` rows, and that is always enough. A duplicate can only be an
account already picked, so a tier cut off at `limit` still holds every row the list still needs.

Every follow-edge predicate must carry `deleted_at IS NULL`. That is what lets the partial indexes
`idx_user_follows_follower_id` / `idx_user_follows_following_id` serve them (see CLAUDE.md on
partial indexes).

### Cost

- **Tier 2** walks the viewer's followings' followings (F × M rows) through `idx_user_follows_follower_id`.
  That is fine for thousands. Leave a `ponytail:` comment naming the ceiling: cap the followings
  considered (most recent N) once accounts follow tens of thousands.
- **Tier 3** sorts `user_profiles` by `follower_count` with no index. That is fine at our size. An
  index there would be rewritten on every follow, since the counter changes, so don't add one
  pre-emptively. A `ponytail:` comment instead.

No Kafka, no outbox, no migration.

### Tests and docs

- A Testcontainers query test in the style of `StoryAudienceQueryTest`: rows inserted through the
  repositories, not the services, because a block through `BlockService` also ends the follows and
  would hide whether the query filters blocks. Cover:
  - every exclusion above, including soft-deleted follows/requests/blocks that must count as gone;
  - tier order and dedupe;
  - mutual-count ordering;
  - the `followedBy` pick;
  - top-up to `limit`;
  - the `limit` bounds.
- `docs/user-service-api.md`: a new §3.23 for the endpoint, plus a `SuggestedAccountResponse.reason`
  row in the §4 enum table.

## 5. Frontend — tiktok-cloned

### 5.1 Toggle and placement

- **`ProfilePage`** owns `suggestedOpen` and renders the panel between `ProfileHeader` and
  `ProfileBody`. View all → `setFollowTab("suggested")`.
- **`ProfileHeader`**:
  - The add-friend `IconButton` gets `aria-label="Suggested accounts"` plus
    `aria-expanded`/`aria-controls`, and calls `if (requireSignIn()) onToggleSuggested()`.
  - The header's bottom margin is `mb-5` closed and `mb-3` open, with no transition.
- The panel is full width at every breakpoint. Nothing changes at `tt-840`: the row just scrolls.
- `suggestedOpen` resets to closed when the profile's user id changes. A client-side hop from one
  `/@handle` to another can reuse the component, and the panel should not arrive already open.

### 5.2 Mounting and the animation

- The collapse wrapper is always rendered: `overflow-hidden`,
  `transition-[max-height,opacity] duration-[400ms] ease-[ease]`, with `max-h-0 opacity-0` ↔
  `max-h-[300px] opacity-100`.
- Its content mounts on the **first** open and then stays mounted. A freshly mounted wrapper would not
  animate, and an unmounted one could not animate closed.
- `inert` while closed.
- No inline `style` (FRONTEND_RULES). The four mask variants and everything else are Tailwind classes.

### 5.3 Data

- `lib/api/users.ts`: `getSuggestedAccounts(limit = 20)`, plus its response type next to the other API
  types in `lib/api/types.ts`. Profiles map to `Author` with `authorFromProfile`.
- `hooks/use-suggested-accounts.ts`: fetches once when first enabled and keeps the result while the page
  lives. Abort on unmount, the way `useSuggestedCreators` does.
- The panel drops the profile owner client-side. The endpoint is viewer-only by design. Ask for
  `limit + 1` so the row still holds 20 after the drop.
- When the response lands, write `setFollowState(id, false)` for every account in it. The server has
  just said the viewer follows none of them. Without this, the first card's `useFollow` starts the
  following-list walk in `isFollowing`: up to 5 pages against a gateway that allows 20 requests per
  second per IP.
- **Follow on a card** uses `useFollow(userId)`. That gives the shared follow cache, Requested for
  private accounts and the guest gate. Following / Requested use the neutral `--tt-field` fill at the
  same 110×28.
- **`FollowListModal`'s Suggested tab** switches from `useSuggestedCreators()` to the new hook. It
  drops `targetUserId` the same way and keeps its rows (name over @handle, as TikTok's tab does).
  `useSuggestedCreators` stays for `/following`.

### 5.4 States TikTok didn't show us

- **Loading:** skeleton cards at the real 160×222, so nothing shifts when data lands.
- **Empty or failed:** the title row stays, with one muted line ("No suggestions right now." /
  "Couldn't load suggestions."). The row is not collapsed. With the POPULAR top-up, an empty list
  should be rare.

### 5.5 Copy

- Labels:
  - "Suggested accounts", "View all", "Follow", "Following", "Requested".
  - Reasons: "Follows you", "Followed by {name}", "Suggested for you".
- Tooltip, ours: "Suggestions come from your connections: people who follow you, and people followed
  by accounts you follow. Your account can be suggested to others the same way."
- The existing `components/ui/Tooltip.tsx` is the dark one-line label used by the sidebar rail. The
  white 240px bubble is a second look for the same primitive (a variant plus a top-aligned right
  placement). It is not a second tooltip component.

### 5.6 Pure logic, with unit tests (FRONTEND_RULES)

- The reason → label mapping.
- The scroll-edge state (`scrollLeft`, `clientWidth`, `scrollWidth` → at start / at end, with a 1px
  tolerance). It drives both the arrows and the mask.

### 5.7 Research notes

Add §3 to `docs/research/tiktok.com/PROFILE.md` ("Suggested accounts"), the way the rest of that file
records the live page.

## 6. Non-goals

- A dismiss ✕ on cards. TikTok has none.
- Opening the panel automatically after pressing Follow on a profile. TikTok's app does this. On the
  web it wasn't observed, because testing it means following a real account.
- Suggestions that depend on the profile being viewed.
- Paging or infinite scroll in the dialog's Suggested tab.
- Changing `/following` or `/friends`.

## 7. Anti-goals

- Suggesting the viewer, someone they follow or asked to follow, someone across a block, or a
  closed account.
- A card showing "Follow" for an account the viewer already follows.
- Collapsed-panel controls reachable by keyboard or screen reader.
- A "Followed by" that reveals a follow the viewer couldn't otherwise see.

**Accepted gap:** a banned account can still be suggested. user-service has no ban data. Closing it
means user-service consuming `UserBannedEvent`/`UserUnbannedEvent` into its own `owner_bans` with the
`changedAt` guard, the way video-service and search-service do. That is a separate change.

## 8. Left to the builder

- One CTE vs three queries (lean in §4).
- The exact `followedBy` pick rule, as long as it is deterministic.
- The component split inside `components/profile/` (panel / card / arrows).
- How the 10px end insets are made: zero-width edge children like TikTok's, or inline padding on
  the scroller.
- Reduced motion. The app has no shared pattern for it yet.

## 9. Verifying

- **Backend:** the query test above, plus `./mvnw test -pl services/user-service`.
- **Frontend:**
  - unit tests for §5.6;
  - a component test: the toggle flips `aria-expanded`, the closed panel is `inert`, and View all
    opens the dialog on Suggested;
  - `npm run check`.
- **Live:** with Claude in Chrome on a local profile, check the rects against §3 and
  `getAnimations()` on open/close (`max-height` 0↔300 and `opacity` 0↔1, 400ms `ease`).
