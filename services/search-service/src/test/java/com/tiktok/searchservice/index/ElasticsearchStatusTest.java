package com.tiktok.searchservice.index;

import org.apache.http.HttpHost;
import org.apache.http.HttpVersion;
import org.apache.http.message.BasicRequestLine;
import org.apache.http.message.BasicStatusLine;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.elasticsearch.UncategorizedElasticsearchException;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * kafka-lib retries an outage until it ends and parks anything else on the DLT, telling them apart
 * by exception type — so what a failed write is re-thrown as decides whether an Elasticsearch
 * restart loses events, and whether a request Elasticsearch refuses stalls its partition.
 */
class ElasticsearchStatusTest {

    @Test
    void noAnswerAtAll_isLeftAsTheOutageItAlreadyIs() {
        DataAccessResourceFailureException refused =
                new DataAccessResourceFailureException("Connection refused", new ConnectException());

        assertThat(ElasticsearchStatus.classify(refused)).isSameAs(refused);
    }

    /** A cluster that is up but not serving yet: a restart answers 503 until its shards are back. */
    @Test
    void unavailable_becomesTransient() {
        UncategorizedElasticsearchException unavailable = new UncategorizedElasticsearchException(
                "unavailable_shards_exception", 503, "{}", null);

        assertThat(ElasticsearchStatus.classify(unavailable)).isInstanceOf(TransientDataAccessResourceException.class);
    }

    @Test
    void aRefusedRequest_staysWhatItIs() {
        UncategorizedElasticsearchException refused = new UncategorizedElasticsearchException(
                "script_exception", 400, "{}", null);

        assertThat(ElasticsearchStatus.classify(refused)).isSameAs(refused);
    }

    /** The transport reports some answers as a resource failure, which would read as an outage. */
    @Test
    void aRefusedRequestDressedAsAResourceFailure_isNotTakenForAnOutage() throws Exception {
        DataAccessResourceFailureException dressed = new DataAccessResourceFailureException("POST /videos",
                new RuntimeException("transport", new ResponseException(response(400))));

        RuntimeException classified = ElasticsearchStatus.classify(dressed);

        assertThat(classified).isInstanceOf(InvalidDataAccessResourceUsageException.class);
        assertThat(classified).hasRootCauseInstanceOf(ResponseException.class);
    }

    private static Response response(int status) {
        Response response = mock(Response.class);
        when(response.getRequestLine()).thenReturn(new BasicRequestLine("POST", "/videos/_update/v1", HttpVersion.HTTP_1_1));
        when(response.getHost()).thenReturn(new HttpHost("localhost", 9200));
        when(response.getStatusLine()).thenReturn(new BasicStatusLine(HttpVersion.HTTP_1_1, status, "refused"));
        return response;
    }
}
