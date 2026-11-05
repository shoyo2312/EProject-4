package com.tiktok.storyservice.entity;

import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.storyservice.client.MusicTrack;
import com.tiktok.storyservice.dto.request.StoryEditRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * The editor's recipe, kept on the story because StoryCreatedEvent is built by the outbox poll,
 * possibly after the create request is gone. Null on a story posted as it was shot.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoryEdit {

    private int version;

    private List<Segment> segments;

    private Filter filter;

    /** Lowest first. */
    private List<Overlay> overlays;

    private Music music;

    /** How long an image with music plays; null on a video. */
    private Double photoSeconds;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Music {
        private String trackId;
        /** Title, artist, licence and link: the credit every Creative Commons licence asks for. */
        private String title;
        private String artist;
        private String licenseUrl;
        private String shareUrl;
        /** The catalogue's download address, as video-service described it at create. */
        private String audioUrl;
        private double start;
        private double volume;
        private double originalVolume;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Overlay {
        /** Canonical {@code s3://} form, checked to be the owner's own PNG upload. */
        private String imageUrl;
        private Double start;
        private Double end;
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

    /**
     * Null in, null out.
     *
     * @param overlayUrls the request's overlays' URLs in canonical form and in order, once checked
     * @param track       the request's music as the catalogue describes it; null for none
     * @param mediaType   what was uploaded: only an image has a photo length, only a video segments
     */
    public static StoryEdit from(StoryEditRequest request, List<String> overlayUrls, MusicTrack track,
                                 StoryMediaType mediaType) {
        if (request == null) {
            return null;
        }
        boolean image = mediaType == StoryMediaType.IMAGE;
        return StoryEdit.builder()
                .version(VideoPublishedEvent.Edit.CURRENT_VERSION)
                .segments(image || request.segments() == null ? List.of() : request.segments().stream()
                        .map(s -> new Segment(s.start(), s.end(), s.speed()))
                        .toList())
                .filter(request.filter() == null ? null
                        : new Filter(request.filter().id(), request.filter().intensityOrFull()))
                .overlays(overlays(request.overlays(), overlayUrls))
                .music(request.music() == null || track == null ? null : new Music(track.id(), track.title(),
                        track.artist(), track.licenseUrl(), track.shareUrl(), track.downloadUrl(),
                        request.music().startOrZero(), request.music().volumeOrFull(),
                        request.music().originalVolumeOrFull()))
                .photoSeconds(image && request.music() != null ? request.photoSecondsOrDefault() : null)
                .build();
    }

    private static List<Overlay> overlays(List<StoryEditRequest.Overlay> requested, List<String> urls) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        if (urls.size() != requested.size()) {
            throw new IllegalArgumentException("One checked URL per overlay: " + urls.size() + " for " + requested.size());
        }
        List<Overlay> overlays = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            StoryEditRequest.Overlay overlay = requested.get(i);
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
                        .toList(),
                music == null ? null : new VideoPublishedEvent.Edit.Music(music.getAudioUrl(), music.getStart(),
                        music.getVolume(), music.getOriginalVolume()));
    }
}
