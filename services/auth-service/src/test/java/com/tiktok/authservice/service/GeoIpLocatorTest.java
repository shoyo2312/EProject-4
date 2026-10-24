package com.tiktok.authservice.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against MaxMind's own test database (fake data, the GeoIP2-City layout DB-IP's file shares), so
 * the places below are fixed. What matters most is what the lookup refuses to do: guess, reach the
 * network, or let a bad file take auth-service down with it.
 */
class GeoIpLocatorTest {

    private static final String FIXTURE = "src/test/resources/geoip/GeoIP2-City-Test.mmdb";

    private final GeoIpLocator locator = new GeoIpLocator(FIXTURE);

    @AfterEach
    void close() {
        locator.close();
    }

    @Test
    void anIpv4AddressResolvesToCityAndCountry() {
        assertThat(locator.locate("81.2.69.160")).isEqualTo("London, United Kingdom");
    }

    @Test
    void anIpv6AddressWithoutACityResolvesToItsCountry() {
        assertThat(locator.locate("2001:218::1")).isEqualTo("Japan");
    }

    /** How a dual-stack socket reports an IPv4 client. */
    @Test
    void anIpv4MappedAddressIsLookedUpAsIpv4() {
        assertThat(locator.locate("::ffff:81.2.69.160")).isEqualTo("London, United Kingdom");
    }

    /** Every local request is one of these; none of them is anywhere. */
    @Test
    void privateAndLocalAddressesAreNotLookedUp() {
        for (String address : List.of("127.0.0.1", "::1", "10.1.2.3", "172.16.0.1", "192.168.1.1",
                "169.254.1.1", "fe80::1", "fd00::1", "0.0.0.0")) {
            assertThat(locator.locate(address)).as(address).isNull();
        }
    }

    @Test
    void anAddressOutsideTheFileHasNoPlace() {
        assertThat(locator.locate("198.51.100.1")).isNull();  // TEST-NET-2
    }

    /**
     * InetAddress.getByName resolves anything that is not a literal through DNS — on the sign-in
     * path. A literal starting with something other than a hex digit or a colon goes that way too.
     */
    @Test
    void onlyLiteralsAreParsed_neverAHostname() {
        for (String address : List.of("example.com", "abc.def", "zz::1", "1.2.3", "1.2.3.4:8080", "", " ")) {
            assertThat(GeoIpLocator.literal(address)).as(address).isNull();
        }
        assertThat(GeoIpLocator.literal(null)).isNull();
    }

    @Test
    void anUnsetPathDisablesTheLookup() {
        try (GeoIpLocator disabled = new GeoIpLocator("")) {
            assertThat(disabled.locate("81.2.69.160")).isNull();
        }
    }

    @Test
    void aMissingFileDisablesTheLookup() {
        try (GeoIpLocator disabled = new GeoIpLocator("/nonexistent/dbip-city-lite.mmdb")) {
            assertThat(disabled.locate("81.2.69.160")).isNull();
        }
    }

    /** A download cut short, or an error page saved under the file's name. */
    @Test
    void aFileThatIsNotADatabaseDisablesTheLookup(@TempDir Path dir) throws IOException {
        Path junk = Files.writeString(dir.resolve("dbip-city-lite.mmdb"), "<html>502 Bad Gateway</html>");

        try (GeoIpLocator disabled = new GeoIpLocator(junk.toString())) {
            assertThat(disabled.locate("81.2.69.160")).isNull();
        }
    }
}
