package com.tiktok.videoservice.service;

import com.tiktok.videoservice.dto.request.CreateVideoRequest;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.exception.NotVideoOwnerException;
import com.tiktok.videoservice.exception.VideoNotFoundException;
import com.tiktok.videoservice.mapper.VideoMapper;
import com.tiktok.videoservice.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VideoServiceImpl implements VideoService {

    private final VideoRepository videoRepository;
    private final VideoMapper videoMapper;

    @Override
    public VideoResponse publish(Long userId, CreateVideoRequest request) {
        Video video = Video.builder()
                .id(Video.newId())
                .userId(userId)
                .title(request.title())
                .description(request.description())
                .rawFileUrl(request.rawFileUrl())
                .visibility(request.visibility())
                .status(VideoStatus.PROCESSING)
                .viewCount(0)
                .likeCount(0)
                .commentCount(0)
                .build();

        Video saved = videoRepository.save(video);
        return videoMapper.toResponse(saved);
    }

    @Override
    public VideoResponse getById(Long requesterId, String videoId) {
        Video video = videoRepository.findByIdAndDeletedAtIsNull(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));

        if (!isOwner(requesterId, video) && !isPubliclyVisible(video)) {
            throw new VideoNotFoundException(videoId);
        }

        return videoMapper.toResponse(video);
    }

    @Override
    public Page<VideoResponse> getFeed(Pageable pageable) {
        return videoRepository
                .findByStatusAndVisibilityAndDeletedAtIsNullOrderByCreatedAtDesc(
                        VideoStatus.PUBLISHED, VideoVisibility.PUBLIC, pageable)
                .map(videoMapper::toResponse);
    }

    @Override
    public Page<VideoResponse> listByUser(Long userId, Pageable pageable) {
        return videoRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId, pageable)
                .map(videoMapper::toResponse);
    }

    @Override
    public void delete(Long requesterId, String videoId) {
        Video video = videoRepository.findByIdAndDeletedAtIsNull(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));

        if (!isOwner(requesterId, video)) {
            throw new NotVideoOwnerException(videoId);
        }

        video.markDeleted();
        videoRepository.save(video);
    }

    private boolean isOwner(Long requesterId, Video video) {
        return requesterId != null && requesterId.equals(video.getUserId());
    }

    private boolean isPubliclyVisible(Video video) {
        return video.getVisibility() == VideoVisibility.PUBLIC && video.getStatus() == VideoStatus.PUBLISHED;
    }
}
