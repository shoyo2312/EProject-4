package com.tiktok.chatservice.controller;

import com.tiktok.chatservice.dto.response.ImageView;
import com.tiktok.chatservice.entity.ChatImage;
import com.tiktok.chatservice.service.ChatImageService;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/images")
@RequiredArgsConstructor
public class ImageController {

    private final ChatImageService imageService;

    /** Step one of sending a picture: store it, get an id to put in an IMAGE message. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ImageView> upload(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(imageService.upload(conversationId, currentUserId, ChatImageService.read(file)));
    }

    /**
     * Needs the viewer's token like every other call, so a link to it is no use to anyone else. The type
     * is the one the server detected, with sniffing off and no scripting context.
     */
    @GetMapping("/{imageId}")
    public ResponseEntity<byte[]> download(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId,
            @PathVariable String imageId) {
        ChatImage image = imageService.open(conversationId, currentUserId, imageId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.getMime()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(image.getData());
    }
}
