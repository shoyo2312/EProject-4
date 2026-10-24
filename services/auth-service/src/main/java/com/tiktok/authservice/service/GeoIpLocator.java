package com.tiktok.authservice.service;

import com.maxmind.db.Reader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns a sign-in's address into the approximate place it came from — {@code "Ho Chi Minh City,
 * Vietnam"} — from DB-IP's City Lite file ({@code make geoip}; {@code geoip-init} in production).
 *
 * <p>Offline on purpose. The address never leaves the server, and a lookup is a read of a
 * memory-mapped file rather than a call that can be slow, or down, on the sign-in path.
 *
 * <p>It never throws, and it never stops auth-service from starting: a missing or broken file
 * turns the lookup off with one WARN, and every entry is then written without a place, exactly as
 * before this existed. The place is approximate — Vietnamese mobile networks often resolve to Hà
 * Nội or TP.HCM wherever the phone is — so nothing decides anything on it; it is only shown.
 */
@Slf4j
@Component
public class GeoIpLocator implements AutoCloseable {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /**
     * Hex digits, colons and dots, IPv4-mapped forms included. The first character matters:
     * {@link InetAddress#getByName} parses a literal only when it starts with a hex digit or a
     * colon, and sends anything else to DNS.
     */
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f:.]*");

    /** Null when the lookup is off. */
    private final Reader reader;

    public GeoIpLocator(@Value("${auth.geoip.database:}") String database) {
        this.reader = open(database);
    }

    private static Reader open(String database) {
        if (database == null || database.isBlank()) {
            log.warn("GeoIP lookup disabled: auth.geoip.database is not set — sign-ins get no place");
            return null;
        }
        try {
            Reader opened = new Reader(new File(database));
            log.info("GeoIP lookup enabled from {} (built {})", database, opened.getMetadata().buildTime());
            return opened;
        } catch (IOException | RuntimeException e) {
            log.warn("GeoIP lookup disabled: cannot open {} ({}) — sign-ins get no place", database, e.getMessage());
            return null;
        }
    }

    /**
     * @param address an IP literal, as api-gateway resolved it or the socket reported it
     * @return {@code "City, Country"}, the country alone when the file names no city, or null: for
     *         anything that is not a public IP literal, an address the file does not know, or a
     *         lookup that failed. Never a guess.
     */
    public String locate(String address) {
        if (reader == null) {
            return null;
        }

        InetAddress ip = literal(address);
        if (ip == null || !routable(ip)) {
            return null;
        }

        try {
            Place place = reader.get(ip, Place.class);
            return place == null ? null : place.describe();
        } catch (IOException | RuntimeException e) {
            log.debug("GeoIP lookup failed for {}", address, e);
            return null;
        }
    }

    /**
     * The address as an {@link InetAddress} when it is an IP literal, otherwise null. Never a DNS
     * lookup: a dotted quad goes through the IPv4 parser, which rejects what it cannot read
     * rather than resolving it, and an IPv6 candidate starts with a character that keeps
     * {@code getByName} on the literal path.
     */
    static InetAddress literal(String address) {
        if (address == null) {
            return null;
        }

        String candidate = address.strip();
        boolean looksLiteral = IPV4.matcher(candidate).matches()
                || (candidate.indexOf(':') >= 0 && IPV6.matcher(candidate).matches());
        if (!looksLiteral) {
            return null;
        }

        try {
            return InetAddress.getByName(candidate);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Local and private addresses are nowhere: every local request is one of them. */
    private static boolean routable(InetAddress ip) {
        if (ip.isLoopbackAddress() || ip.isSiteLocalAddress() || ip.isLinkLocalAddress() || ip.isAnyLocalAddress()) {
            return false;
        }
        // fc00::/7, IPv6's private range, which isSiteLocalAddress (the retired fec0::/10) misses.
        return !(ip instanceof Inet6Address && (ip.getAddress()[0] & 0xfe) == 0xfc);
    }

    @Override
    public void close() {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (IOException e) {
            log.debug("Closing the GeoIP database failed", e);
        }
    }

    /**
     * The two parts of a City record this reads. maxmind-db fills a record through its canonical
     * constructor, by component name, and skips every other key in the data.
     */
    public record Place(Named city, Named country) {

        String describe() {
            String countryName = country == null ? null : country.english();
            if (countryName == null) {
                return null;
            }
            String cityName = city == null ? null : city.english();
            return cityName == null ? countryName : cityName + ", " + countryName;
        }
    }

    public record Named(Map<String, String> names) {

        String english() {
            return names == null ? null : names.get("en");
        }
    }
}
