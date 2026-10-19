package com.tiktok.chatservice.realtime;

/** One message on {@code /topic/admin}. The console re-reads through REST, so this is all it needs. */
public record AdminFrame(String type) {
}
