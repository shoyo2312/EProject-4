package com.tiktok.storyservice.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tiktok.storyservice.client.FriendshipClient.Relationship;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FriendshipClientTest {

    private static final Relationship STRANGER = new Relationship(false, false, false, false);
    private static final String PATH = "/api/v1/users/internal/relationships";
    private static final String ENDPOINT = "http://users" + PATH;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger clientLogger = (Logger) LoggerFactory.getLogger(FriendshipClient.class);

    private MockRestServiceServer server;
    private FriendshipClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://users");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new FriendshipClient(builder.build());
        // Captured here and kept off the console: the failure cases warn on purpose.
        logs.start();
        clientLogger.setAdditive(false);
        clientLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        clientLogger.detachAppender(logs);
        clientLogger.setAdditive(true);
    }

    /**
     * One flag per owner, answered out of order, so a field read into the wrong slot (following as
     * friends, say) or an entry matched by position shows up as a different relationship.
     */
    @Test
    void asksOnceAsTheViewerAndMapsTheAnswerByUserIdNotByPosition() {
        server.expect(requestTo(ENDPOINT + "?viewerId=9&userIds=2,3,4,5"))
                .andRespond(withSuccess(answer(
                        json(5, true, false, false, false),
                        json(3, false, false, true, false),
                        json(2, false, true, false, false),
                        json(4, false, false, false, true)),
                        MediaType.APPLICATION_JSON));

        assertThat(client.relationships(9L, List.of(2L, 3L, 4L, 5L))).containsOnly(
                entry(2L, new Relationship(false, true, false, false)),
                entry(3L, new Relationship(false, false, true, false)),
                entry(4L, new Relationship(false, false, false, true)),
                entry(5L, new Relationship(true, false, false, false)));
        server.verify();
        assertThat(warnings()).isEmpty();
    }

    /** Only the owners asked about come back: a stray entry must not overwrite another chunk's answer. */
    @Test
    void anEntryForSomeoneNotAskedAboutIsIgnored() {
        server.expect(requestTo(ENDPOINT + "?viewerId=9&userIds=2"))
                .andRespond(withSuccess(answer(stranger(2), json(7, true, true, true, true)),
                        MediaType.APPLICATION_JSON));

        assertThat(client.relationships(9L, List.of(2L))).containsOnly(entry(2L, STRANGER));
    }

    @Test
    void anAnonymousViewerIsAskedWithoutAViewerId() {
        server.expect(requestTo(ENDPOINT + "?userIds=2"))
                .andRespond(withSuccess(answer(stranger(2)), MediaType.APPLICATION_JSON));

        assertThat(client.relationships(null, List.of(2L))).containsOnly(entry(2L, STRANGER));
        server.verify();
    }

    @Test
    void noOwnersIsNoCall() {
        assertThat(client.relationships(9L, List.of())).isEmpty();
        server.verify();
    }

    /** user-service refuses more than 100 ids in one request, so a longer list goes as several. */
    @Test
    void moreThanAHundredOwnersAreAskedInChunksOfAHundred() {
        List<Long> owners = LongStream.rangeClosed(1, 150).boxed().toList();
        server.expect(askedAs(9L, owners.subList(0, 100)))
                .andRespond(withSuccess(answer(owners.subList(0, 100)), MediaType.APPLICATION_JSON));
        server.expect(askedAs(9L, owners.subList(100, 150)))
                .andRespond(withSuccess(answer(owners.subList(100, 150)), MediaType.APPLICATION_JSON));

        Map<Long, Relationship> relationships = client.relationships(9L, owners);

        assertThat(relationships).hasSize(150);
        assertThat(relationships.values()).containsOnly(STRANGER);
        server.verify();
    }

    @Test
    void aFailedCallHidesEveryOwnerOfThatChunkAndNoOtherAndWarnsOnce() {
        List<Long> owners = LongStream.rangeClosed(1, 150).boxed().toList();
        server.expect(askedAs(9L, owners.subList(0, 100)))
                .andRespond(withSuccess(answer(owners.subList(0, 100)), MediaType.APPLICATION_JSON));
        server.expect(askedAs(9L, owners.subList(100, 150))).andRespond(withServerError());

        Map<Long, Relationship> relationships = client.relationships(9L, owners);

        assertThat(relationships).hasSize(150);
        assertThat(owners.subList(0, 100)).allSatisfy(owner -> assertThat(relationships.get(owner)).isEqualTo(STRANGER));
        assertThat(owners.subList(100, 150)).allSatisfy(owner ->
                assertThat(relationships.get(owner)).isEqualTo(Relationship.UNKNOWN));
        assertThat(warnings()).hasSize(1);
    }

    /** What the deploy order allows: story-service up before the user-service that has this endpoint. */
    @Test
    void anEndpointTheUserServiceDoesNotHaveYetHidesTheOwners() {
        server.expect(requestTo(ENDPOINT + "?viewerId=9&userIds=2"))
                .andRespond(withResourceNotFound());

        assertThat(client.relationships(9L, List.of(2L))).containsOnly(entry(2L, Relationship.UNKNOWN));
        assertThat(warnings()).hasSize(1);
    }

    @Test
    void anOwnerMissingFromTheAnswerIsHiddenAndTheOthersAreStillRead() {
        server.expect(requestTo(ENDPOINT + "?viewerId=9&userIds=2,3"))
                .andRespond(withSuccess(answer(stranger(2)), MediaType.APPLICATION_JSON));

        assertThat(client.relationships(9L, List.of(2L, 3L))).containsOnly(
                entry(2L, STRANGER),
                entry(3L, Relationship.UNKNOWN));
        assertThat(warnings()).hasSize(1);
    }

    @Test
    void anAnswerWithNoDataHidesTheOwners() {
        server.expect(requestTo(ENDPOINT + "?viewerId=9&userIds=2,3"))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.relationships(9L, List.of(2L, 3L))).containsOnly(
                entry(2L, Relationship.UNKNOWN),
                entry(3L, Relationship.UNKNOWN));
        assertThat(warnings()).hasSize(1);
    }

    @Test
    void anUnansweredOwnerIsReadAsPrivateAndNotFollowedWhichShowsTheViewerNothing() {
        assertThat(Relationship.UNKNOWN).isEqualTo(new Relationship(false, true, false, false));
    }

    /**
     * The request goes to the relationships endpoint as this viewer (none for a guest) and carries
     * exactly these ids, in this order. Checked on every chunk: one that lost the viewer would be
     * answered as for a guest, with nobody blocked.
     */
    private static RequestMatcher askedAs(Long viewerId, List<Long> expected) {
        return request -> {
            UriComponents uri = UriComponentsBuilder.fromUri(request.getURI()).build();
            assertThat(uri.getPath()).isEqualTo(PATH);
            assertThat(uri.getQueryParams().getFirst("viewerId")).isEqualTo(viewerId == null ? null : viewerId.toString());
            assertThat(uri.getQueryParams().getFirst("userIds"))
                    .isEqualTo(expected.stream().map(String::valueOf).collect(Collectors.joining(",")));
        };
    }

    private List<ILoggingEvent> warnings() {
        return logs.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    private static String stranger(long userId) {
        return json(userId, false, false, false, false);
    }

    private static String json(long userId, boolean blocked, boolean privateAccount, boolean following, boolean friends) {
        return "{\"userId\":" + userId + ",\"blocked\":" + blocked + ",\"privateAccount\":" + privateAccount
                + ",\"following\":" + following + ",\"friends\":" + friends + "}";
    }

    private static String answer(String... entries) {
        return "{\"success\":true,\"data\":[" + String.join(",", entries) + "]}";
    }

    private static String answer(List<Long> strangers) {
        return answer(strangers.stream().map(FriendshipClientTest::stranger).toArray(String[]::new));
    }
}
