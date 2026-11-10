package com.tiktok.authservice.service;

import com.tiktok.common.http.InternalApiToken;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.regex.Pattern;

/**
 * Reads, once, what the request being served says about where it came from: the address, the
 * browser, the device id, and the place the address resolves to.
 *
 * <p>Once, because a sign-in uses it twice — the new-device check inside the token transaction
 * and the security-log entry after it — and the two must not disagree, nor pay for the lookup
 * twice. Every other security event reads it here too, through the recorder.
 *
 * <p>Never throws: the recorder that calls it must not be able to fail a sign-in.
 */
@Component
@RequiredArgsConstructor
public class RequestOrigins {

    /**
     * Set by api-gateway's ClientAddressFilter, which drops any inbound copy first. Read instead
     * of {@code X-Forwarded-For} because the left-hand entries of that header are written by the
     * client: believing them would let an attacker choose the address their own break-in is filed
     * under, and a log that can be addressed by its subject is worse than no log.
     */
    private static final String CLIENT_IP_HEADER = "X-Client-Ip";

    /**
     * User-Agent Client Hints, which the browser only sends after it has seen this origin's
     * {@code Accept-CH} — that header is set by each front end's Next config, since the browser
     * talks to them and never to this service.
     *
     * <p>They exist because the user agent no longer carries either value: it is frozen for
     * privacy, so every Mac reports "Intel Mac OS X 10_15_7" whatever it actually is, and Chrome
     * on Android reports the model as the literal "K". Only Chromium implements the hints, and
     * {@code Sec-CH-UA-Model} is specified to be empty on every desktop platform, so expect both
     * to be absent most of the time.
     */
    private static final String MODEL_HEADER = "Sec-CH-UA-Model";
    private static final String PLATFORM_VERSION_HEADER = "Sec-CH-UA-Platform-Version";

    /**
     * A random id the client generates once and keeps, so a sign-in from a browser the account
     * has used before raises no alert. Not a credential: knowing it only keeps an alert quiet,
     * and signing in still takes the password or the provider. Outside the format it counts as
     * absent rather than being stored.
     */
    private static final String DEVICE_ID_HEADER = "X-Device-Id";
    private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9-]{16,64}");

    private final InternalApiToken internalApiToken;
    private final GeoIpLocator geoIpLocator;

    /** {@link RequestOrigin#NONE} on a thread that is not serving a request. */
    public RequestOrigin current() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return RequestOrigin.NONE;
        }

        String address = clientIp(request);
        return new RequestOrigin(
                address,
                request.getHeader(HttpHeaders.USER_AGENT),
                hint(request, PLATFORM_VERSION_HEADER),
                hint(request, MODEL_HEADER),
                deviceId(request),
                geoIpLocator.locate(address));
    }

    /**
     * The request being served, or null on a thread that is not serving one — a Kafka consumer, a
     * scheduled sweep. Spring MVC already publishes this; a filter and a ThreadLocal of our own
     * would be the same mechanism built a second time.
     */
    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    /**
     * Falls back to the socket's peer when the gateway header is absent, which means the service
     * was reached directly — local development, or a health probe. In a deployment that is the
     * gateway's own address, and recording that is honest: it says the hop the request arrived
     * from, rather than a client address nobody established.
     *
     * <p>The header counts only beside the internal API token, which api-gateway sends to this
     * service and no other. Anything that reaches this port without the gateway can write
     * X-Client-Ip however it likes, and for that caller the socket is the honest answer too.
     */
    private String clientIp(HttpServletRequest request) {
        String resolved = request.getHeader(CLIENT_IP_HEADER);
        boolean fromGateway = internalApiToken.accepts(request.getHeader(InternalApiToken.HEADER));
        return fromGateway && resolved != null && !resolved.isBlank() ? resolved.strip() : request.getRemoteAddr();
    }

    private static String deviceId(HttpServletRequest request) {
        String value = request.getHeader(DEVICE_ID_HEADER);
        if (value == null) {
            return null;
        }
        String id = value.strip();
        return DEVICE_ID.matcher(id).matches() ? id : null;
    }

    /**
     * A client hint, unquoted. They are RFC 8941 structured-header strings, so the value arrives
     * as {@code "SM-A525F"} with the quotes included and would otherwise be stored that way.
     *
     * <p>An empty result becomes null rather than an empty string, because that is what it means:
     * every desktop Chromium answers {@code Sec-CH-UA-Model} with {@code ""}, and a column of
     * empty strings reads like data that failed to parse instead of a device that cannot be named.
     */
    private static String hint(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null) {
            return null;
        }

        String unquoted = value.strip();
        if (unquoted.length() >= 2 && unquoted.startsWith("\"") && unquoted.endsWith("\"")) {
            unquoted = unquoted.substring(1, unquoted.length() - 1);
        }
        return unquoted.isBlank() ? null : unquoted;
    }
}
