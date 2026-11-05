package com.tiktok.videoservice.entity;

import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.videoservice.dto.request.VideoEditRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The edits the uploader made in the app, kept on the document because the publication that
 * carries them to media-worker is built by the outbox poll, possibly long after the request that
 * sent them is gone. Null on a video published without edits and on every video from before.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoEdit {

    private int version;

    private List<Segment> segments;

    /** Null when the colour is left alone. */
    private Filter filter;

    /** The text and sticker layers, lowest first. Empty or null without any. */
    private List<Overlay> overlays;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Overlay {
        /** Canonical {@code s3://} form, checked to be the owner's own PNG upload. */
        private String imageUrl;
        private Double start;
        private Double end;
        /** The words in the layer as typed, kept for whoever reviews the video. */
        private List<String> texts;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Filter {
        private String id;
        private double intensity;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Segment {
        private double start;
        private double end;
        private double speed;
    }

    /** Without an overlay. */
    public static VideoEdit from(VideoEditRequest request) {
        return from(request, List.of());
    }

    /**
     * Null in, null out: no edits is the common case and stores nothing.
     *
     * @param overlayUrls the request's overlays' URLs in canonical form and in order, once the
     *                    caller has checked them. Taken separately so an unchecked URL is never
     *                    stored
     */
    public static VideoEdit from(VideoEditRequest request, List<String> overlayUrls) {
        if (request == null) {
            return null;
        }
        return VideoEdit.builder()
                .version(VideoPublishedEvent.Edit.CURRENT_VERSION)
                .segments(request.segments() == null ? List.of() : request.segments().stream()
                        .map(s -> new Segment(s.start(), s.end(), s.speed()))
                        .toList())
                .filter(request.filter() == null ? null
                        : new Filter(request.filter().id(), request.filter().intensityOrFull()))
                .overlays(overlays(request.overlays(), overlayUrls))
                .build();
    }

    private static List<Overlay> overlays(List<VideoEditRequest.Overlay> requested, List<String> urls) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        if (urls.size() != requested.size()) {
            throw new IllegalArgumentException("One checked URL per overlay: " + urls.size() + " for " + requested.size());
        }
        List<Overlay> overlays = new java.util.ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            VideoEditRequest.Overlay overlay = requested.get(i);
            overlays.add(new Overlay(urls.get(i), overlay.start(), overlay.end(),
                    overlay.texts() == null ? List.of() : List.copyOf(overlay.texts())));
        }
        return overlays;
    }

    public VideoPublishedEvent.Edit toEvent() {
        return new VideoPublishedEvent.Edit(version, segments == null ? List.of() : segments.stream()
                .map(s -> new VideoPublishedEvent.Edit.Segment(s.getStart(), s.getEnd(), s.getSpeed()))
                .toList(),
                filter == null ? null : new VideoPublishedEvent.Edit.Filter(filter.getId(), filter.getIntensity()),
                overlays == null ? List.of() : overlays.stream()
                        .map(o -> new VideoPublishedEvent.Edit.Overlay(o.getImageUrl(), o.getStart(), o.getEnd()))
                        .toList());
    }
}
