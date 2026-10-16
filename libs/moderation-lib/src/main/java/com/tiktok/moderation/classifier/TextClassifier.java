package com.tiktok.moderation.classifier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.moderation.Category;
import com.tiktok.moderation.Flag;
import com.tiktok.moderation.Surface;
import com.tiktok.moderation.TextVerdict;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A second reader behind the rules: moderation-service's {@code POST /moderate/text}, a Vietnamese
 * hate-speech classifier. It adds to a verdict the rules already reached, never replaces one.
 *
 * <p><b>Fails open to the rules.</b> By the time this is asked the rules have run, so a classifier
 * that cannot answer leaves the verdict exactly as the rules made it — the Phase 1 behaviour, not
 * "every comment held". Frames do the opposite (failure is REVIEW) because nothing else screens
 * them; text has the rules. After a failure it is not asked again for {@code cooldown}, so an outage
 * costs one timeout per cooldown rather than one on every comment.
 *
 * <p>What it says becomes one flag from the {@link Flag.Source#MODEL}: a HOLD while enforcing, a
 * note while only being measured, and a note when it saw offence below its threshold. The threshold
 * itself lives in moderation-service, with the model — as for frames.
 */
public class TextClassifier {

    private static final Logger log = LoggerFactory.getLogger(TextClassifier.class);

    private static final String HOLD = "HOLD";

    private static final String CLEAN = "CLEAN";

    /** One text to read again, with the verdict the rules gave it. */
    public record Input(String text, Surface surface, TextVerdict verdict) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Response(List<Result> results, String modelVersion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Result(String verdict, String label, double score) {
    }

    private final RestClient restClient;
    private final boolean enforcing;
    private final Duration cooldown;
    private final Clock clock;
    private volatile Instant resumeAt;

    public TextClassifier(RestClient restClient, boolean enforcing, Duration cooldown, Clock clock) {
        this.restClient = restClient;
        this.enforcing = enforcing;
        this.cooldown = cooldown;
        this.clock = clock;
    }

    /** One that is never asked: every verdict comes back as the rules made it. */
    public static TextClassifier disabled() {
        return new TextClassifier(null, false, Duration.ZERO, Clock.systemUTC());
    }

    public boolean enabled() {
        return restClient != null;
    }

    /** Whether a HOLD from the model holds, or is only recorded as a note. */
    public boolean enforcing() {
        return enforcing;
    }

    public TextVerdict refine(String text, Surface surface, TextVerdict verdict) {
        return refine(List.of(new Input(text, surface, verdict))).getFirst();
    }

    /**
     * Every input's verdict with the model's opinion added, in order, from one call. Blank texts are
     * not sent and come back unchanged, as does everything when the model cannot answer.
     */
    public List<TextVerdict> refine(List<Input> inputs) {
        List<TextVerdict> verdicts = new ArrayList<>(inputs.stream().map(Input::verdict).toList());
        if (!enabled() || paused()) {
            return verdicts;
        }

        List<Integer> asked = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            String text = inputs.get(i).text();
            if (text != null && !text.isBlank()) {
                asked.add(i);
                texts.add(text);
            }
        }
        if (texts.isEmpty()) {
            return verdicts;
        }

        Response response = ask(texts);
        if (response == null) {
            return verdicts;
        }

        for (int n = 0; n < asked.size(); n++) {
            int i = asked.get(n);
            Input input = inputs.get(i);
            Result result = response.results().get(n);
            List<Flag> flag = flagFor(result);
            Metrics.counter("text.classifier.opinions",
                    "surface", input.surface().tag(),
                    "label", String.valueOf(result.label()).toLowerCase(Locale.ROOT),
                    "verdict", String.valueOf(result.verdict()).toLowerCase(Locale.ROOT),
                    "enforced", String.valueOf(enforcing)).increment();
            if (!flag.isEmpty()) {
                // The flag, not the text — the same rule as the rules' own log line.
                log.info("Text classifier: {} {} ({})", input.surface().tag(), flag.getFirst(),
                        enforcing ? "enforced" : "not enforced");
            }
            verdicts.set(i, input.verdict().with(flag, input.verdict().version() + "+" + response.modelVersion()));
        }
        return verdicts;
    }

    private Response ask(List<String> texts) {
        try {
            Response response = restClient.post()
                    .uri("/moderate/text")
                    .body(Map.of("texts", texts))
                    .retrieve()
                    .body(Response.class);
            if (response == null || response.results() == null || response.results().size() != texts.size()
                    || response.modelVersion() == null) {
                throw new IllegalStateException("moderation-service answered " + texts.size()
                        + " texts with a malformed body");
            }
            return response;
        } catch (RuntimeException e) {
            // Every failure looks alike to the caller — no opinion — and none of them is retried
            // here: the comment is waiting.
            resumeAt = clock.instant().plus(cooldown);
            Metrics.counter("text.classifier.unavailable").increment();
            log.warn("Text classifier unavailable, going on with the rules alone for {}: {}", cooldown, e.toString());
            return null;
        }
    }

    private boolean paused() {
        Instant until = resumeAt;
        return until != null && clock.instant().isBefore(until);
    }

    private List<Flag> flagFor(Result result) {
        String label = result.label() == null ? "UNKNOWN" : result.label().toUpperCase(Locale.ROOT);
        boolean hold = HOLD.equalsIgnoreCase(result.verdict());
        if (!hold && CLEAN.equals(label)) {
            return List.of();
        }
        int severity = hold && enforcing ? Flag.HOLD : Flag.NOTE;
        String rule = "model:" + label + ":" + String.format(Locale.ROOT, "%.2f", result.score());
        return List.of(new Flag(categoryOf(label), severity, rule, Flag.Source.MODEL));
    }

    /**
     * ViHSD's labels in this platform's categories. OFFENSIVE is profanity that targets nobody;
     * HATE is an attack on a person or a group. A label this does not know — another model behind
     * the same endpoint — is read as an insult rather than dropped.
     */
    private static Category categoryOf(String label) {
        return switch (label) {
            case "HATE" -> Category.HATE;
            case "OFFENSIVE" -> Category.PROFANITY;
            default -> Category.INSULT;
        };
    }
}
