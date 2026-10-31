# Sign-in location + new-device alerts — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the approximate place of every sign-in on the admin timeline, and tell an account's
owner in the web inbox when the account signs in from a device it has not used before.

**Architecture:**
- auth-service resolves the place offline, from DB-IP's City Lite file, when it writes a
  security-log entry.
- It decides "new device" from a client-sent `X-Device-Id`, checked against two Postgres tables
  inside the transaction that writes the refresh token.
- An alert is an outbox row on its own topic; notification-service turns it into a `SYSTEM`
  notice.
- The web's `src/proxy.ts` keeps the device id in an HttpOnly cookie and adds the header to auth
  calls. The admin console shows the place.

**Tech Stack:** Java 21 · Spring Boot 4.1 · Postgres + Flyway · Kafka outbox ·
`com.maxmind.db:maxmind-db` 4.2.0 · MongoDB · Next.js 16.4 (web: Vitest; console: `node --test`)

**Spec:** `docs/superpowers/specs/2026-10-24-sign-in-location-and-new-device-alerts-design.md`

**Where the work happens** — every repo is on branch `feat/sign-in-alerts`:

| Repo | Path | |
|---|---|---|
| backend | `/Users/minhhung/Projects/EProject/EProject4/tiktok-backend/.claude/worktrees/sign-in-alerts` | the worktree. Never the main checkout: it holds someone else's uncommitted `libs/kafka-lib` work |
| web | `/Users/minhhung/Projects/EProject/EProject4/tiktok-cloned` | |
| console | `/Users/minhhung/Projects/EProject/EProject4/tiktok-admin` | |

Backend paths are relative to the worktree. Two short forms:

- `AUTH/` = `services/auth-service/src/main/java/com/tiktok/authservice/`
- `AUTH_TEST/` = `services/auth-service/src/test/java/com/tiktok/authservice/`

## Global Constraints

- Backend conventions are those of `CLAUDE.md`: constructor injection (`@RequiredArgsConstructor`),
  no `@Data` on entities, records for DTOs, Flyway for every schema change. Infrastructure tables
  (no `BaseEntity`) are hard-deleted.
- `X-Device-Id`:
  - it counts only when it matches `[A-Za-z0-9-]{16,64}`; anything else is absent;
  - it is stored only as `HashUtils.sha256(id)`.
- Devices kept per account:
  - at most 50, ranked by `last_seen_at`;
  - forgotten once unused for `auth.retention.security-event-grace` (180d).
- Topic `auth.new-device-events`, key = userId, outbox aggregate type `NEW_DEVICE`. Never
  `auth.user-events`.
- The notice: `NotificationType.SYSTEM`, actorId `null`, referenceId `"account-security"`. No new
  `NotificationType`.
- Title: `Your account logged in from a new device`
- Body: `Your account logged in from device {deviceLabel} on {MM/dd HH:mm} +07 in {place}. If this wasn't you, secure your account immediately.`
  - no label: `Your account logged in from a new device on …`
  - no place: drop ` in {place}`
  - the time is `occurredAt` in `Asia/Ho_Chi_Minh`
- Place:
  - format: `"{city.names.en}, {country.names.en}"`; the country alone when there is no city;
    `null` when there is no country;
  - not looked up: loopback, site-local, link-local, any-local, `fc00::/7`;
  - addresses are parsed as literals only, never DNS.
- Column `auth_security_events.location VARCHAR(128)`.
- ADMIN accounts skip the whole new-device step: no device rows, no alert.
- The alert's device label:
  - the browser and the platform from `DeviceLabel`'s lists;
  - the OS version only when it matches `\d+(\.\d+)*`;
  - never the model.
- Neither the lookup nor the alert can fail or delay a sign-in.
- Web cookie `nowa_device`: HttpOnly, SameSite=Lax, `Path=/` (corrected in the final review: the
  page reads it too, and `/api/v1/auth` never reaches a page), max-age 31536000, Secure
  when `NODE_ENV === "production"`. Its value is a lowercase UUID; anything else gets replaced.
- Attribution, wherever a place is shown or explained: `<a href="https://db-ip.com">IP Geolocation by DB-IP</a>`.
- Commits:
  - each task commits only its own files (`git add <paths>`, never `-A` or `.`);
  - the message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A GeoIP file that is there but is not a database** (a truncated download): auth-service
   starts, logs one WARN, and signs people in without places. → Task 2,
   `aFileThatIsNotADatabaseDisablesTheLookup`.
2. **A place longer than its column.** The recorder swallows insert failures, so an overlong
   value would silently drop the whole log entry. → Task 3, `aPlaceLongerThanTheColumnIsCut`.
3. **A hostile `X-Device-Id`** (too long, spaces, `;`, non-ASCII, empty) is absent: it is never
   stored and throws nothing. → Task 3, `aHostileDeviceIdIsAbsent`.
4. **A sign-in late in the UTC day**: its alert carries the next day's date in +07. → Task 7,
   `aSignInAfterFiveUtcIsTheNextDayInVietnam`.
5. **A tampered device cookie** on the web is replaced with a fresh id, never forwarded. → Task 8,
   `replacesATamperedCookie`.

## File map

| Where | File | Responsibility |
|---|---|---|
| backend | `scripts/geoip/download.sh`, `Makefile`, `.gitignore`, `docker-compose.prod.yml`, `docs/operations.md` | the GeoIP file in dev and prod (Task 1) |
| auth | `AUTH/service/GeoIpLocator.java` | an address to a place (Task 2) |
| auth | `AUTH/service/RequestOrigin.java`, `AUTH/service/RequestOrigins.java` | what one request says about where it came from, read once (Task 3) |
| auth | `AUTH/service/SecurityEventRecorder.java`, `AUTH/entity/AuthSecurityEvent.java`, `AUTH/dto/response/SecurityEventResponse.java`, `AUTH/controller/AdminSecurityEventController.java`, `V18__security_event_location.sql` | the place on every log entry (Task 3) |
| auth | `AUTH/entity/KnownDevice.java`, `AUTH/repository/KnownDeviceRepository.java`, `AUTH/service/KnownDevices.java`, `V19__known_devices.sql` | the spec §6.3 rule (Task 4) |
| auth + event-schema | `AUTH/service/TokenIssuer.java`, `AUTH/service/DeviceLabel.java`, `AUTH/event/producer/*`, `libs/event-schema/.../NewDeviceSignInEvent.java` | the alert leaving auth-service (Task 5) |
| auth | `AUTH/service/ExpiredRecordCleanup.java`, `AUTH/service/AccountClosureServiceImpl.java` | forgetting devices (Task 6) |
| notification | `NewDeviceSignInEventConsumer.java` | the notice (Task 7) |
| web | `src/proxy.ts` | the device cookie and header (Task 8) |
| web | `src/components/layout/ActivityDrawer.tsx`, `src/lib/api/notifications.ts`, `src/app/privacy/page.tsx` | the link to Settings, the privacy text (Task 9) |
| console | `lib/activity.ts`, `lib/api/types.ts`, the activity page, `lib/mock/security-events.ts` | the place on the timeline (Task 10) |

---

### Task 1: The GeoIP file reaches dev and production

**Files:**
- Create: `scripts/geoip/download.sh`
- Modify: `Makefile` (`.PHONY`, the help block under "Infrastructure:", a `geoip` target),
  `.gitignore`, `docker-compose.prod.yml`, `docs/operations.md`

**Interfaces:**
- Produces:
  - dev: `data/geoip/dbip-city-lite.mmdb`;
  - prod: the env `GEOIP_DATABASE=/geoip/dbip-city-lite.mmdb` on auth-service, which Task 2's
    property reads.

- [ ] **Step 1: Write `scripts/geoip/download.sh <dir>`** — POSIX `sh`, running under busybox
  (alpine) and macOS alike:
  - The target is `<dir>/dbip-city-lite.mmdb`. Run `mkdir -p <dir>` first.
  - If the target exists and `find "$target" -mtime -30` prints it:
    - print `GeoIP: <target> is less than a month old, kept`;
    - exit 0.
  - Months to try: the current UTC month `YYYY-MM`, then the previous one (January rolls back to
    December of the year before). Strip the leading zero before doing arithmetic on the month:
    `08` is not a number to `sh`.
  - For each month:
    - run `curl -fsSL --max-time 600 https://download.db-ip.com/free/dbip-city-lite-$ym.mmdb.gz | gunzip > "$target.tmp"`;
    - if that succeeds and `$target.tmp` is non-empty: `mv "$target.tmp" "$target"`, print
      `GeoIP: downloaded dbip-city-lite-$ym`, exit 0;
    - otherwise run `rm -f "$target.tmp"` and try the next month.
  - If both fail:
    - print to stderr `WARNING: GeoIP download failed; sign-ins get no place until a later run succeeds`;
    - exit 0.

- [ ] **Step 2: Wire it into dev**
  - `Makefile`:
    - add `geoip` to `.PHONY`;
    - add the target `geoip:` → `sh scripts/geoip/download.sh data/geoip`;
    - add this help line under "Infrastructure:":
      `@echo "    make geoip              Tải DB-IP City Lite vào data/geoip (bỏ qua nếu mới hơn 1 tháng)"`.
  - `.gitignore`: add `data/geoip/` under the comment
    `# GeoIP database (make geoip) — ~120 MB, never committed`.

- [ ] **Step 3: Run it**
  - Run `make geoip`.
    Expected: `GeoIP: downloaded dbip-city-lite-2026-10` (or the month before it), and
    `ls -lh data/geoip` shows a file of about 121M.
  - Run `make geoip` again. Expected: `… is less than a month old, kept`.
  - Run `git status --short`. Expected: nothing under `data/`.

- [ ] **Step 4: Production compose**
  - Next to `cassandra-init`, add:

```yaml
  # Baked into an image rather than bind-mounted (see cassandra-init). Fetches DB-IP's City Lite
  # into geoip-data once the copy there is a month old. A failed download still exits 0, so a
  # deploy never waits on db-ip.com; auth-service then runs without places and logs a WARN.
  geoip-init:
    build:
      context: ./scripts/geoip
      dockerfile_inline: |
        FROM alpine:3.22
        RUN apk add --no-cache curl
        COPY download.sh /download.sh
        ENTRYPOINT ["sh", "/download.sh", "/data"]
    volumes:
      - geoip-data:/data
    restart: "no"
```

  - In `auth-service`:
    - add `geoip-init: { condition: service_completed_successfully }` to `depends_on`;
    - add `GEOIP_DATABASE: /geoip/dbip-city-lite.mmdb` to `environment`;
    - add `volumes: [ "geoip-data:/geoip:ro" ]`.
  - Add `geoip-data:` to the top-level `volumes:`.
  - Run `docker compose -f docker-compose.prod.yml config --no-interpolate -q`. Expected: exit 0.

- [ ] **Step 5: `docs/operations.md`**
  - Add `## 6. Vị trí đăng nhập (GeoIP)`, written in Vietnamese like the rest of the file. It
    covers:
    - the file comes from DB-IP City Lite (CC BY 4.0) via `geoip-init`;
    - each deploy refreshes it once it is more than a month old;
    - after a deploy, check auth-service's startup log for the INFO or WARN line from Task 2;
    - attribution is required (admin activity page, privacy page).
  - Add the spec §8.2 requirements:
    - the web image is built with `API_GATEWAY_URL` pointing at the gateway over Coolify's
      internal network, not `https://api.nowaa.site`;
    - Traefik must trust the tunnel connector (`forwardedHeaders.trustedIPs`), or it replaces the
      address Cloudflare wrote;
    - the post-deploy check: sign in on the web from a phone on 4G, and the admin timeline shows
      the carrier's address and a place.

- [ ] **Step 6: Commit**

```bash
git add scripts/geoip/download.sh Makefile .gitignore docker-compose.prod.yml docs/operations.md
git commit -m "feat(ops): fetch DB-IP City Lite for sign-in locations"
```

---

### Task 2: `GeoIpLocator` — an address to a place, offline

**Files:**
- Modify:
  - `pom.xml`: property `<maxmind-db.version>4.2.0</maxmind-db.version>`, plus a
    `dependencyManagement` entry for `com.maxmind.db:maxmind-db`;
  - `services/auth-service/pom.xml`: the dependency, no version;
  - `services/auth-service/src/main/resources/application.yml`: under `auth:` add
    `geoip.database: ${GEOIP_DATABASE:../../data/geoip/dbip-city-lite.mmdb}`, which resolves from
    the module directory, where `make run-auth` and IntelliJ run.
- Create:
  - `AUTH/service/GeoIpLocator.java`;
  - `services/auth-service/src/test/resources/geoip/GeoIP2-City-Test.mmdb`, downloaded from
    `https://raw.githubusercontent.com/maxmind/MaxMind-DB/main/test-data/GeoIP2-City-Test.mmdb`
    (18,035 bytes; the repo is Apache-2.0 / MIT).
- Test: `AUTH_TEST/service/GeoIpLocatorTest.java`

**Interfaces:**
- Produces: `@Component public class GeoIpLocator implements AutoCloseable`
  - `GeoIpLocator(@Value("${auth.geoip.database:}") String database)`
  - `public String locate(String address)`: the place, or `null`; never throws
  - `static InetAddress literal(String address)`: package-private; `null` for anything that is not
    an IP literal

- [ ] **Step 1: Write the failing test.** Construct it with
  `new GeoIpLocator("src/test/resources/geoip/GeoIP2-City-Test.mmdb")`, which tests resolve from
  the module directory.

```java
@Test void anIpv4AddressResolvesToCityAndCountry() {
    assertThat(locator.locate("81.2.69.160")).isEqualTo("London, United Kingdom");
}
@Test void anIpv6AddressWithoutACityResolvesToItsCountry() {
    assertThat(locator.locate("2001:218::1")).isEqualTo("Japan");
}
@Test void anIpv4MappedAddressIsLookedUpAsIpv4() {
    assertThat(locator.locate("::ffff:81.2.69.160")).isEqualTo("London, United Kingdom");
}
@Test void privateAndLocalAddressesAreNotLookedUp() {
    for (String a : List.of("127.0.0.1", "::1", "10.1.2.3", "172.16.0.1", "192.168.1.1",
            "169.254.1.1", "fe80::1", "fd00::1", "0.0.0.0")) {
        assertThat(locator.locate(a)).as(a).isNull();
    }
}
@Test void anAddressOutsideTheFileHasNoPlace() {
    assertThat(locator.locate("198.51.100.1")).isNull();  // TEST-NET-2
}
@Test void onlyLiteralsAreParsed_neverAHostname() {
    for (String a : List.of("example.com", "abc.def", "zz::1", "1.2.3", "1.2.3.4:8080", "", " ")) {
        assertThat(GeoIpLocator.literal(a)).as(a).isNull();
    }
    assertThat(GeoIpLocator.literal(null)).isNull();
}
@Test void anUnsetPathDisablesTheLookup() {
    assertThat(new GeoIpLocator("").locate("81.2.69.160")).isNull();
}
@Test void aMissingFileDisablesTheLookup() {
    assertThat(new GeoIpLocator("/nonexistent/dbip.mmdb").locate("81.2.69.160")).isNull();
}
@Test void aFileThatIsNotADatabaseDisablesTheLookup(@TempDir Path dir) throws IOException {
    Path junk = Files.writeString(dir.resolve("dbip.mmdb"), "<html>502 Bad Gateway</html>");
    assertThat(new GeoIpLocator(junk.toString()).locate("81.2.69.160")).isNull();
}
```

- [ ] **Step 2: Run it**
  - Run: `./mvnw -q -pl services/auth-service -am test -Dtest=GeoIpLocatorTest -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: a compilation failure, because `GeoIpLocator` does not exist.

- [ ] **Step 3: Add the dependency and implement `GeoIpLocator`**
  - The constructor:
    - a blank path: one WARN, `GeoIP lookup disabled: auth.geoip.database is not set — sign-ins get no place`;
    - otherwise `new Reader(new File(path))` (memory-mapped by default):
      - on success, one INFO: `GeoIP lookup enabled from {path} (built {date})`, where the date
        is the build date as `reader.getMetadata()` reports it;
      - on `IOException`, one WARN with the path and the exception's message, no stack trace,
        and the lookup stays disabled.
  - `literal`:
    - strip the input first;
    - two shapes go to `InetAddress.getByName`, with `UnknownHostException` → `null`:
      - an IPv4 dotted quad, `\d{1,3}(\.\d{1,3}){3}`;
      - a string made only of `[0-9A-Fa-f:.]` that contains `:`;
    - everything else → `null`.
    - The reason: `getByName` parses a literal only when the first character is a hex digit or
      `:`. Anything else goes to DNS, including `zz::1`, a dotless name, or a hex-looking one like
      `abc.def`.
  - `locate`:
    - `null` when the lookup is disabled, the address is not a literal, or the address is
      loopback, site-local, link-local, any-local, or `(bytes[0] & 0xfe) == 0xfc` for IPv6;
    - otherwise `reader.get(ip, Place.class)`, where `Place` is decoded by maxmind-db from record
      component names:
      - `private record Place(Named city, Named country) {}`
      - `private record Named(Map<String, String> names) {}`
    - it returns `"City, Country"` from `names.get("en")`, or the country alone;
    - it catches `IOException | RuntimeException`, logs at debug, and returns `null`.
  - `close()` closes the reader. Spring calls it on shutdown because the class is `AutoCloseable`.

- [ ] **Step 4: Run it again**
  - Run the Step 2 command.
  - Expected: 9 tests, 0 failures.
  - Then run `./mvnw -q -pl services/auth-service dependency:tree -Dincludes=com.fasterxml.jackson.core`.
    Expected: nothing comes in through `maxmind-db`.

- [ ] **Step 5: Commit**

```bash
git add pom.xml services/auth-service/pom.xml services/auth-service/src/main/resources/application.yml \
  services/auth-service/src/main/java/com/tiktok/authservice/service/GeoIpLocator.java \
  services/auth-service/src/test/java/com/tiktok/authservice/service/GeoIpLocatorTest.java \
  services/auth-service/src/test/resources/geoip/GeoIP2-City-Test.mmdb
git commit -m "feat(auth-service): resolve an address to an approximate place offline"
```

---

### Task 3: Every security-log entry gets a place

**Files:**
- Create:
  - `AUTH/service/RequestOrigin.java`
  - `AUTH/service/RequestOrigins.java`
  - `services/auth-service/src/main/resources/db/migration/V18__security_event_location.sql`
  - `services/auth-service/src/test/resources/application-test.yml`
- Modify:
  - `AUTH/service/SecurityEventRecorder.java`: request reading moves out to `RequestOrigins`
  - `AUTH/entity/AuthSecurityEvent.java`
  - `AUTH/dto/response/SecurityEventResponse.java`
  - `AUTH/controller/AdminSecurityEventController.java`
  - `docs/auth-service-api.md` §3.18
- Test:
  - `AUTH_TEST/service/RequestOriginsTest.java` (create)
  - `AUTH_TEST/service/SecurityEventRecorderTest.java` (create)
  - `AUTH_TEST/service/AuthServiceImplTest.java` (add one case)

**Interfaces:**
- Consumes: `GeoIpLocator.locate(String)` (Task 2).
- Produces:
  - `public record RequestOrigin(String address, String userAgent, String platformVersion, String model, String deviceId, String location)`
    - `public static final RequestOrigin NONE` (all null)
    - `public String deviceLabel()` = `DeviceLabel.of(userAgent, platformVersion, model)`
  - `@Component public class RequestOrigins`
    - constructor `(InternalApiToken internalApiToken, GeoIpLocator geoIpLocator)`
    - `public RequestOrigin current()`: `NONE` off a request thread; never throws
  - `SecurityEventRecorder`
    - new constructor `(AuthSecurityEventRepository, PlatformTransactionManager, RequestOrigins)`
    - new overload `public void record(Long userId, SecurityEventType type, String detail, RequestOrigin origin)`
    - the existing `record(userId, type)` and `record(userId, type, detail)` keep their signatures
  - `SecurityEventResponse(Long id, SecurityEventType eventType, String ipAddress, String userAgent, String deviceLabel, String deviceModel, String location, String detail, Instant createdAt)`

- [ ] **Step 1: Write the failing tests**
  - `RequestOriginsTest` is a unit test:
    - build the token from `MockEnvironment` with active profile `test`
      (`InternalApiToken.from(env)`), and the locator on the Task 2 fixture;
    - put a `MockHttpServletRequest` on the thread with `RequestContextHolder`, and reset it in
      `@AfterEach`.

```java
@Test void theGatewaysAddressCountsOnlyBesideTheInternalToken() {
    arrive(Map.of("X-Client-Ip", "81.2.69.160", InternalApiToken.HEADER, token.value()));
    assertThat(origins.current()).extracting(RequestOrigin::address, RequestOrigin::location)
            .containsExactly("81.2.69.160", "London, United Kingdom");
    arrive(Map.of("X-Client-Ip", "81.2.69.160"));                 // no token: the socket peer
    assertThat(origins.current()).extracting(RequestOrigin::address, RequestOrigin::location)
            .containsExactly("127.0.0.1", null);
}
@Test void aDeviceIdThatFitsIsRead() {
    arrive(Map.of("X-Device-Id", "test-device-aaaaaaaa"));
    assertThat(origins.current().deviceId()).isEqualTo("test-device-aaaaaaaa");
}
@Test void aHostileDeviceIdIsAbsent() {
    for (String id : List.of("", "short", "x".repeat(65), "has space aaaaaaaaaaa",
            "semi;colon-aaaaaaaaaaa", "ünïcødé-aaaaaaaaaaaa")) {
        arrive(Map.of("X-Device-Id", id));
        assertThat(origins.current().deviceId()).as(id).isNull();
    }
}
@Test void clientHintsAreUnquoted() {
    arrive(Map.of("Sec-CH-UA-Model", "\"Pixel 9\"", "Sec-CH-UA-Platform-Version", "\"\""));
    assertThat(origins.current()).extracting(RequestOrigin::model, RequestOrigin::platformVersion)
            .containsExactly("Pixel 9", null);
}
@Test void offARequestThreadNothingIsKnown() {
    RequestContextHolder.resetRequestAttributes();
    assertThat(origins.current()).isEqualTo(RequestOrigin.NONE);
}
```

  - `SecurityEventRecorderTest` is a unit test with mocks:
    - a mocked `AuthSecurityEventRepository`;
    - a mocked `PlatformTransactionManager` whose `getTransaction(any())` returns
      `new SimpleTransactionStatus()`;
    - a mocked `RequestOrigins`;
    - an `ArgumentCaptor<AuthSecurityEvent>` on `save`.

```java
@Test void thePlaceIsStoredWithTheEntry() {
    recorder.record(7L, SecurityEventType.LOGIN_FAILED, null,
            new RequestOrigin("81.2.69.160", null, null, null, null, "London, United Kingdom"));
    assertThat(saved().getLocation()).isEqualTo("London, United Kingdom");
    assertThat(saved().getIpAddress()).isEqualTo("81.2.69.160");
}
@Test void aPlaceLongerThanTheColumnIsCut() {
    recorder.record(7L, SecurityEventType.LOGIN_FAILED, null,
            new RequestOrigin("81.2.69.160", null, null, null, null, "x".repeat(200)));
    assertThat(saved().getLocation()).hasSize(128);
}
@Test void theShortOverloadsReadTheCurrentRequest() {
    when(requestOrigins.current()).thenReturn(new RequestOrigin("81.2.69.160", null, null, null, null, "London, United Kingdom"));
    recorder.record(7L, SecurityEventType.LOGOUT);
    assertThat(saved().getLocation()).isEqualTo("London, United Kingdom");
}
```

  - `AuthServiceImplTest`: add `aFailedSignInIsFiledWithItsPlace`:
    - `arriveFrom("81.2.69.160", <a Chrome UA>)`;
    - `login` with a wrong password for a verified account, expecting `InvalidCredentialsException`;
    - the `LOGIN_FAILED` row's `getLocation()` is `"London, United Kingdom"`.

- [ ] **Step 2: Run them**
  - Run: `./mvnw -q -pl services/auth-service -am test -Dtest='RequestOriginsTest,SecurityEventRecorderTest,AuthServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: a compilation failure (`RequestOrigin`, `RequestOrigins` and `getLocation` are
    missing).

- [ ] **Step 3: Implement**
  - `V18__security_event_location.sql`:
    - a comment: the approximate place of `ip_address`, resolved when the row was written; null
      before this migration and wherever the address resolves to nothing;
    - `ALTER TABLE auth_security_events ADD COLUMN location VARCHAR(128);`
  - `AuthSecurityEvent`: `@Column(name = "location", length = 128) private String location;`
  - `RequestOrigins`:
    - it takes over the recorder's `CLIENT_IP_HEADER`, `MODEL_HEADER`,
      `PLATFORM_VERSION_HEADER`, `clientIp(...)` and `hint(...)`, Javadoc included;
    - it reads `X-Device-Id` (`request.getHeader`, stripped) and keeps it only when it matches
      `[A-Za-z0-9-]{16,64}`;
    - `location` = `geoIpLocator.locate(address)`.
  - `SecurityEventRecorder`:
    - `MAX_LOCATION = 128`;
    - the 4-arg overload builds the entity from the origin's fields (`deviceLabel()`,
      `truncate(location, MAX_LOCATION)`) inside the try that already guards the write;
    - the 3-arg overload calls `requestOrigins.current()` inside that same try, so it still
      cannot throw.
  - `SecurityEventResponse` + `AdminSecurityEventController.toResponse`: carry `location`, in the
    position listed in Interfaces.
  - `application-test.yml`:

```yaml
auth:
  geoip:
    # The MaxMind test database, never the downloaded file: tests assert exact places.
    database: src/test/resources/geoip/GeoIP2-City-Test.mmdb
```

  - `docs/auth-service-api.md` §3.18:
    - add `location` to the element list;
    - explain it in one sentence: the approximate place of `ipAddress` ("Ho Chi Minh City,
      Vietnam", sometimes the country only), looked up when the entry was written; null for older
      entries and for addresses that resolve to nothing.

- [ ] **Step 4: Run the Step 2 command, then the whole module**
  - Expected: all green.
  - Then run `./mvnw -q -pl services/auth-service -am test`. Expected: BUILD SUCCESS. The
    baseline was 269 tests.

- [ ] **Step 5: Commit**

```bash
git add services/auth-service/src docs/auth-service-api.md
git commit -m "feat(auth-service): record the approximate place of every security-log entry"
```

  `services/auth-service/src` holds only this task's changes at this point. Check with
  `git status --short` before adding.

---

### Task 4: Remembering devices — the spec §6.3 rule

**Files:**
- Create:
  - `services/auth-service/src/main/resources/db/migration/V19__known_devices.sql`
  - `AUTH/entity/KnownDevice.java`
  - `AUTH/repository/KnownDeviceRepository.java`
  - `AUTH/service/KnownDevices.java`
- Test: `AUTH_TEST/service/KnownDevicesTest.java`

**Interfaces:**
- Produces:
  - `@Component public class KnownDevices`
    - `static final int MAX_PER_ACCOUNT = 50`
    - `@Transactional public boolean signedIn(Long userId, String deviceId)`: `true` when the
      owner should be told. `deviceId` may be null. It joins the caller's transaction.
    - `@Transactional public void refreshed(Long userId, String deviceId)`: remembers the device,
      and never asks for an alert
    - `@Transactional public void forget(Long userId)`
  - `KnownDeviceRepository extends JpaRepository<KnownDevice, Long>`. All of these are native
    `@Modifying`/`@Query` methods except the `EXISTS` query:
    - `int insertIfAbsent(Long id, Long userId, String deviceHash, Instant now)`:
      `INSERT … ON CONFLICT (user_id, device_hash) DO NOTHING`
    - `int touch(Long userId, String deviceHash, Instant now)`
    - `int markAccount(Long userId)`:
      `INSERT INTO known_device_accounts (user_id) VALUES (:userId) ON CONFLICT DO NOTHING`
    - `boolean accountHasHadDevice(Long userId)`:
      `SELECT EXISTS (SELECT 1 FROM known_device_accounts WHERE user_id = :userId)`
    - `int evictBeyond(Long userId, int keep)`:
      `DELETE … WHERE id IN (SELECT id … WHERE user_id = :userId ORDER BY last_seen_at DESC, id DESC OFFSET :keep)`
    - `int deleteAllByUserId(Long userId)` and `int unmarkAccount(Long userId)`

- [ ] **Step 1: Write the failing test.**
  - `@SpringBootTest @Testcontainers @ActiveProfiles("test")`, with the container and mock block
    copied from `ExpiredRecordCleanupTest` (Postgres, Redis, `@MockitoBean` KafkaTemplate and
    MailService).
  - `@BeforeEach`, in this order: delete from `known_devices`, then from `known_device_accounts`
    (both via `JdbcTemplate`), then `userRepository.deleteAll()`, then save one `USER` account
    as `userId`.
  - The ids: `A = "device-aaaaaaaaaaaa"`, `B = "device-bbbbbbbbbbbb"`.

```java
@Test void theFirstDeviceAnAccountIsSeenOnIsSilent() { assertThat(devices.signedIn(userId, A)).isFalse(); }
@Test void theSameDeviceAgainIsSilent_andIsTouched() {
    devices.signedIn(userId, A);
    Instant before = lastSeen(A);
    assertThat(devices.signedIn(userId, A)).isFalse();
    assertThat(lastSeen(A)).isAfter(before);
}
@Test void anotherDeviceAlerts() {
    devices.signedIn(userId, A);
    assertThat(devices.signedIn(userId, B)).isTrue();
}
@Test void noIdAlertsOnlyOnceTheAccountHasHadADevice() {
    assertThat(devices.signedIn(userId, null)).isFalse();
    devices.signedIn(userId, A);
    assertThat(devices.signedIn(userId, null)).isTrue();
}
@Test void aRefreshRemembersWithoutEverAlerting() {
    devices.refreshed(userId, A);                        // first device, through a refresh
    assertThat(devices.signedIn(userId, A)).isFalse();
    devices.refreshed(userId, B);                        // a second device, still no alert
    assertThat(devices.signedIn(userId, B)).isFalse();
}
@Test void deviceIdsAreStoredHashed() {
    devices.signedIn(userId, A);
    assertThat(jdbc.queryForList("select device_hash from known_devices", String.class))
            .containsExactly(HashUtils.sha256(A));
}
@Test void aDoubleSubmitFromOneNewDeviceAlertsOnce() throws Exception {
    devices.signedIn(userId, A);
    // Two threads, each: transactionTemplate.execute(s -> devices.signedIn(userId, B)), started
    // together off a CountDownLatch; collect both results.
    assertThat(results).containsExactlyInAnyOrder(true, false);
}
@Test void theFiftyFirstDeviceEvictsTheLeastRecentlyUsed() {
    for (int i = 0; i < 50; i++) devices.signedIn(userId, "device-%012d".formatted(i));
    devices.signedIn(userId, "device-new-device-51");
    assertThat(count("known_devices")).isEqualTo(50);
    assertThat(devices.signedIn(userId, "device-%012d".formatted(0))).isTrue();   // new again
}
@Test void aRollbackLeavesNoDevice() {
    assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(s -> {
        devices.signedIn(userId, A);
        throw new IllegalStateException("sign-in failed after the device step");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(count("known_devices")).isZero();
    assertThat(count("known_device_accounts")).isZero();
}
```

- [ ] **Step 2: Run it**
  - Run: `./mvnw -q -pl services/auth-service -am test -Dtest=KnownDevicesTest -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: a compilation failure.

- [ ] **Step 3: Implement**
  - `V19__known_devices.sql`:

```sql
-- Devices an account has signed in or refreshed a session from, by the client's X-Device-Id;
-- only its SHA-256 is kept. Infrastructure table like remembered_devices: no BaseEntity,
-- hard-deleted. At most 50 per account (KnownDevices), forgotten 180 days after last use
-- (ExpiredRecordCleanup), removed when the account is erased.
CREATE TABLE known_devices (
    id           BIGINT PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id),
    device_hash  VARCHAR(64) NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_known_devices_user_device UNIQUE (user_id, device_hash)
);

CREATE INDEX idx_known_devices_last_seen ON known_devices (last_seen_at);

-- One row per account that has ever had a known device. Never swept: it is what tells an
-- account's first device from a returning account whose devices all aged out, which must
-- still alert.
CREATE TABLE known_device_accounts (
    user_id BIGINT PRIMARY KEY REFERENCES users (id)
);
```

  - `KnownDevice`:
    - `@Entity @Table(name = "known_devices")`, `@Getter @Builder @NoArgsConstructor @AllArgsConstructor`;
    - fields `id`, `userId`, `deviceHash`, `lastSeenAt`;
    - the Javadoc says what it is, in the style of `RememberedDevice`;
    - every write is a native query, so it needs no `@PrePersist`.
  - `KnownDevices.signedIn`, in this order:
    1. `deviceId == null` → return `repository.accountHasHadDevice(userId)`.
    2. `hash = HashUtils.sha256(deviceId)`, `now = Instant.now()`.
    3. If `insertIfAbsent(SnowflakeIdGenerator.nextId(), userId, hash, now) == 0`, the device is
       known: call `touch(userId, hash, now)` and return `false`.
    4. `boolean first = repository.markAccount(userId) == 1`.
    5. `evictBeyond(userId, MAX_PER_ACCOUNT)`.
    6. Return `!first`.
  - `refreshed`: steps 1–5 without an answer (a null id returns at once).
  - `forget`: `deleteAllByUserId` + `unmarkAccount`.

- [ ] **Step 4: Run the Step 2 command.** Expected: 9 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add services/auth-service/src/main/resources/db/migration/V19__known_devices.sql \
  services/auth-service/src/main/java/com/tiktok/authservice/entity/KnownDevice.java \
  services/auth-service/src/main/java/com/tiktok/authservice/repository/KnownDeviceRepository.java \
  services/auth-service/src/main/java/com/tiktok/authservice/service/KnownDevices.java \
  services/auth-service/src/test/java/com/tiktok/authservice/service/KnownDevicesTest.java
git commit -m "feat(auth-service): remember the devices an account signs in from"
```

---

### Task 5: The alert leaves auth-service

**Files:**
- Create:
  - `libs/event-schema/src/main/java/com/tiktok/event/user/NewDeviceSignInEvent.java`
  - `AUTH_TEST/service/NewDeviceSignInTest.java`
- Modify:
  - `libs/event-schema/src/test/resources/event-json.json` (recorded)
  - `AUTH/service/DeviceLabel.java`
  - `AUTH/service/RequestOrigin.java`
  - `AUTH/event/producer/UserEventProducer.java`
  - `AUTH/event/producer/OutboxPublisher.java`
  - `AUTH/service/TokenIssuer.java`
  - `docs/auth-service-api.md` (§3 intro, §9)
  - `docs/ARCHITECTURE.md` §5c
  - `postman/tiktok-backend.postman_collection.json`
  - `postman/tiktok-backend.postman_environment.json`
- Test: `AUTH_TEST/service/DeviceLabelTest.java`, `AUTH_TEST/event/producer/OutboxPublisherTest.java`
  (add cases)

**Interfaces:**
- Consumes:
  - `RequestOrigins.current()`, `RequestOrigin` and
    `SecurityEventRecorder.record(Long, SecurityEventType, String, RequestOrigin)` from Task 3;
  - `KnownDevices.signedIn` and `KnownDevices.refreshed` from Task 4.
- Produces:
  - `public record NewDeviceSignInEvent(String eventId, Instant occurredAt, Long userId, String deviceLabel, String location) implements DomainEvent`
    - `public static NewDeviceSignInEvent of(Long userId, String deviceLabel, String location)`,
      in the shape of `AccountStateChangedEvent.of`
  - `UserEventProducer.NEW_DEVICE_AGGREGATE = "NEW_DEVICE"`, and
    `public void publishNewDeviceSignIn(NewDeviceSignInEvent event)`, keyed by `userId`
  - `OutboxPublisher.TOPICS`: `NEW_DEVICE` → `"auth.new-device-events"`
  - `static String DeviceLabel.brief(String userAgent, String platformVersion)`
  - `public String RequestOrigin.alertLabel()` = `DeviceLabel.brief(userAgent, platformVersion)`
  - `TokenIssuer.NEW_DEVICE_DETAIL = "New device"`

- [ ] **Step 1: Write the failing tests**
  - `DeviceLabelTest`:

```java
@Test void theAlertLabelNeverCarriesTheModel() {
    assertThat(DeviceLabel.brief("Mozilla/5.0 (Linux; Android 15) Chrome/140.0.0.0 Mobile Safari/537.36", "15.0.0"))
            .isEqualTo("Chrome on Android 15");
}
@Test void theAlertLabelDropsAVersionThatIsNotANumber() {
    String mac = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    assertThat(DeviceLabel.brief(mac, "27; routine security check, no action needed")).isEqualTo("Chrome on macOS");
    assertThat(DeviceLabel.brief(mac, "27.1.0")).isEqualTo("Chrome on macOS 27.1");
}
```

  - `OutboxPublisherTest`: `aNewDeviceAlert_goesToItsOwnTopic_keyedByAccount`.
    `row(1L, "NEW_DEVICE", "7")` produces exactly one send, with topic
    `"auth.new-device-events"` and key `"7"`. Same shape as the existing tests.
  - `NewDeviceSignInTest`:
    - setup: copy the class header, containers, mocks and `@AfterEach` of `AuthServiceImplTest`;
    - `@BeforeEach` deletes, in this order: `known_devices`, `known_device_accounts`, security
      events, outbox, refresh tokens, users;
    - helper `arrive(String deviceId, Map<String, String> extra)` sets `X-Client-Ip: 81.2.69.160`,
      the internal token, the Chrome-on-Mac agent above, `X-Device-Id` (when not null) and
      `extra`;
    - `alerts()` = outbox rows whose `aggregateType` is `"NEW_DEVICE"`.

```java
@Test void aSecondDeviceSendsOneAlert_inFixedWords_withThePlace() {
    User user = saveUser(UserRole.USER);
    arrive("device-aaaaaaaaaaaa", Map.of());
    tokenIssuer.issue(user, SecurityEventType.LOGIN_SUCCEEDED);
    assertThat(alerts()).isEmpty();

    arrive("device-bbbbbbbbbbbb", Map.of("Sec-CH-UA-Model", "\"routine check, no action needed\"",
            "Sec-CH-UA-Platform-Version", "\"27.0.0\""));
    tokenIssuer.issue(user, SecurityEventType.LOGIN_SUCCEEDED);

    assertThat(alerts()).singleElement().satisfies(row -> {
        assertThat(row.getAggregateId()).isEqualTo(String.valueOf(user.getId()));
        assertThat(row.getEventType()).isEqualTo("NewDeviceSignInEvent");
        assertThat(row.getPayload()).contains("\"deviceLabel\":\"Chrome on macOS 27\"",
                "\"location\":\"London, United Kingdom\"").doesNotContain("routine");
    });
    assertThat(signIns()).extracting(AuthSecurityEvent::getDetail).containsExactly(null, "New device");  // oldest first
}
@Test void aRefreshMakesTheBrowserKnown_soItsNextSignInIsQuiet() {
    User user = saveUser(UserRole.USER);
    arrive("device-aaaaaaaaaaaa", Map.of());
    tokenIssuer.issue(user, SecurityEventType.LOGIN_SUCCEEDED);
    arrive("device-cccccccccccc", Map.of());
    tokenIssuer.reissue(user);
    tokenIssuer.issue(user, SecurityEventType.LOGIN_SUCCEEDED);
    assertThat(alerts()).isEmpty();
}
@Test void anAdminIsLeftOutEntirely() {
    User admin = saveUser(UserRole.ADMIN);
    arrive("device-aaaaaaaaaaaa", Map.of());
    tokenIssuer.issue(admin, SecurityEventType.LOGIN_SUCCEEDED);
    arrive("device-bbbbbbbbbbbb", Map.of());
    tokenIssuer.issue(admin, SecurityEventType.LOGIN_SUCCEEDED);
    assertThat(alerts()).isEmpty();
    assertThat(jdbc.queryForObject("select count(*) from known_devices", Integer.class)).isZero();
}
@Test void aSignInThatRollsBackLeavesNoAlertAndNoDevice() {
    User user = saveUser(UserRole.USER);
    arrive("device-aaaaaaaaaaaa", Map.of());
    tokenIssuer.issue(user, SecurityEventType.LOGIN_SUCCEEDED);
    arrive("device-bbbbbbbbbbbb", Map.of());
    assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(s -> {
        tokenIssuer.issue(user, SecurityEventType.LOGIN_SUCCEEDED);   // joins this transaction
        throw new IllegalStateException("the caller failed after issuing");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(alerts()).isEmpty();
    assertThat(jdbc.queryForObject("select count(*) from known_devices", Integer.class)).isEqualTo(1);
}
```

  - `NewDeviceSignInEvent` is pinned by `EventJsonCompatibilityTest`, which finds every event
    class by itself.

- [ ] **Step 2: Run them**
  - Run: `./mvnw -q -pl services/auth-service -am test -Dtest='DeviceLabelTest,OutboxPublisherTest,NewDeviceSignInTest' -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: a compilation failure.

- [ ] **Step 3: Implement**
  - `NewDeviceSignInEvent`:
    - Javadoc: what it is; its own topic, because of `auth.user-events`; `deviceLabel` holds
      fixed words only; both strings may be null.
  - `DeviceLabel.brief`:
    - `of(userAgent, v, null)`;
    - `v` is the stripped `platformVersion` when it matches `\d+(\.\d+)*`, and `null` otherwise.
  - `UserEventProducer` and `OutboxPublisher`: add the aggregate and its topic.
  - `TokenIssuer`:
    - inject `RequestOrigins`, `KnownDevices` and `UserEventProducer`;
    - `issue`:
      - resolve `RequestOrigin origin = requestOrigins.current()` once, before the transaction;
      - inside `transactionTemplate.execute`, after `mint(user)`, compute `boolean newDevice`:
        - `false` for `UserRole.ADMIN`;
        - otherwise `knownDevices.signedIn(user.getId(), origin.deviceId())`, and when that is
          true, `userEventProducer.publishNewDeviceSignIn(NewDeviceSignInEvent.of(user.getId(), origin.alertLabel(), origin.location()))`;
      - return both from the transaction, through a small private record;
      - after it, call `securityEventRecorder.record(user.getId(), signIn, newDevice ? NEW_DEVICE_DETAIL : null, origin)`;
    - `reissue`:
      - after `mint(user)`, for a non-ADMIN user, call
        `knownDevices.refreshed(user.getId(), requestOrigins.current().deviceId())`;
      - update the Javadoc: a refresh still writes no log entry, but it remembers the device.
  - Record the event JSON:
    - run `./mvnw -q -pl libs/event-schema test -Devent-json.record=true`;
    - review the new `NewDeviceSignInEvent` entry in `event-json.json`. It must have exactly the
      five fields;
    - then run `./mvnw -q -pl libs/event-schema test`. Expected: green.
  - Docs:
    - `docs/auth-service-api.md`, in the §3 intro, before 3.1, written in Vietnamese: the
      `X-Device-Id` header —
      - which calls it goes on: login, register/complete, the two oauth calls, the oauth link
        calls, and refresh;
      - its format (16–64 `[A-Za-z0-9-]`, otherwise ignored);
      - it is not a credential;
      - without it, every sign-in on an account that has had a device counts as new, so the
        mobile app should send it;
      - refresh never alerts;
      - ADMIN accounts are skipped.
    - `docs/auth-service-api.md` §9: a row for `auth.new-device-events` — `NewDeviceSignInEvent`,
      payload `eventId, occurredAt, userId, deviceLabel, location`, Outbox `NEW_DEVICE`, key
      `userId`, consumed by notification-service. Name it in the "tách nhau" paragraph too.
    - `docs/ARCHITECTURE.md` §5c: the same row, in that table's columns.
  - Postman:
    - add `{"key": "X-Device-Id", "value": "{{deviceId}}"}` to every request whose URL ends in
      `/auth/login`, `/auth/register/complete`, `/auth/refresh`, `/auth/oauth/google`,
      `/auth/oauth/facebook` or `/auth/oauth/facebook/link`;
    - add `deviceId` = `postman-device-0000000001` to the environment.

- [ ] **Step 4: Run the Step 2 command, then the whole module**
  - Expected: green.
  - Then run `./mvnw -q -pl services/auth-service -am test`. Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add libs/event-schema services/auth-service/src docs/auth-service-api.md docs/ARCHITECTURE.md postman
git commit -m "feat(auth-service): alert the owner when an account signs in from a new device"
```

---

### Task 6: Forgetting devices — the 180-day sweep and erasure

**Files:**
- Modify:
  - `AUTH/repository/KnownDeviceRepository.java`
  - `AUTH/service/ExpiredRecordCleanup.java`
  - `AUTH/service/AccountClosureServiceImpl.java`
- Test: `AUTH_TEST/service/ExpiredRecordCleanupTest.java`,
  `AUTH_TEST/service/AccountClosureServiceImplTest.java` (add cases)

**Interfaces:**
- Consumes: `KnownDevices.forget(Long)` and `KnownDevices.signedIn` from Task 4.
- Produces: `int KnownDeviceRepository.deleteUnusedBefore(Instant cutoff, int batchSize)`, a
  batched native delete by `last_seen_at`, shaped like `deleteRecordedBefore`.

- [ ] **Step 1: Write the failing tests**
  - In both classes, `@BeforeEach` first deletes from `known_devices` and
    `known_device_accounts`, before the users, because of the foreign keys.
  - `ExpiredRecordCleanupTest`:

```java
@Test void purge_forgetsDevicesUnusedPastTheGrace_butNotThatTheAccountHadOne() {
    jdbc.update("insert into known_devices values (1, ?, 'old-hash', now() - interval '181 days')", userId);
    jdbc.update("insert into known_devices values (2, ?, 'recent-hash', now() - interval '1 day')", userId);
    jdbc.update("insert into known_device_accounts values (?)", userId);

    cleanup.purgeExpiredRecords();

    assertThat(jdbc.queryForList("select device_hash from known_devices", String.class)).containsExactly("recent-hash");
    assertThat(knownDevices.signedIn(userId, "device-never-seen-01")).isTrue();
}
```

  - `AccountClosureServiceImplTest`, next to `theSweep_deletesWhatIsDue_evenUnderABan_andFreesTheCredentials`:

```java
@Test void theSweep_forgetsTheAccountsDevices() {
    Long id = signedUpWithPassword();
    knownDevices.signedIn(id, "device-aaaaaaaaaaaa");
    accountClosureService.requestDeletion(id, byPassword(PASSWORD));
    jdbcTemplate.update("update users set deletion_scheduled_at = now() - interval '1 minute' where id = ?", id);

    accountDeletionSweep.deleteDueAccounts();

    assertThat(jdbcTemplate.queryForObject("select count(*) from known_devices where user_id = ?", Integer.class, id)).isZero();
    assertThat(jdbcTemplate.queryForObject("select count(*) from known_device_accounts where user_id = ?", Integer.class, id)).isZero();
}
```

- [ ] **Step 2: Run them**
  - Run: `./mvnw -q -pl services/auth-service -am test -Dtest='ExpiredRecordCleanupTest,AccountClosureServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: a compilation failure, then the two new tests failing.

- [ ] **Step 3: Implement**
  - `ExpiredRecordCleanup`:
    - one more sweep, `sweep("unused devices", () -> knownDeviceRepository.deleteUnusedBefore(now.minus(retention.securityEventGrace()), batchSize))`;
    - a comment: it runs on the security log's clock because the privacy pages promise 180 days
      for sign-in records; the account's mark stays, see V19.
  - `AccountClosureServiceImpl.eraseIfDue`: call `knownDevices.forget(userId)` beside
    `userIdentityRepository.deleteAllByUserId(userId)`.

- [ ] **Step 4: Run the Step 2 command.** Expected: green.

- [ ] **Step 5: Commit**

```bash
git add services/auth-service/src
git commit -m "feat(auth-service): forget unused devices after 180 days and on erasure"
```

---

### Task 7: notification-service writes the notice

**Files:**
- Create:
  - `services/notification-service/src/main/java/com/tiktok/notificationservice/event/consumer/NewDeviceSignInEventConsumer.java`
  - `services/notification-service/src/test/java/com/tiktok/notificationservice/event/consumer/NewDeviceSignInEventConsumerTest.java`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: `NewDeviceSignInEvent` (Task 5), on topic `auth.new-device-events`.
- Produces:
  - `NewDeviceSignInEventConsumer(IdempotentEventProcessor, NotificationService, ObjectMapper)`,
    with `@KafkaListener(topics = "auth.new-device-events", groupId = "notification-service") public void onMessage(String payload)`
  - `static final String REFERENCE = "account-security"`
  - `static final String TITLE = "Your account logged in from a new device"`
  - `static String body(String deviceLabel, Instant occurredAt, String location)`

- [ ] **Step 1: Write the failing test.** Same shape as `UserRegisteredEventConsumerTest`.
  `occurredAt = Instant.parse("2026-10-23T14:42:00Z")` is 21:42 in +07.

```java
@Test void aNewDeviceSignInBecomesOneSystemNotice() {
    deliver(event("Chrome on macOS 27", "Ho Chi Minh City, Vietnam", AT));
    verify(notificationService).create(7L, null, NotificationType.SYSTEM,
            "Your account logged in from a new device",
            "Your account logged in from device Chrome on macOS 27 on 10/23 21:42 +07 in Ho Chi Minh City, Vietnam. If this wasn't you, secure your account immediately.",
            "account-security");
}
@Test void withoutALabelItSaysANewDevice() {
    assertThat(NewDeviceSignInEventConsumer.body(null, AT, "Ho Chi Minh City, Vietnam")).isEqualTo(
            "Your account logged in from a new device on 10/23 21:42 +07 in Ho Chi Minh City, Vietnam. If this wasn't you, secure your account immediately.");
}
@Test void withoutAPlaceTheInPartIsLeftOut() {
    assertThat(NewDeviceSignInEventConsumer.body("Chrome on macOS 27", AT, null)).isEqualTo(
            "Your account logged in from device Chrome on macOS 27 on 10/23 21:42 +07. If this wasn't you, secure your account immediately.");
}
@Test void aSignInAfterFiveUtcIsTheNextDayInVietnam() {
    assertThat(NewDeviceSignInEventConsumer.body("Chrome", Instant.parse("2026-10-23T17:30:00Z"), null))
            .contains(" on 10/24 00:30 +07.");
}
@Test void aRedeliveredEventWritesNothing() {
    // tryClaim returns false
    verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
}
```

- [ ] **Step 2: Run it**
  - Run: `./mvnw -q -pl services/notification-service -am test -Dtest=NewDeviceSignInEventConsumerTest -Dsurefire.failIfNoSpecifiedTests=false`
  - Expected: a compilation failure.

- [ ] **Step 3: Implement**
  - The consumer: parse, then `idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () -> notificationService.create(event.userId(), null, NotificationType.SYSTEM, TITLE, body(...), REFERENCE))`.
  - The time format: `DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.of("Asia/Ho_Chi_Minh"))`.
  - The Javadoc says three things:
    - why it is `SYSTEM` and not a new type (the mobile app fails its inbox on an unknown type);
    - `REFERENCE` is what the web keys its "Secure your account" link on;
    - `SYSTEM` never collapses repeats, which is right: two alerts are two sign-ins.
  - `CLAUDE.md`, written in Vietnamese like the rest:
    - §6: add `known_devices` and `known_device_accounts` to the hard-delete exceptions.
    - A new bullet in §4 "JWT Authentication & security-lib", next to "Tự đóng tài khoản":
      **Vị trí đăng nhập + thiết bị mới**, with a link to the spec. It covers:
      - `GeoIpLocator` and `auth.geoip.database`: offline; a missing or broken file turns the
        lookup off, never a sign-in;
      - the place is resolved from `X-Client-Ip` and stored in `auth_security_events.location`;
      - `X-Device-Id` is read in `TokenIssuer`;
      - the two tables, and why the marker is never swept;
      - the first device is silent, `/refresh` remembers without alerting, and ADMIN accounts are
        skipped;
      - the topic is `auth.new-device-events`, never `auth.user-events`;
      - the notice is `SYSTEM` + `"account-security"`. Do NOT add a new `NotificationType`;
      - the alert's label is `DeviceLabel.brief`, because client hints are the client's to write.
    - The Production list: one line on `geoip-init` and the `geoip-data` volume.

- [ ] **Step 4: Run the Step 2 command**
  - Expected: 5 tests, 0 failures.
  - Then run `./mvnw -q -pl services/notification-service -am test`. Expected: BUILD SUCCESS.
    The baseline was 92 tests.

- [ ] **Step 5: Commit**

```bash
git add services/notification-service/src CLAUDE.md
git commit -m "feat(notification-service): tell the owner about a sign-in from a new device"
```

---

### Task 8: Web — the device cookie (`tiktok-cloned`)

**Files:**
- Create: `src/proxy.ts`, `src/__tests__/proxy.test.ts`
- Modify: `docs/TAI_LIEU_DU_AN.md`. Add one line beside the `/api/*` proxy paragraph (line ~30),
  in Vietnamese: `src/proxy.ts` sets the `nowa_device` cookie and adds `X-Device-Id` on
  `/api/v1/auth/*`, so auth-service alerts only for a new device.

**Interfaces:**
- Produces:
  - `export const DEVICE_COOKIE = "nowa_device"`
  - `export function proxy(request: NextRequest): NextResponse`
  - `export const config = { matcher: "/api/v1/auth/:path*" }`

- [ ] **Step 1: Write the failing test.**
  - The first line of the file is `// @vitest-environment node`.
  - The upstream request headers are read from what `NextResponse.next({ request })` encodes:
    `x-middleware-request-<name>`.

```ts
import { NextRequest } from "next/server";
// Next 16.4.2 exports the matcher helper under its pre-16 name; the bundled docs' doesProxyMatch does not exist.
import { unstable_doesMiddlewareMatch } from "next/experimental/testing/server";
import { config, DEVICE_COOKIE, proxy } from "@/proxy";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const KNOWN = "0f8fad5b-d9cb-469f-a165-70867728950e";
const forwarded = (res: Response) => res.headers.get("x-middleware-request-x-device-id");
const call = (headers: Record<string, string> = {}) =>
  proxy(new NextRequest("http://localhost:3000/api/v1/auth/login", { method: "POST", headers }));

it("gives a browser without the cookie an id, and forwards it", () => {
  const res = call();
  const set = res.cookies.get(DEVICE_COOKIE);
  expect(set?.value).toMatch(UUID);
  expect(set).toMatchObject({ httpOnly: true, sameSite: "lax", path: "/api/v1/auth", maxAge: 31536000 });
  expect(forwarded(res)).toBe(set?.value);
});
it("forwards the id the browser already has, and sets nothing", () => {
  const res = call({ cookie: `${DEVICE_COOKIE}=${KNOWN}` });
  expect(forwarded(res)).toBe(KNOWN);
  expect(res.cookies.get(DEVICE_COOKIE)).toBeUndefined();
});
it("overwrites an X-Device-Id the browser wrote itself", () => {
  expect(forwarded(call({ cookie: `${DEVICE_COOKIE}=${KNOWN}`, "x-device-id": "chosen-by-the-page-0001" }))).toBe(KNOWN);
});
it("replacesATamperedCookie", () => {
  for (const bad of ["short", "x".repeat(500), "not-a-uuid-but-long-enough-0001", KNOWN.toUpperCase()]) {
    const res = call({ cookie: `${DEVICE_COOKIE}=${bad}` });
    expect(forwarded(res)).toMatch(UUID);
    expect(forwarded(res)).not.toBe(bad);
    expect(res.cookies.get(DEVICE_COOKIE)?.value).toBe(forwarded(res));
  }
});
it("runs on auth calls only", () => {
  expect(unstable_doesMiddlewareMatch({ config, url: "/api/v1/auth/refresh" })).toBe(true);
  expect(unstable_doesMiddlewareMatch({ config, url: "/api/v1/videos/feed" })).toBe(false);
  expect(unstable_doesMiddlewareMatch({ config, url: "/" })).toBe(false);
});
```

- [ ] **Step 2: Run it**
  - Run: `npx vitest run src/__tests__/proxy.test.ts`
  - Expected: it fails because `@/proxy` does not exist.

- [ ] **Step 3: Implement `src/proxy.ts`**
  - Read `request.cookies.get(DEVICE_COOKIE)?.value`. When it does not match the lowercase UUID
    pattern, mint `crypto.randomUUID()`.
  - `const headers = new Headers(request.headers); headers.set("x-device-id", id)`
  - `const res = NextResponse.next({ request: { headers } })`
  - When the id was minted, set the cookie with the Global Constraints attributes
    (`secure: process.env.NODE_ENV === "production"`).
  - The file comment says:
    - why it is a cookie: WebKit deletes localStorage after seven days without a visit;
    - why it is HttpOnly and scoped to `/api/v1/auth`;
    - that it is not a credential;
    - that `X-Forwarded-For` is deliberately left alone (spec §8.2).

- [ ] **Step 4: Run the Step 2 command**
  - Expected: 5 passing.
  - Then run `npx vitest run && npm run lint && npm run typecheck`. Expected: all clean.

- [ ] **Step 5: Check it through the external rewrite**
  - Use the dev server that is already running on :3000; `proxy.ts` hot-reloads.
  - Run: `curl -si -X POST http://localhost:3000/api/v1/auth/login -H 'Content-Type: application/json' -d '{"usernameOrEmail":"nobody","password":"x"}' | grep -i '^set-cookie: nowa_device='`
  - Expected: one line containing `HttpOnly`, `Path=/api/v1/auth` and `SameSite=lax`. The 401
    body does not matter here.
  - That the header reaches the gateway is checked in Task 11, where the `known_devices` row
    must be the cookie's hash.

- [ ] **Step 6: Commit**, in `tiktok-cloned`:

```bash
git add src/proxy.ts src/__tests__/proxy.test.ts docs/TAI_LIEU_DU_AN.md
git commit -m "feat(auth): keep a device id cookie and send it on auth calls"
```

---

### Task 9: Web — the notice links to Settings; the privacy page (`tiktok-cloned`)

**Files:**
- Modify:
  - `src/lib/api/notifications.ts`
  - `src/components/layout/ActivityDrawer.tsx`
  - `src/app/privacy/page.tsx`
- Test: `src/components/layout/__tests__/ActivityDrawer.test.tsx`,
  `src/hooks/__tests__/use-notifications.test.ts` (add cases)

**Interfaces:**
- Produces:
  - `export const ACCOUNT_SECURITY_REFERENCE = "account-security";` in `notifications.ts`, with a
    comment naming `NewDeviceSignInEventConsumer.REFERENCE`
  - `NotificationItem` gains a prop `onSecure: () => void`

- [ ] **Step 1: Write the failing tests**
  - `ActivityDrawer.test.tsx`: a new `describe("ActivityDrawer, an account-security notice")`.
    It reuses the file's mocks (`push`, `onClose`, `markRead`):

```tsx
const alert: NotificationResponse = {
  id: "n9", actorId: null, type: "SYSTEM", title: "Your account logged in from a new device",
  body: "Your account logged in from device Chrome on macOS 27 on 10/23 21:42 +07 in Ho Chi Minh City, Vietnam. If this wasn't you, secure your account immediately.",
  referenceId: "account-security", read: false, createdAt: new Date().toISOString(),
};
function show(item: NotificationResponse) {
  render(<ActivityDrawer open onClose={onClose} inbox={{ items: [item], unreadCount: 1, loaded: true,
    actors: new Map(), videos: new Map(), markRead, markAllRead: vi.fn() }} />);
}

it("expands to the whole message and a link to Settings, which closes the drawer", () => {
  show(alert);
  expect(screen.queryByRole("link", { name: "Secure your account" })).toBeNull();
  fireEvent.click(screen.getByText("Your account logged in from a new device"));
  expect(markRead).toHaveBeenCalledWith("n9");
  expect(push).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("link", { name: "Secure your account" }));
  expect(push).toHaveBeenCalledWith("/setting");
  expect(onClose).toHaveBeenCalledOnce();
});
it("another system notice has no link", () => {
  show({ ...alert, id: "n10", title: "Welcome to Nowaa!", body: "Hi nora, thanks for joining us.", referenceId: null });
  fireEvent.click(screen.getByText("Welcome to Nowaa!"));
  expect(screen.queryByRole("link", { name: "Secure your account" })).toBeNull();
});
```

  - `use-notifications.test.ts`: turn the `@/lib/api/videos` mock into a spy
    (`const getVideosByIds = vi.fn(async () => [])`, with the factory delegating to it, the same
    way `listNotifications` is done). Then add:

```ts
it("never looks up a video for an account-security notice", async () => {
  listNotifications.mockResolvedValue([{ ...entry, referenceId: "account-security" }]);
  getUnreadCount.mockResolvedValue(1);
  const { result } = renderHook(() => useNotifications());
  await waitFor(() => expect(result.current.loaded).toBe(true));
  expect(getVideosByIds).not.toHaveBeenCalled();
});
```

- [ ] **Step 2: Run them**
  - Run: `npx vitest run src/components/layout/__tests__/ActivityDrawer.test.tsx src/hooks/__tests__/use-notifications.test.ts`
  - Expected: the link test fails, because there is no such link. The video test may already
    pass, since `unresolvedVideoIds` skips `SYSTEM`; it is there to keep it that way.

- [ ] **Step 3: Implement**
  - The drawer passes `onSecure={() => navigate("/setting")}` to each `NotificationItem`.
  - In the expanded block, after the time line, when
    `item.type === "SYSTEM" && item.referenceId === ACCOUNT_SECURITY_REFERENCE`, render
    `Secure your account`.
    - Use the same `span role="link" tabIndex={0}` pattern as the actor name, with
      `stopPropagation` on click and on Enter/Space, so the row does not also collapse. The
      clicks call `onSecure`.
    - Style it like a link: `text-[var(--tt-red)]` or the file's existing link colour,
      `font-semibold`, `hover:underline`.
  - `privacy/page.tsx`:
    - append to the "Sign-in and security data." paragraph:
      `We look up the approximate place of that address — usually a city and a country — in a database kept on our own servers (` +
      `<a href="https://db-ip.com" className="underline">IP Geolocation by DB-IP</a>`, then
      `), and we let you know in your notifications when your account is signed in from a device it has not used before.`
    - in "Data stored in your browser.", after "local storage and cookies", add
      `, along with a random identifier for your browser that lets us tell a device you have used before from a new one`.

- [ ] **Step 4: Run the Step 2 command, then the full check**
  - Expected: green.
  - Then run `npx vitest run && npm run check` (`check` = lint + typecheck + build; Next 16
    builds into `.next` beside the running dev server's `.next/dev`). Expected: all clean.

- [ ] **Step 5: Commit**, in `tiktok-cloned`:

```bash
git add src/lib/api/notifications.ts src/components/layout/ActivityDrawer.tsx src/app/privacy/page.tsx \
  src/components/layout/__tests__/ActivityDrawer.test.tsx src/hooks/__tests__/use-notifications.test.ts
git commit -m "feat(notifications): a new-device notice links to Settings to secure the account"
```

---

### Task 10: Admin console — the place on the timeline (`tiktok-admin`)

**Files:**
- Modify:
  - `lib/api/types.ts` (`SecurityEventResponse`)
  - `lib/activity.ts:214`
  - `app/(admin)/users/[id]/activity/page.tsx:159-164`
  - `lib/mock/security-events.ts`
- Test: `lib/__tests__/activity.test.mjs` (add cases)

**Interfaces:**
- Consumes: the `location` field of the auth-service JSON (Task 3).
- Produces: `SecurityEventResponse.location: string | null`, with a comment: approximate, looked
  up when the event happened.

- [ ] **Step 1: Write the failing test** — in `activity.test.mjs`, under the existing `signIn`
  case:

```js
// The place is approximate, and the console says so with ≈ rather than stating it.
const located = build({ securityEvents: [{ ...signIn, id: "s9", location: "Ho Chi Minh City, Vietnam" }] })
  .find((e) => e.id === "security.s9");
assert.equal(located.detail, "Chrome on macOS · 203.0.113.7 · ≈ Ho Chi Minh City, Vietnam");

// The sign-in that raised an alert says so first.
const alerted = build({ securityEvents: [{ ...signIn, id: "s10", detail: "New device", location: "Hanoi, Vietnam" }] })
  .find((e) => e.id === "security.s10");
assert.equal(alerted.detail, "New device · Chrome on macOS · 203.0.113.7 · ≈ Hanoi, Vietnam");
```

- [ ] **Step 2: Run it**
  - Run: `node --disable-warning=MODULE_TYPELESS_PACKAGE_JSON --test lib/__tests__/activity.test.mjs`
  - Expected: the `located` assertion fails.

- [ ] **Step 3: Implement**
  - `lib/activity.ts:214`: `[event.detail, event.deviceLabel, event.ipAddress, event.location && \`≈ ${event.location}\`]`.
  - `lib/api/types.ts`: add the field.
  - Mock data: each generated event gets `location`, picked from
    `["Ho Chi Minh City, Vietnam", "Hanoi, Vietnam", "Da Nang, Vietnam", null]`.
  - The footnote: replace the sentence "Locations are not shown — …does not have." with
    `Places are approximate: they are looked up from the address when the event happened, mobile networks often resolve to Hà Nội or TP.HCM wherever the phone is, and entries from before places were added have none.`
    Keep the phone sentence, and end with
    `<a href="https://db-ip.com" className="underline">IP Geolocation by DB-IP</a>`.

- [ ] **Step 4: Run the Step 2 command, then `npm run check`.** Expected: all clean. The baseline
  was 31 tests.

- [ ] **Step 5: Commit**, in `tiktok-admin`:

```bash
git add lib/api/types.ts lib/activity.ts lib/__tests__/activity.test.mjs lib/mock/security-events.ts "app/(admin)/users/[id]/activity/page.tsx"
git commit -m "feat(activity): show the approximate place of each security event"
```

---

### Task 11: End to end, locally

Nothing here is committed. It needs the user, because their own auth-service and
notification-service, started from the main checkout, hold ports 8081 and 8089.

- [ ] **Step 1: Prepare**
  - Run `make geoip` in the worktree; Task 1 already did this.
  - Run `./mvnw -q install -pl libs/event-schema -DskipTests`. It installs only event-schema, so
    nothing else in `~/.m2` changes.
  - Ask the user to stop their auth-service and notification-service.
  - From the worktree, start `make run-auth` and `make run-notification` in the background, and
    wait for `Started` in each log.
  - Check auth-service's log for `GeoIP lookup enabled from … (built …)`.
- [ ] **Step 2: Through the gateway (spec §11)**
  - Use an account from `postman/tiktok-backend.postman_environment.json` (`testUsername` /
    `testPassword`).
  - The requests:
    - one sign-in with `X-Device-Id: e2e-device-aaaaaaaa` and
      `X-Forwarded-For: 14.161.0.1`. Expected: no alert;
    - one more with the same id. Expected: no alert;
    - one with `e2e-device-bbbbbbbb`. Expected: an outbox row on `auth.new-device-events`, and
      within about 5s a `SYSTEM` notification with `referenceId: "account-security"`
      (`GET /api/v1/notifications` with that session's token).
- [ ] **Step 3: The web, in Chrome**
  - Sign in at `localhost:3000` with the same account.
    - The account gains exactly one `known_devices` row, and one more alert. Without the header
      the sign-in would still alert, since the account has had devices, but it would remember
      nothing. The new row is what proves the header crossed the rewrite.
  - Sign out, then sign in again from the same browser.
    - Expected: no new alert. The cookie persisted, and it carried the same id.
  - The inbox shows the notices. Expanding one shows the "Secure your account" link, and the link
    opens `/setting`.
- [ ] **Step 4: The console**
  - At `localhost:3001/users/<id>/activity`:
    - the Step 2 rows read `… · 14.161.0.1 · ≈ <a Vietnamese place>`;
    - the second-device row starts with "New device";
    - the footnote carries the DB-IP link.
- [ ] **Step 5: Hand back**
  - Stop the worktree's two services, and tell the user they can start theirs again.
  - Report what was seen, with the place strings exactly as shown.
