package com.tiktok.userservice.dto.request;

import com.tiktok.common.validation.ValidMediaUrl;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PATCH semantics: a null field means "leave unchanged", so a client can send only the field it
 * edits without wiping the rest. To clear an optional field the client sends an empty string,
 * which is stored as null (see UserProfile.updateProfile).
 */
public record UpdateProfileRequest(
        // Optional like the others, but displayName is NOT NULL in the DB, so it can be omitted
        // yet never cleared: whitespace-only is rejected here rather than blanking the name.
        @Pattern(regexp = ".*\\S.*", message = "displayName must not be blank")
        @Size(max = 100) String displayName,
        @Size(max = 500) String bio,
        // Host must be in app.media.allowed-hosts: rejects javascript:/data: URIs and arbitrary
        // third-party hosts, not just malformed URLs. Blank/null still passes (field is optional).
        //
        // http as well as https, because the value this field most often carries is one we handed
        // out ourselves: AvatarUploadService builds it from minio.endpoint, and a client that
        // reads its profile and PATCHes it back was being 400'd on our own URL. The allow-list is
        // what makes that safe — the scheme only picks the transport to a host we already named,
        // and in production that host is an https CDN which never answers on plaintext anyway.
        @Size(max = 500) @ValidMediaUrl(schemes = {"https", "http"}) String avatarUrl
) {
}
