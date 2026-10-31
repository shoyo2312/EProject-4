package com.tiktok.userservice.dto.response;

import java.util.List;

/**
 * One page of the accounts a story is announced to, ascending by id.
 *
 * <p>{@code next} is the {@code after} that asks for the following page: the last id when this page
 * is full, null when it is not. A full page can be the last one, so the caller may get one more,
 * empty, page before {@code next} is null.
 */
public record StoryAudienceResponse(List<Long> userIds, Long next) {
}
