# Suggested Accounts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On someone else's profile, the add-friend button opens TikTok's "Suggested accounts" row,
filled from the viewer's real follow graph.

**Architecture:**
- **user-service** gains `GET /api/v1/users/me/suggested-accounts`. Three native queries (follows
  you → followed by your followings → most followed) share one exclusion fragment and are merged in a
  service.
- **Profile page (web)** gets a collapsible panel copied from tiktok.com (`max-height`/`opacity`,
  400ms). A hook feeds it and fetches on first open.
- **Followers dialog (web):** its Suggested tab switches to the same hook.

**Tech Stack:**
- Backend: Java 21, Spring Boot 4, Spring Data JPA (native SQL on PostgreSQL 16), JUnit 5 + Testcontainers.
- Web: Next.js 16, React 19, TypeScript strict, Tailwind v4, Vitest + Testing Library.

**Spec:** `docs/superpowers/specs/2026-10-28-suggested-accounts-design.md` (backend repo). §3 holds every
measurement the UI tasks copy.

**Repos:**
- Backend: `/Users/minhhung/Projects/EProject/EProject4/tiktok-backend` (paths below starting `services/`
  or `docs/superpowers/`).
- Web: `/Users/minhhung/Projects/EProject/EProject4/tiktok-cloned` (paths starting `src/` or
  `docs/research/`).

## Global Constraints

- **Branch** `feat/suggested-accounts` in both repos, cut from the current `feat/story-reactions` HEAD.
  `dev` (backend) and `master` (web) are 118 and 62 commits behind and lack the stories work this
  builds on.
- **Endpoint** `GET /api/v1/users/me/suggested-accounts?limit=` returns
  `ApiResponse<List<SuggestedAccountResponse>>`. Each item is `{ profile, reason, followedBy }`, with
  `reason` ∈ `FOLLOWS_YOU | FOLLOWED_BY | POPULAR`. `limit`: default 20, clamped to 1–50.
- **Never suggested:**
  - the viewer;
  - anyone the viewer follows (live edge), has a live follow request to, has a live block with in
    either direction, or has muted;
  - a soft-deleted or deactivated profile.

  A FOLLOWED_BY source must itself be a live, non-deactivated profile.
- **Backend** follows CLAUDE.md: constructor injection, records, service interface + impl. Every
  follow-edge predicate carries `deleted_at IS NULL`.
- **Web** follows FRONTEND_RULES/AGENTS:
  - TS strict, no `any`, named exports;
  - Tailwind classes only, no `style={}`;
  - tests in a sibling `__tests__/`; pure logic in `src/lib/` with a unit test;
  - `npm run check` passes.
- **Copy, verbatim:**
  - "Suggested accounts", "View all", "Follow", "Following", "Requested";
  - "Follows you", "Followed by {name}", "Suggested for you";
  - "No suggestions right now.", "Couldn't load suggestions.";
  - tooltip: "Suggestions come from your connections: people who follow you, and people followed by
    accounts you follow. Your account can be suggested to others the same way."
- **Motion, verbatim:**
  - wrapper `max-height 0 ↔ 300px` plus `opacity 0 ↔ 1`, 400ms, CSS `ease` (`ease-[ease]`, not
    Tailwind's default curve);
  - header `margin-bottom` 20 ↔ 12px, no transition;
  - arrows `scrollBy(±clientWidth / 4, smooth)`;
  - edge fade and tooltip switch instantly.
- **Commits:** conventional, scope `suggestions`, each ending with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Brand-new account** (no follows either way) opens the row. It still fills from POPULAR and never
   contains the viewer. Test: Task 1 `newAccountGetsTheMostFollowedButNeverItself`.
2. **Account reachable through several tiers.** It is shown once, as "Follows you". Test: Task 1
   `eachAccountAppearsOnceUnderItsFirstTier`.
3. **Only route to a candidate goes through a closed account.** No "Followed by" names that closed
   account; the candidate can only appear as POPULAR. Test: Task 1 `aClosedAccountIsNeverTheReason`.
4. **Viewer opens the row on the profile of someone the endpoint suggests.** That person is left out
   and the row still holds 20. Test: Task 3 `leavesOutTheExcludedAccountAndKeepsTwenty`.
5. **Toggling the panel repeatedly.** One request in total, and the closed panel can't be tabbed into.
   Tests: Task 3 `asksOnceForOneMoreThanItShows`, Task 4 `staysInertAndSilentWhileClosed`.

---

### Task 1: Suggestion queries and service (user-service)

**Files:**
- Create: `services/user-service/src/main/java/com/tiktok/userservice/dto/response/SuggestedAccountResponse.java`
- Create: `services/user-service/src/main/java/com/tiktok/userservice/repository/SuggestedAccountRepository.java`
- Create: `services/user-service/src/main/java/com/tiktok/userservice/service/SuggestedAccountService.java`
- Create: `services/user-service/src/main/java/com/tiktok/userservice/service/SuggestedAccountServiceImpl.java`
- Test: `services/user-service/src/test/java/com/tiktok/userservice/repository/SuggestedAccountsQueryTest.java`

**Interfaces:**
- Produces:
  - `public record SuggestedAccountResponse(UserProfileResponse profile, Reason reason, String followedBy)`
    with a nested `public enum Reason { FOLLOWS_YOU, FOLLOWED_BY, POPULAR }`, the way `FollowResponse.Status` is nested.
  - `public interface SuggestedAccountService { List<SuggestedAccountResponse> suggest(Long viewerId, Integer limit); }`.
    A null `limit` means 20; any value is clamped to [1, 50].

- [ ] **Step 1: Branch, and commit the spec and this plan**

```bash
cd /Users/minhhung/Projects/EProject/EProject4/tiktok-backend
git switch -c feat/suggested-accounts
git add docs/superpowers/specs/2026-10-28-suggested-accounts-design.md docs/superpowers/plans/2026-10-28-suggested-accounts.md
git commit -m "docs(suggestions): spec and plan for suggested accounts"
```

- [ ] **Step 2: Write the failing query test**

Copy the class setup from `StoryAudienceQueryTest`:
- `@SpringBootTest @Testcontainers @ActiveProfiles("test")`;
- a `postgres:16-alpine` `@ServiceConnection` container;
- `@MockitoBean KafkaTemplate<String, String>`.

Autowire `SuggestedAccountService` and the profile, follow, block, mute and `FollowRequestRepository`
repositories. `deleteAllInBatch()` all five in `@BeforeEach`, then seed.

Insert through the repositories, never the services: a block placed through `BlockService` also ends
the follows. Each relationship row goes in with its own `save`, so `created_at` and the Snowflake
`id` follow insertion order.

Put this graph in the class javadoc:

```
Viewer V = 1. Profiles 1..20 and 30, displayName "user{id}". followerCount is 0 except
17:900, 15:800, 18:700, 19:600, 20:500. Deactivated: 7, 12.
Follows into V (in this order): 4, 2, 3, 5, 6, 7, 8, 10 — then 9, soft-deleted.
V follows (in this order):      3, 11, 12.
Other follows:                  3→13, 11→13, 3→14, 11→2, 3→V, 12→15; 11→16 soft-deleted.
Blocks: V blocks 5; 6 blocks V.   Mute: V mutes 8.   Live follow request: V → 10.
```

```java
@Test
void ordersFollowsYouThenFollowedByThenMostFollowed() {
    List<SuggestedAccountResponse> all = suggestedAccountService.suggest(1L, null);

    assertThat(all).extracting(a -> a.profile().userId())
            .containsExactly(2L, 4L, 13L, 14L, 17L, 15L, 18L, 19L, 20L, 9L, 16L, 30L);
    assertThat(all).extracting(SuggestedAccountResponse::reason).containsExactly(
            FOLLOWS_YOU, FOLLOWS_YOU, FOLLOWED_BY, FOLLOWED_BY,
            POPULAR, POPULAR, POPULAR, POPULAR, POPULAR, POPULAR, POPULAR, POPULAR);
}

@Test
void namesTheSourceTheViewerFollowedMostRecently() {
    Map<Long, String> followedBy = suggestedAccountService.suggest(1L, null).stream()
            .filter(a -> a.followedBy() != null)
            .collect(toMap(a -> a.profile().userId(), SuggestedAccountResponse::followedBy));

    assertThat(followedBy).containsOnly(entry(13L, "user11"), entry(14L, "user3"));
}

/** 2 follows V and is also followed by 11, whom V follows. */
@Test
void eachAccountAppearsOnceUnderItsFirstTier() {
    assertThat(suggestedAccountService.suggest(1L, null))
            .filteredOn(a -> a.profile().userId() == 2L)
            .singleElement().extracting(SuggestedAccountResponse::reason).isEqualTo(FOLLOWS_YOU);
}

/** The only route to 15 runs through 12, who closed their account. */
@Test
void aClosedAccountIsNeverTheReason() {
    assertThat(suggestedAccountService.suggest(1L, null))
            .filteredOn(a -> a.profile().userId() == 15L)
            .singleElement().satisfies(a -> {
                assertThat(a.reason()).isEqualTo(POPULAR);
                assertThat(a.followedBy()).isNull();
            });
}

@Test
void leavesOutEveryoneTheViewerMustNotBeOffered() {
    assertThat(suggestedAccountService.suggest(1L, 50)).extracting(a -> a.profile().userId())
            .doesNotContain(1L, 3L, 5L, 6L, 7L, 8L, 10L, 11L, 12L);
}

@Test
void newAccountGetsTheMostFollowedButNeverItself() {
    List<SuggestedAccountResponse> all = suggestedAccountService.suggest(30L, null);

    assertThat(all).extracting(a -> a.profile().userId())
            .startsWith(17L, 15L, 18L, 19L, 20L).doesNotContain(30L, 7L, 12L);
    assertThat(all).extracting(SuggestedAccountResponse::reason).containsOnly(POPULAR);
}

@Test
void limitIsClampedToOneThroughFifty() {
    for (long id = 100; id < 160; id++) {
        userProfileRepository.save(UserProfile.builder().userId(id).displayName("user" + id).build());
    }

    assertThat(suggestedAccountService.suggest(1L, 3)).extracting(a -> a.profile().userId())
            .containsExactly(2L, 4L, 13L);
    assertThat(suggestedAccountService.suggest(1L, 0)).hasSize(1);
    assertThat(suggestedAccountService.suggest(30L, 999)).hasSize(50);
}
```

- [ ] **Step 3: Run it to see it fail**

Run: `./mvnw test -pl services/user-service -Dtest=SuggestedAccountsQueryTest`
Expected: compilation failure, because `SuggestedAccountService` does not exist.

- [ ] **Step 4: Write `SuggestedAccountRepository`**

It extends `org.springframework.data.repository.Repository<UserProfile, Long>`, so it is a home for
native queries only. Every query aliases the candidate's `user_profiles` row as `c` and appends this
shared fragment:

```java
/** Who may be offered to :viewerId. The candidate's user_profiles row is aliased {@code c}. */
String SUGGESTIBLE = """
        c.deleted_at IS NULL AND c.deactivated_at IS NULL AND c.user_id <> :viewerId
        AND NOT EXISTS (SELECT 1 FROM user_follows mine WHERE mine.follower_id = :viewerId
                        AND mine.following_id = c.user_id AND mine.deleted_at IS NULL)
        AND NOT EXISTS (SELECT 1 FROM user_follow_requests r WHERE r.requester_id = :viewerId
                        AND r.target_id = c.user_id AND r.deleted_at IS NULL)
        AND NOT EXISTS (SELECT 1 FROM user_blocks b WHERE b.deleted_at IS NULL
                        AND ((b.blocker_id = :viewerId AND b.blocked_id = c.user_id)
                          OR (b.blocked_id = :viewerId AND b.blocker_id = c.user_id)))
        AND NOT EXISTS (SELECT 1 FROM user_mutes m WHERE m.muter_id = :viewerId
                        AND m.muted_id = c.user_id AND m.deleted_at IS NULL)
        """;
```

Three methods, all `nativeQuery = true`, with `@Param("viewerId") long viewerId, @Param("limit") int limit`:

1. **`List<Long> findFollowersNotFollowedBack(...)`**
   - Selects `f.follower_id` from `user_follows f`, joined to `c` on `c.user_id = f.follower_id`.
   - `WHERE f.following_id = :viewerId AND f.deleted_at IS NULL AND` + `SUGGESTIBLE`.
   - `ORDER BY f.created_at DESC, f.id DESC LIMIT :limit`.
2. **`List<FollowedByCandidate> findFollowedByFollowings(...)`**
   - Returns the nested projection `interface FollowedByCandidate { Long getUserId(); Long getViaId(); }`.
   - The aliases are quoted so Postgres keeps their case:

   ```sql
   SELECT f2.following_id AS "userId",
          (array_agg(f1.following_id ORDER BY f1.created_at DESC, f1.id DESC))[1] AS "viaId"
   FROM user_follows f1
   JOIN user_profiles src ON src.user_id = f1.following_id
        AND src.deleted_at IS NULL AND src.deactivated_at IS NULL
   JOIN user_follows f2 ON f2.follower_id = f1.following_id AND f2.deleted_at IS NULL
   JOIN user_profiles c ON c.user_id = f2.following_id
   WHERE f1.follower_id = :viewerId AND f1.deleted_at IS NULL AND <SUGGESTIBLE>
   GROUP BY f2.following_id, c.follower_count
   ORDER BY count(*) DESC, c.follower_count DESC, f2.following_id
   LIMIT :limit
   ```

   Javadoc it with `ponytail: walks the viewer's followings' followings (F × M rows) on
   idx_user_follows_follower_id; cap the followings considered to the most recent N once accounts
   follow tens of thousands`.
3. **`List<Long> findMostFollowed(...)`**
   - Selects `c.user_id` from `user_profiles c`.
   - `WHERE` + `SUGGESTIBLE` + `ORDER BY c.follower_count DESC, c.user_id LIMIT :limit`.
   - Javadoc it with `ponytail: sorts every profile, no index; an index on follower_count is
     rewritten on every follow, so add one only when this shows up in a profile`.

- [ ] **Step 5: Write `SuggestedAccountService` and `SuggestedAccountServiceImpl.suggest(Long viewerId, Integer limit)`**

- Clamp: null → 20, then into [1, 50].
- Ask each tier for `n` rows. That is always enough, because a duplicate can only be an account
  already picked.
- Fill a `LinkedHashMap<Long, …>` tier by tier with `putIfAbsent`, keeping the reason (and `viaId` for
  FOLLOWED_BY), and stop at `n`.
- Load the picked ids plus every `viaId` in one
  `userProfileRepository.findByUserIdInAndDeletedAtIsNullAndDeactivatedAtIsNull(...)`.
- Map in pick order with `UserProfileMapper.toResponse`, and drop an id whose profile is gone.
- `followedBy` is the via profile's `displayName`, else its `username`, else null.

- [ ] **Step 6: Run the test to see it pass**

Run: `./mvnw test -pl services/user-service -Dtest=SuggestedAccountsQueryTest`
Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Step 7: Commit**

```bash
git add services/user-service/src
git commit -m "feat(suggestions): pick people the viewer may know from the follow graph"
```

---

### Task 2: Endpoint and API docs (user-service)

**Files:**
- Create: `services/user-service/src/main/java/com/tiktok/userservice/controller/SuggestedAccountController.java`
- Modify: `docs/user-service-api.md`. Add §3.23 after §3.22, and a row in the §4 enum table.
- Test: `services/user-service/src/test/java/com/tiktok/userservice/controller/SuggestedAccountControllerTest.java`

**Interfaces:**
- Consumes: `SuggestedAccountService.suggest(Long, Integer)`, `SuggestedAccountResponse` (Task 1).
- Produces: `GET /api/v1/users/me/suggested-accounts?limit=` →
  `ApiResponse<List<SuggestedAccountResponse>>`.

- [ ] **Step 1: Write the failing controller test**

Use the setup from `FollowRequestControllerTest`:
- `@WebMvcTest(controllers = SuggestedAccountController.class)`;
- the same `@Import` and `@TestPropertySource`;
- `@MockitoBean SuggestedAccountService`;
- its `tokenFor(userId)` helper.

```java
@Test
void suggestedAccounts_withoutToken_isRejectedBeforeReachingService() throws Exception {
    mockMvc.perform(get("/api/v1/users/me/suggested-accounts"))
            .andExpect(status().isForbidden());

    verify(suggestedAccountService, never()).suggest(any(), any());
}

@Test
void suggestedAccounts_areTheCallersOwn() throws Exception {
    UserProfileResponse ann = new UserProfileResponse(13L, "ann", "Ann", null, null, 0, 0, false, null);
    when(suggestedAccountService.suggest(1L, null)).thenReturn(List.of(
            new SuggestedAccountResponse(ann, SuggestedAccountResponse.Reason.FOLLOWED_BY, "user11")));

    mockMvc.perform(get("/api/v1/users/me/suggested-accounts")
                    .header("Authorization", "Bearer " + tokenFor(1L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].profile.userId").value(13))
            .andExpect(jsonPath("$.data[0].reason").value("FOLLOWED_BY"))
            .andExpect(jsonPath("$.data[0].followedBy").value("user11"));
}

@Test
void suggestedAccounts_passTheLimitThrough() throws Exception {
    when(suggestedAccountService.suggest(1L, 5)).thenReturn(List.of());

    mockMvc.perform(get("/api/v1/users/me/suggested-accounts?limit=5")
                    .header("Authorization", "Bearer " + tokenFor(1L)))
            .andExpect(status().isOk());

    verify(suggestedAccountService).suggest(1L, 5);
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw test -pl services/user-service -Dtest=SuggestedAccountControllerTest`
Expected: compilation failure, because `SuggestedAccountController` does not exist.

- [ ] **Step 3: Write `SuggestedAccountController`**

- Method: `suggestedAccounts(@AuthenticationPrincipal Long currentUserId, @RequestParam(required = false) Integer limit)`.
- Mapping: `@GetMapping("/api/v1/users/me/suggested-accounts")`, returning
  `ApiResponse.success(suggestedAccountService.suggest(currentUserId, limit))`.
- `@Tag(name = "Suggestions")`.
- `@Operation` description: the three tiers, the exclusions, and the 1–50 clamp.

No security or gateway change. Both catch-alls already require a token for this path.

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw test -pl services/user-service -Dtest=SuggestedAccountControllerTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Document it in `docs/user-service-api.md`**

Write it in Vietnamese, matching the file.

**§3.23 `GET /me/suggested-accounts?limit=20` — gợi ý "người bạn có thể biết"** must cover:
- The token is required.
- `limit` defaults to 20 and is clamped to 1–50.
- The three tiers and their order.
- Every exclusion from the Global Constraints.
- `followedBy` exists only for `FOLLOWED_BY`.
- Banned accounts are not filtered (user-service has no ban data).
- An example response:

```json
[{ "profile": { "userId": 13, "username": "ann", "displayName": "Ann", "...": "…" },
   "reason": "FOLLOWED_BY", "followedBy": "user11" },
 { "profile": { "userId": 17, "...": "…" }, "reason": "POPULAR", "followedBy": null }]
```

In §4, add a row: `SuggestedAccountResponse.reason` | `FOLLOWS_YOU`, `FOLLOWED_BY`, `POPULAR` | mục 3.23.

- [ ] **Step 6: Run the whole service suite**

Run: `./mvnw test -pl services/user-service`
Expected: `BUILD SUCCESS`

- [ ] **Step 7: Commit**

```bash
git add services/user-service/src docs/user-service-api.md
git commit -m "feat(suggestions): GET /api/v1/users/me/suggested-accounts"
```

---

### Task 3: Client, pure logic and the data hook (web)

**Files:**
- Modify: `src/lib/api/types.ts`. Add the response types after `UserProfileResponse`.
- Modify: `src/lib/api/users.ts`. Add `getSuggestedAccounts` after `getFollowing`.
- Modify: `src/types/tiktok.ts`. Add `SuggestedAccount` after `SuggestedCreator`.
- Create: `src/lib/suggested-accounts.ts`
- Create: `src/hooks/use-suggested-accounts.ts`
- Test: `src/lib/__tests__/suggested-accounts.test.ts`, `src/hooks/__tests__/use-suggested-accounts.test.ts`

**Interfaces:**
- Consumes: the JSON from Task 2.
- Produces:
  - `type SuggestionReason = "FOLLOWS_YOU" | "FOLLOWED_BY" | "POPULAR"`
  - `interface SuggestedAccountResponse { profile: UserProfileResponse; reason: SuggestionReason; followedBy: string | null }`
  - `getSuggestedAccounts(limit = 20): Promise<SuggestedAccountResponse[]>`, which is
    `apiFetch(\`/users/me/suggested-accounts\`, { auth: "required", query: { limit } })`
  - `interface SuggestedAccount { author: Author; label: string }`
  - `reasonLabel(reason: SuggestionReason, followedBy: string | null): string`
  - `scrollEdges(scrollLeft: number, clientWidth: number, scrollWidth: number): { atStart: boolean; atEnd: boolean }`
    (1px tolerance)
  - `SUGGESTED_ACCOUNTS_LIMIT = 20`
  - `useSuggestedAccounts(enabled: boolean, excludeUserId?: string): { accounts: SuggestedAccount[]; isLoading: boolean; error: unknown }`

- [ ] **Step 1: Branch the web repo**

```bash
cd /Users/minhhung/Projects/EProject/EProject4/tiktok-cloned
git switch -c feat/suggested-accounts
```

- [ ] **Step 2: Write the failing tests**

`src/lib/__tests__/suggested-accounts.test.ts`:

```ts
describe("reasonLabel", () => {
  it("says why each account is suggested", () => {
    expect(reasonLabel("FOLLOWS_YOU", null)).toBe("Follows you");
    expect(reasonLabel("FOLLOWED_BY", "Ann")).toBe("Followed by Ann");
    expect(reasonLabel("FOLLOWED_BY", null)).toBe("Suggested for you");
    expect(reasonLabel("POPULAR", null)).toBe("Suggested for you");
  });
});

describe("scrollEdges", () => {
  it("knows which ends of the row are reached", () => {
    expect(scrollEdges(0, 1296, 3410)).toEqual({ atStart: true, atEnd: false });
    expect(scrollEdges(324, 1296, 3410)).toEqual({ atStart: false, atEnd: false });
    expect(scrollEdges(2113.5, 1296, 3410)).toEqual({ atStart: false, atEnd: true });
    expect(scrollEdges(0, 1296, 1296)).toEqual({ atStart: true, atEnd: true });
  });
});
```

`src/hooks/__tests__/use-suggested-accounts.test.ts`:
- Mock `@/lib/api/users` with `getSuggestedAccounts` and `setFollowState` spies.
- Add a helper `suggestion(id)` that returns a `POPULAR` item. Its profile is
  `{ userId: String(id), username: \`u${id}\`, displayName: \`User ${id}\`, bio: null, avatarUrl: null, followerCount: 0, followingCount: 0 }`.

```ts
it("does not ask before it is first enabled", () => {
  renderHook(() => useSuggestedAccounts(false));
  expect(getSuggestedAccounts).not.toHaveBeenCalled();
});

it("asksOnceForOneMoreThanItShows", async () => {
  getSuggestedAccounts.mockResolvedValue([suggestion(1)]);
  const { result, rerender } = renderHook(({ on }) => useSuggestedAccounts(on), {
    initialProps: { on: false },
  });
  rerender({ on: true });
  await waitFor(() => expect(result.current.isLoading).toBe(false));
  rerender({ on: false });
  rerender({ on: true });

  expect(getSuggestedAccounts).toHaveBeenCalledTimes(1);
  expect(getSuggestedAccounts).toHaveBeenCalledWith(21);
});

it("leavesOutTheExcludedAccountAndKeepsTwenty", async () => {
  getSuggestedAccounts.mockResolvedValue(Array.from({ length: 21 }, (_, i) => suggestion(i + 1)));
  const { result } = renderHook(() => useSuggestedAccounts(true, "5"));
  await waitFor(() => expect(result.current.accounts).toHaveLength(20));

  expect(result.current.accounts.map((a) => a.author.userId)).not.toContain("5");
  expect(result.current.accounts[0].label).toBe("Suggested for you");
});

it("tells the follow cache that none of them is followed", async () => {
  getSuggestedAccounts.mockResolvedValue([suggestion(1), suggestion(2)]);
  const { result } = renderHook(() => useSuggestedAccounts(true));
  await waitFor(() => expect(result.current.isLoading).toBe(false));

  expect(setFollowState).toHaveBeenCalledWith("1", false);
  expect(setFollowState).toHaveBeenCalledWith("2", false);
});
```

- [ ] **Step 3: Run them to see them fail**

Run: `npx vitest run src/lib/__tests__/suggested-accounts.test.ts src/hooks/__tests__/use-suggested-accounts.test.ts`
Expected: FAIL, the modules cannot be resolved.

- [ ] **Step 4: Implement the types, `getSuggestedAccounts`, `reasonLabel`, `scrollEdges` and the hook**

The hook follows `useSuggestedCreators`'s shape (`AbortController`, ignore results after unmount).
- It starts at most once per mount: the first time `enabled` is true.
- It asks for `SUGGESTED_ACCOUNTS_LIMIT + 1`.
- It calls `setFollowState(profile.userId, false)` for every returned item before rendering.
- It maps each item to `{ author: authorFromProfile(profile), label: reasonLabel(reason, followedBy) }`,
  drops `excludeUserId`, and keeps the first 20.
- `isLoading` is true from that first enable until the request settles.

- [ ] **Step 5: Run them to see them pass**

Run: `npx vitest run src/lib/__tests__/suggested-accounts.test.ts src/hooks/__tests__/use-suggested-accounts.test.ts`
Expected: 6 passed

- [ ] **Step 6: Commit**

```bash
git add src/lib src/hooks src/types
git commit -m "feat(suggestions): client, labels and hook for suggested accounts"
```

---

### Task 4: The panel, wired into the profile (web)

**Files:**
- Modify: `src/components/ui/Tooltip.tsx`
- Create: `src/components/profile/SuggestedAccountsPanel.tsx`
- Modify: `src/components/profile/ProfileHeader.tsx`. Change the header `className` (line 110),
  `IconButton` (lines 172–174 and 262–282) and the props.
- Modify: `src/components/profile/ProfilePage.tsx`
- Modify: `src/components/profile/BackendProfilePage.tsx:502`
- Modify: `docs/research/tiktok.com/PROFILE.md`
- Test: `src/components/profile/__tests__/SuggestedAccountsPanel.test.tsx`, `src/components/profile/__tests__/ProfileHeader.test.tsx`

**Interfaces:**
- Consumes: `useSuggestedAccounts`, `scrollEdges`, `SuggestedAccount` (Task 3); `useFollow`;
  `StoryAvatar`; `InfoCircleIcon`, `ChevronRightIcon`.
- Produces:
  - `SUGGESTED_ACCOUNTS_PANEL_ID = "suggested-accounts"`
  - `SuggestedAccountsPanel({ open, excludeUserId, onViewAll }: { open: boolean; excludeUserId: string; onViewAll: () => void })`
  - `ProfileHeader` gains `suggestedOpen: boolean` and `onToggleSuggested: () => void`.
  - `Tooltip` gains `variant?: "label" | "bubble"` (default `"label"`) and `placement` value `"right-start"`.

- [ ] **Step 1: Write the failing component tests**

Common mocks for `SuggestedAccountsPanel.test.tsx`:
- `@/lib/api/users` → `{ getSuggestedAccounts, setFollowState: vi.fn() }`;
- `@/hooks/use-follow` → `useFollow: () => ({ isSelf: false, following: false, requested: false, ready: true, toggle: vi.fn() })`;
- `@/components/stories/StoryAvatar` → renders `children`.

Redefine the `suggestion(id)` helper from Task 3's hook test locally. There is no jest-dom, so assert
on attributes and `textContent`.

```tsx
it("staysInertAndSilentWhileClosed", () => {
  const { container } = render(<SuggestedAccountsPanel open={false} excludeUserId="7" onViewAll={vi.fn()} />);
  expect(container.querySelector("#suggested-accounts")?.hasAttribute("inert")).toBe(true);
  expect(getSuggestedAccounts).not.toHaveBeenCalled();
});

it("shows each suggestion with its reason and a Follow button", async () => {
  getSuggestedAccounts.mockResolvedValue([
    { ...suggestion(9), reason: "FOLLOWED_BY", followedBy: "Ann" },
    suggestion(7),
  ]);
  render(<SuggestedAccountsPanel open excludeUserId="7" onViewAll={vi.fn()} />);

  expect(await screen.findByText("Followed by Ann")).toBeTruthy();
  expect(screen.queryByText("User 7")).toBeNull();
  expect(screen.getAllByRole("button", { name: "Follow" })).toHaveLength(1);
});

it("explains where suggestions come from on hover", async () => {
  getSuggestedAccounts.mockResolvedValue([]);
  render(<SuggestedAccountsPanel open excludeUserId="7" onViewAll={vi.fn()} />);
  fireEvent.mouseEnter(screen.getByText("Suggested accounts"));
  expect(screen.getByRole("tooltip").textContent).toBe(
    "Suggestions come from your connections: people who follow you, and people followed by accounts you follow. Your account can be suggested to others the same way.",
  );
});

it("opens the dialog from View all, and says when there is nothing to suggest", async () => {
  getSuggestedAccounts.mockResolvedValue([]);
  const onViewAll = vi.fn();
  render(<SuggestedAccountsPanel open excludeUserId="7" onViewAll={onViewAll} />);

  expect(await screen.findByText("No suggestions right now.")).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "View all" }));
  expect(onViewAll).toHaveBeenCalledTimes(1);
});

it("keeps its height and says so when the request fails", async () => {
  getSuggestedAccounts.mockRejectedValue(new Error("down"));
  render(<SuggestedAccountsPanel open excludeUserId="7" onViewAll={vi.fn()} />);
  expect(await screen.findByText("Couldn't load suggestions.")).toBeTruthy();
});
```

`ProfileHeader.test.tsx`:
- Mock `@/components/stories/ProfileStoryAvatar` and `@/components/profile/ProfileMoreMenu` to render
  nothing.
- Add a helper `header(overrides)` that renders `<ProfileHeader>` with a visitor `UserProfile`
  fixture, `isOwner={false}` and no-op `onEditProfile` / `onToggleFollow` / `onOpenFollowers` /
  `onOpenFollowing`, spread with `overrides`.

```tsx
it("toggles the suggested accounts for a signed-in visitor", () => {
  const onToggleSuggested = vi.fn();
  const { rerender } = render(header({ requireSignIn: () => true, suggestedOpen: false, onToggleSuggested }));
  const button = screen.getByRole("button", { name: "Suggested accounts" });

  expect(button.getAttribute("aria-expanded")).toBe("false");
  expect(button.getAttribute("aria-controls")).toBe("suggested-accounts");
  fireEvent.click(button);
  expect(onToggleSuggested).toHaveBeenCalledTimes(1);

  rerender(header({ requireSignIn: () => true, suggestedOpen: true, onToggleSuggested }));
  expect(button.getAttribute("aria-expanded")).toBe("true");
});

it("asks a guest to sign in instead", () => {
  const onToggleSuggested = vi.fn();
  render(header({ requireSignIn: () => false, suggestedOpen: false, onToggleSuggested }));
  fireEvent.click(screen.getByRole("button", { name: "Suggested accounts" }));
  expect(onToggleSuggested).not.toHaveBeenCalled();
});
```

- [ ] **Step 2: Run them to see them fail**

Run: `npx vitest run src/components/profile/__tests__/SuggestedAccountsPanel.test.tsx src/components/profile/__tests__/ProfileHeader.test.tsx`
Expected: FAIL. The panel module is missing, and the header has no "Suggested accounts" button.

- [ ] **Step 3: Add the bubble look to `Tooltip`**

- **`right-start`** places the tip at `{ x: r.right + 4, y: r.top }`, with no translate.
- **`bubble`** swaps the label classes for
  `max-w-[240px] whitespace-normal rounded-[12px] bg-white px-3 py-[9px] text-[14px] leading-[18.2px] font-medium text-black shadow-[0_2px_10px_rgba(0,0,0,0.21)]`.
- Still mounted on enter and removed on leave. TikTok's tip has no fade.

- [ ] **Step 4: Build `SuggestedAccountsPanel`**

Every size and colour comes from spec §3 (title row, card table, arrows). These are the
Tailwind translations a reader can't derive on their own:

**Wrapper:**
- Always rendered with `id={SUGGESTED_ACCOUNTS_PANEL_ID}`, `inert={!open}` and
  `overflow-hidden transition-[max-height,opacity] duration-[400ms] ease-[ease]`.
- Open: `max-h-[300px] opacity-100`. Closed: `max-h-0 opacity-0`.

**Mounting:** the hook runs with `useSuggestedAccounts(open, excludeUserId)`. The title row and the
card row mount on the first open (a state flag set during render) and then stay mounted, so closing
can animate.

**Card row:**
- Classes: `flex items-center gap-[10px] overflow-x-scroll pb-[5px] max-h-[250px] min-h-[100px] [scrollbar-width:none] [&::-webkit-scrollbar]:hidden`.
- A zero-width `flex-none` span at each end makes the 10px end insets, the way TikTok does it.
- Cards are `flex-none` at 160×222, so they never shrink.

**Edge fade:** pick one class from `scrollEdges(...)`:

| `atStart` / `atEnd` | class |
|---|---|
| true / true | none |
| true / false | `[mask-image:linear-gradient(to_right,#000_0%,#000_calc(100%_-_125px),transparent_100%)]` |
| false / true | `[mask-image:linear-gradient(to_right,transparent_0%,#000_125px,#000_100%)]` |
| false / false | `[mask-image:linear-gradient(to_right,transparent_0%,#000_125px,#000_calc(100%_-_125px),transparent_100%)]` |

`scrollEdges` is recomputed on the row's `onScroll`, after the accounts render, and on window
`resize`.

**Arrows:**
- Container: `pointer-events-none absolute top-0 flex h-full items-center pt-[15px] pl-[5px]`, at
  `right-0` or `left-0`. This copies TikTok's `padding: inherit`.
- Button: `pointer-events-auto z-[1] flex h-8 w-8 items-center rounded-full bg-[#1e1e1e]` with
  `pl-[3.555px]` (right) or `pl-[12.445px]` (left).
- Icon: `ChevronRightIcon` `h-4 w-4`, mirrored with `-scale-x-100` on the left.
- Shown only while that side isn't reached.
- Click: `row.scrollBy({ left: ±row.clientWidth / 4, behavior: "smooth" })`.
- `aria-label`: "Previous" / "Next".

**Card:**
- Avatar link and name link go to `/@${author.username}`. The avatar is wrapped in `StoryAvatar`.
- The Follow button uses `useFollow(author.userId)`:
  - Not followed: label "Follow", `bg-[var(--tt-red)] text-white`.
  - Following or requested: label "Following" or "Requested", `bg-[var(--tt-field)] text-[var(--tt-text)]`.

**States:**
- **Loading:** 8 skeleton cards at 160×222.
- **Empty or error:** the copy line, centred, in a `h-[227px]` box where the row would be, so the
  panel keeps its height.
- **View all:** a `<button>` that calls `onViewAll`.

- [ ] **Step 5: Wire the header and the page**

**`ProfileHeader`:**
- Header class: `mb-5` → `suggestedOpen ? "mb-3" : "mb-5"`, with no transition.
- `IconButton` takes optional `expanded` and `controls` props and renders `aria-expanded` and
  `aria-controls`.
- The add-friend button gets label "Suggested accounts", `expanded={suggestedOpen}`,
  `controls={SUGGESTED_ACCOUNTS_PANEL_ID}`, and
  `onClick={() => { if (requireSignIn()) onToggleSuggested(); }}`.

**`ProfilePage`:**
- Add `const [suggestedOpen, setSuggestedOpen] = useState(false)`.
- Between `ProfileHeader` and `ProfileBody`, for visitors only (`!isOwner`), render
  `<SuggestedAccountsPanel open={suggestedOpen} excludeUserId={profile.author.userId} onViewAll={() => setFollowTab("suggested")} />`.

**`BackendProfilePage`:** add `key={shown.author.userId}` to `<ProfilePage>`. Every per-profile state,
the panel's included, then starts fresh on another `/@handle`.

- [ ] **Step 6: Run the tests to see them pass**

Run: `npx vitest run src/components/profile/__tests__`
Expected: all passed, the existing profile tests included.

- [ ] **Step 7: Record the reference in `docs/research/tiktok.com/PROFILE.md`**

Add a `## Suggested accounts` section after "Buttons row", carrying spec §3: behaviour, the animation
table, the layout tree, card, arrows and tooltip. Use the same "Measured live on 2026-10-28" framing
as the rest of the file.

- [ ] **Step 8: Commit**

```bash
git add src/components docs/research
git commit -m "feat(suggestions): suggested accounts row on profiles, copied from tiktok.com"
```

---

### Task 5: The dialog's Suggested tab uses the same source (web)

**Files:**
- Modify: `src/components/profile/FollowListModal.tsx`. Change line 131 and `SuggestedList`
  (lines 301–320), and drop the `useSuggestedCreators` import.
- Test: `src/components/profile/__tests__/FollowListModal.test.tsx`

**Interfaces:**
- Consumes: `useSuggestedAccounts(true, excludeUserId)` (Task 3).

- [ ] **Step 1: Write the failing test**

Mocks:
- `@/lib/api/users` → `{ getSuggestedAccounts, setFollowState: vi.fn() }`;
- `@/hooks/use-follow`;
- `@/components/stories/StoryAvatar`, as in Task 4.

```tsx
it("lists the viewer's suggestions without the profile's owner", async () => {
  getSuggestedAccounts.mockResolvedValue([suggestion(7), suggestion(9)]);
  render(
    <FollowListModal targetUserId="7" initialTab="suggested" followerCount={0} followingCount={0} onClose={vi.fn()} />,
  );

  expect(await screen.findByText("User 9")).toBeTruthy();
  expect(screen.queryByText("User 7")).toBeNull();
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `npx vitest run src/components/profile/__tests__/FollowListModal.test.tsx`
Expected: FAIL. The tab still reads the feed (`getFeed`) and never calls `getSuggestedAccounts`.

- [ ] **Step 3: Switch the tab**

- Change `SuggestedList` to `SuggestedList({ excludeUserId }: { excludeUserId: string })`, reading
  `useSuggestedAccounts(true, excludeUserId)`.
- Its rows become `<UserRow key={a.author.userId} author={a.author} />`. The skeleton and the rows
  stay as they are.
- Render it as `<SuggestedList excludeUserId={targetUserId} />`.

- [ ] **Step 4: Run it to see it pass**

Run: `npx vitest run src/components/profile/__tests__/FollowListModal.test.tsx`
Expected: 1 passed

- [ ] **Step 5: Commit**

```bash
git add src/components/profile
git commit -m "feat(suggestions): the dialog's Suggested tab lists the same people"
```

---

### Task 6: End-to-end check against the measurements

**Files:** none changed unless a check fails. Fix inside the task that owns the code, then rerun this
task.

- [ ] **Step 1: Run every suite**

Run, in the web repo: `npm test && npm run check`
Expected: all tests pass; lint, typecheck and build succeed.

Run, in the backend repo: `./mvnw test -pl services/user-service`
Expected: `BUILD SUCCESS`

- [ ] **Step 2: Bring the new endpoint up**

Restart user-service from this branch the way the local stack runs it. That is `make run-user`, or
`make image SERVICE=user-service` and then restarting its container. Next, make sure the web dev
server on :3000 is serving this branch.

- [ ] **Step 3: Check the panel live with Claude in Chrome**

Signed in, open `http://localhost:3000/@373446922649051136` at a 1920px-wide window.
1. Click "Suggested accounts". Within 100ms, `document.getAnimations()` holds two `CSSTransition`s on
   the wrapper: `max-height` `0px → 300px` and `opacity` `0 → 1`, each `duration 400` and
   `easing "ease"`.
2. After 500ms, check the rects:

   | Element | Expected |
   |---|---|
   | Panel | 282 tall |
   | Title row | 40 |
   | Card | 160×222, first one 10px in from the row |
   | Avatar | 96×96 |
   | Follow | 110×28 |
   | Arrow button | 32×32 |
   | Header `margin-bottom` | 12px |

3. Click "Next". `scrollLeft` lands on `clientWidth / 4`, and the left arrow and left fade appear.
4. Click the toggle again. The reverse transitions run, the wrapper stays in the DOM with `inert`, and
   `margin-bottom` is back to 20px.
5. Tab through the page. Focus never lands inside the closed panel.
6. Reopen it and click "View all". The dialog opens on Suggested with the same people.

- [ ] **Step 4: Report**

Tell the user what was checked and with which numbers. Name any deviation from spec §3, along with
the fix or the reason it stays.
