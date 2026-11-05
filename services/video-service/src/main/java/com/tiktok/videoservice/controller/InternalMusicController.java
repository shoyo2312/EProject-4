package com.tiktok.videoservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.videoservice.client.MusicTrack;
import com.tiktok.videoservice.service.MusicService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The catalogue lookup for other services — story-service checks a story's song here, so the
 * Jamendo client id and its cache stay in one place. Behind the internal token, as everything under
 * {@code /internal} is: the answer carries the stable download URL the public search leaves out.
 *
 * <p>Answers as publish does: 400 {@code MUSIC_NOT_FOUND} for a track that cannot be used, 503
 * {@code MUSIC_UNAVAILABLE} when the catalogue cannot be read.
 */
@RestController
@RequestMapping("/api/v1/videos/internal/music")
@RequiredArgsConstructor
@Validated
public class InternalMusicController {

    private final MusicService musicService;

    @GetMapping("/{trackId}")
    public ApiResponse<MusicTrack> track(@PathVariable @Pattern(regexp = "\\d{1,12}") String trackId) {
        return ApiResponse.success(musicService.requireTrack(trackId));
    }
}
