package com.tiktok.mediaworker.service;

public interface TranscodeService {

    TranscodeResult transcode(String videoId, String rawFileUrl);
}
