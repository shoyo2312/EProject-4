package com.tiktok.videoservice.dto.response;

import java.util.List;

/**
 * A page positioned by what the previous one ended on, not by how many pages preceded it.
 *
 * <p>No total count, deliberately. Answering one costs a second full pass over the match set on
 * every request, and an infinite feed has nowhere to show it — there is no page 47 to jump to.
 * {@link #nextCursor()} being null is the end of the feed; that is the whole protocol.
 *
 * @param nextCursor opaque — clients pass it back verbatim and read nothing out of it, so its
 *                   shape stays this service's business and is free to change.
 */
public record CursorPage<T>(List<T> items, String nextCursor) {
}
