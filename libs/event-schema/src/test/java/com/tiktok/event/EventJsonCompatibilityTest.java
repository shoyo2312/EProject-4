package com.tiktok.event;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every event's JSON, pinned. Services deploy one at a time, and a record outlives the code that
 * wrote it on a topic, a DLT and an outbox row, so what Jackson writes for an event is a contract
 * with every consumer still running older code: a renamed field or a date written as a number
 * breaks them without breaking anything here. event-json.json holds what the services wrote under
 * Jackson 2 (Spring Boot 3), and the current mapper has to write the same and read it back, and
 * read an event that predates every field but the record's name.
 *
 * <p>An event with no entry fails: run once with {@code -Devent-json.record=true}, which adds the
 * missing entries and never rewrites an existing one, and review the diff. Changing an event's
 * JSON on purpose means deleting its entry first, so it shows up in review as a contract change.
 */
class EventJsonCompatibilityTest {

    private static final Path PINNED = Path.of("src/test/resources/event-json.json");
    private static final Instant AT = Instant.parse("2026-10-08T09:30:15.123456789Z");

    /** The mapper every service gets: Spring Boot's own, not one configured to look like it. */
    private static final ObjectMapper MAPPER = servicesMapper();

    @TestFactory
    Stream<DynamicTest> everyEventKeepsItsJson() throws Exception {
        TreeMap<String, JsonNode> pinned = Files.exists(PINNED)
                ? MAPPER.readValue(PINNED.toFile(), new TypeReference<>() {})
                : new TreeMap<>();
        List<Class<?>> events = events();
        if (Boolean.getBoolean("event-json.record")) {
            events.forEach(type -> pinned.putIfAbsent(type.getSimpleName(), MAPPER.valueToTree(sample(type))));
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(PINNED.toFile(), pinned);
        }
        return events.stream().map(type -> DynamicTest.dynamicTest(type.getSimpleName(), () -> {
            JsonNode written = pinned.get(type.getSimpleName());
            assertThat(written).as("no pinned JSON for %s, see the class comment", type.getSimpleName()).isNotNull();
            Object event = sample(type);
            // Through a string, as on the wire: a tree built from the object holds a Long where one
            // parsed from text holds an int, and the two nodes never compare equal.
            assertThat(MAPPER.readTree(MAPPER.writeValueAsString(event))).isEqualTo(written);
            assertThat(MAPPER.treeToValue(written, type)).isEqualTo(event);
            assertThat(MAPPER.treeToValue(epochSeconds(written), type)).isEqualTo(event);
            // A field added later is absent from everything written before it; Jackson 3 refuses a
            // missing primitive unless common-lib's PrimitiveDefaultsModule is on the mapper.
            assertThat(MAPPER.readValue("{}", type)).isNotNull();
        }));
    }

    /** Every record under com.tiktok.event, read off the compiled classes so a new event is not missed. */
    private static List<Class<?>> events() throws Exception {
        Path root = Path.of(DomainEvent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (Stream<Path> files = Files.walk(root)) {
            return files.map(file -> root.relativize(file).toString())
                    .filter(name -> name.endsWith(".class") && !name.contains("$"))
                    .<Class<?>>map(name -> load(name.substring(0, name.length() - ".class".length()).replace(File.separatorChar, '.')))
                    .filter(Class::isRecord)
                    .sorted(Comparator.comparing(Class::getSimpleName))
                    .toList();
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object sample(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            values[i] = value(components[i], i + 1);
        }
        try {
            return type.getDeclaredConstructor(Arrays.stream(components).map(RecordComponent::getType).toArray(Class[]::new))
                    .newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Different for every component, so a value written under another component's name cannot pass. */
    private static Object value(RecordComponent component, int n) {
        Class<?> type = component.getType();
        if (type == String.class) {
            return component.getName() + "-" + n;
        }
        if (type == Instant.class) {
            return AT.plusSeconds(n);
        }
        if (type == Long.class || type == long.class) {
            return 1000L + n;
        }
        if (type == Integer.class || type == int.class) {
            return n;
        }
        if (type == Boolean.class || type == boolean.class) {
            return n % 2 == 0;
        }
        if (type == Double.class || type == double.class) {
            return n + 0.25;
        }
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            return constants[n % constants.length];
        }
        if (component.getGenericType().getTypeName().equals("java.util.List<java.lang.String>")) {
            return List.of(component.getName() + "-a", component.getName() + "-b");
        }
        throw new IllegalArgumentException("No sample value for " + component + " of " + component.getDeclaringRecord());
    }

    /**
     * The same JSON with its timestamps the way media-worker wrote them: its own ObjectMapper had
     * Jackson 2's default of seconds.nanos as a number, not Spring Boot's ISO string.
     */
    private static JsonNode epochSeconds(JsonNode written) {
        ObjectNode copy = (ObjectNode) written.deepCopy();
        written.properties().forEach(field -> {
            if (!field.getValue().isString()) {
                return;
            }
            try {
                Instant at = Instant.parse(field.getValue().stringValue());
                copy.put(field.getKey(), BigDecimal.valueOf(at.getEpochSecond()).add(BigDecimal.valueOf(at.getNano(), 9)));
            } catch (DateTimeParseException notATimestamp) {
                // stays as written
            }
        });
        return copy;
    }

    private static ObjectMapper servicesMapper() {
        AtomicReference<ObjectMapper> mapper = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> mapper.set(context.getBean(ObjectMapper.class)));
        return mapper.get();
    }
}
