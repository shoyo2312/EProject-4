package com.tiktok.common.config;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import tools.jackson.core.Version;
import tools.jackson.databind.JacksonModule;

/**
 * A primitive whose JSON value is missing or null reads as 0 or false, as it did under Jackson 2.
 * Jackson 3 fails such a read instead (FAIL_ON_NULL_FOR_PRIMITIVES is on by default), and every
 * payload written before a field existed lacks that field: an older event goes to the DLT, an
 * older service's answer fails the call, a cache entry from before the deploy reads as a miss.
 * Services deploy one at a time and records outlive the code that wrote them, so a reader has to
 * take what an older writer left out.
 *
 * <p>Registered in META-INF/services, which is where Spring Boot's JsonMapper and Spring's own
 * converters and codecs (a RestClient or WebClient built without Boot) both look for modules, so
 * every mapper the services use picks it up. One built by hand does not: in a test, take
 * {@code JsonMapper.builder().findAndAddModules().build()} rather than {@code new ObjectMapper()}.
 */
public class PrimitiveDefaultsModule extends JacksonModule {

    private static final Class<?>[] PRIMITIVES =
            {boolean.class, byte.class, short.class, int.class, long.class, float.class, double.class, char.class};

    @Override
    public String getModuleName() {
        return getClass().getName();
    }

    @Override
    public Version version() {
        return Version.unknownVersion();
    }

    @Override
    public void setupModule(SetupContext context) {
        for (Class<?> type : PRIMITIVES) {
            context.configOverride(type).setNullHandling(JsonSetter.Value.forValueNulls(Nulls.AS_EMPTY));
        }
    }
}
