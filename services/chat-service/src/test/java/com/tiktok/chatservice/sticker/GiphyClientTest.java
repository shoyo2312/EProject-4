package com.tiktok.chatservice.sticker;

import com.tiktok.chatservice.dto.response.StickerView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;
import org.springframework.http.HttpMethod;

class GiphyClientTest {

    private static final String BODY = """
            {"data":[
              {"id":"LKiPxU4tiTrJ5LWJst","title":"Bunny Rabbit Sticker"},
              {"id":"../evil","title":"bad id"},
              {"id":"abc12345","title":""}
            ]}""";

    private MockRestServiceServer server;
    private RestClient.Builder builder;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private GiphyClient client(String key) {
        return new GiphyClient(new GiphyProperties(key, "http://giphy"), builder);
    }

    @Test
    void aSearchAnswersWithStickersServedFromTheCanonicalMediaHostAndDropsOddIds() {
        server.expect(once(), requestTo(startsWith("http://giphy/v1/stickers/search")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("q", "bunny"))
                .andExpect(queryParam("api_key", "k"))
                .andExpect(queryParam("rating", "g"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        List<StickerView> stickers = client("k").stickers("bunny", 0, 24);

        assertThat(stickers).extracting(StickerView::stickerId)
                .containsExactly("giphy:LKiPxU4tiTrJ5LWJst", "giphy:abc12345");
        assertThat(stickers.get(0).url()).isEqualTo("https://media.giphy.com/media/LKiPxU4tiTrJ5LWJst/giphy.webp");
        assertThat(stickers.get(0).name()).isEqualTo("Bunny Rabbit Sticker");
        assertThat(stickers.get(1).name()).isEqualTo("GIPHY sticker");
        server.verify();
    }

    @Test
    void aBlankQueryAsksForTheTrendingStickers() {
        server.expect(once(), requestTo(startsWith("http://giphy/v1/stickers/trending")))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        assertThat(client("k").stickers("  ", 0, 24)).hasSize(2);
        server.verify();
    }

    @Test
    void theSameQueryIsAnsweredFromMemoryTheSecondTime() {
        server.expect(once(), requestTo(startsWith("http://giphy/v1/stickers/search")))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        GiphyClient client = client("k");

        assertThat(client.stickers("Bunny", 0, 24)).hasSize(2);
        assertThat(client.stickers("bunny ", 0, 24)).hasSize(2);
        server.verify();
    }

    @Test
    void aFailureAnswersWithNoStickersInsteadOfAnError() {
        server.expect(once(), requestTo(startsWith("http://giphy/v1/stickers/search"))).andRespond(withServerError());

        assertThat(client("k").stickers("bunny", 0, 24)).isEmpty();
    }

    @Test
    void withoutAKeyNothingIsAskedAndNothingIsOffered() {
        assertThat(client("").stickers("bunny", 0, 24)).isEmpty();
        server.verify();
    }
}
