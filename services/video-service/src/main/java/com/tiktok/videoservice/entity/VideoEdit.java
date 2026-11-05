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

    /** Null in, null out: no edits is the common case and stores nothing. */
    public static VideoEdit from(VideoEditRequest request) {
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
                .build();
    }

    public VideoPublishedEvent.Edit toEvent() {
        return new VideoPublishedEvent.Edit(version, segments == null ? List.of() : segments.stream()
                .map(s -> new VideoPublishedEvent.Edit.Segment(s.getStart(), s.getEnd(), s.getSpeed()))
                .toList(),
                filter == null ? null : new VideoPublishedEvent.Edit.Filter(filter.getId(), filter.getIntensity()));
    }
}
