package com.tiktok.videoservice.dto.response;

public record ViewableVideoResponse(String id, Long userId, String thumbnailUrl, String title, Integer durationSeconds) {
    public static ViewableVideoResponse from(VideoResponse video) {
        String title = video.title();
        if (title == null || title.isBlank()) {
            title = video.description();
        }
        if (title != null) {
            title = title.trim();
            int end = Math.min(title.length(), 80);
            if (end > 0 && Character.isHighSurrogate(title.charAt(end - 1))) {
                end--;
            }
            title = title.substring(0, end);
        }
        return new ViewableVideoResponse(video.id(), video.userId(), video.thumbnailUrl(), title, video.durationSeconds());
    }
}
