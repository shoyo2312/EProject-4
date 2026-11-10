package com.tiktok.moderation.config;

import com.tiktok.moderation.TextModerator;
import com.tiktok.moderation.classifier.TextClassifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What this service does with what text moderation finds, under {@code textModeration} on
 * /actuator/info, for the admin console's Settings. Both switches are environment variables read
 * once at startup — TEXT_MODERATION_ENFORCE and TEXT_CLASSIFIER_ENFORCE — and until this, a service
 * running with enforcement off looked the same from outside as one with it on.
 *
 * <p>Read off the beans rather than the properties, so it is what the running service decides
 * with: a classifier without an address is never asked, whatever {@code enabled} says.
 */
public class TextModerationInfoContributor implements InfoContributor {

    private final TextModerator textModerator;
    private final ObjectProvider<TextClassifier> textClassifier;

    public TextModerationInfoContributor(TextModerator textModerator, ObjectProvider<TextClassifier> textClassifier) {
        this.textModerator = textModerator;
        this.textClassifier = textClassifier;
    }

    @Override
    public void contribute(Info.Builder builder) {
        TextClassifier classifier = textClassifier.getIfAvailable(TextClassifier::disabled);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("enforce", textModerator.enforcing());
        // rules-<shipped>+admin-<revision>: whether this service has caught up with the admin terms.
        details.put("version", textModerator.version());
        details.put("classifier", Map.of("enabled", classifier.enabled(), "enforce", classifier.enforcing()));
        builder.withDetail("textModeration", details);
    }
}
