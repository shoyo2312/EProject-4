package com.tiktok.videoservice.client;

import com.tiktok.videoservice.dto.request.FeedKind;

import java.util.List;

/** A viewer's Filter keywords, per feed, as user-service's internal settings carry them. As typed. */
public record FeedKeywords(List<String> forYou, List<String> following, List<String> friends) {

    public List<String> forFeed(FeedKind feed) {
        return switch (feed) {
            case FOR_YOU -> forYou;
            case FOLLOWING -> following;
            case FRIENDS -> friends;
        };
    }
}
