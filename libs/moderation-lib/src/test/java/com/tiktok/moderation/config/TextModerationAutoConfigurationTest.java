package com.tiktok.moderation.config;

import com.tiktok.common.config.InternalApiAutoConfiguration;
import com.tiktok.moderation.Decision;
import com.tiktok.moderation.Surface;
import com.tiktok.moderation.TextModerator;
import com.tiktok.moderation.classifier.TextClassifier;
import com.tiktok.moderation.rules.AdminTermsSync;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TextModerationAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TextModerationAutoConfiguration.class));

    @Test
    void providesAModerator_enforcingTheShippedList_byDefault() {
        runner.run(context -> {
            TextModerator moderator = context.getBean(TextModerator.class);

            assertThat(moderator.enforcing()).isTrue();
            assertThat(moderator.version()).startsWith("rules-");
            assertThat(moderator.screen("vào fb88 nhận thưởng", Surface.COMMENT).decision())
                    .isEqualTo(Decision.HOLD);
            // Allow-listed by default, in a caption.
            assertThat(moderator.screen("youtube.com/@minh", Surface.VIDEO_DESCRIPTION).decision())
                    .isEqualTo(Decision.ALLOW);
        });
    }

    @Test
    void enforcementCanBeSwitchedOff() {
        runner.withPropertyValues("moderation.text.enforce=false")
                .run(context -> assertThat(context.getBean(TextModerator.class).enforcing()).isFalse());
    }

    /** A service that cannot read its list must not start looking healthy and filter nothing. */
    @Test
    void aListThatCannotBeRead_stopsTheContext() {
        runner.withPropertyValues("moderation.text.blocklist=classpath:moderation/missing.txt")
                .run(context -> assertThat(context).hasFailed());
    }
    @Test
    void theClassifier_isNeverAsked_untilItHasAnAddress() {
        runner.run(context -> assertThat(context.getBean(TextClassifier.class).enabled()).isFalse());
        runner.withPropertyValues("moderation.text.classifier.base-url=http://localhost:8099",
                        "moderation.text.classifier.enabled=false")
                .run(context -> assertThat(context.getBean(TextClassifier.class).enabled()).isFalse());
    }

    @Test
    void theClassifier_withAnAddress_enforcesByDefault() {
        runner.withPropertyValues("moderation.text.classifier.base-url=http://localhost:8099")
                .run(context -> {
                    TextClassifier classifier = context.getBean(TextClassifier.class);
                    assertThat(classifier.enabled()).isTrue();
                    assertThat(classifier.enforcing()).isTrue();
                });
        runner.withPropertyValues("moderation.text.classifier.base-url=http://localhost:8099",
                        "moderation.text.classifier.enforce=false")
                .run(context -> assertThat(context.getBean(TextClassifier.class).enforcing()).isFalse());
    }

    /** No address, no poller — and no need for an internal token. */
    @Test
    void adminTerms_areOnlyPolledWhereAnAddressIsSet() {
        runner.run(context -> assertThat(context).doesNotHaveBean(AdminTermsSync.class));
        runner.withPropertyValues("moderation.text.admin-terms.base-url=")
                .run(context -> assertThat(context).doesNotHaveBean(AdminTermsSync.class));
        runner.withConfiguration(AutoConfigurations.of(InternalApiAutoConfiguration.class))
                .withPropertyValues("spring.profiles.active=test",
                        "moderation.text.admin-terms.base-url=http://localhost:1")
                .run(context -> assertThat(context).hasSingleBean(AdminTermsSync.class));
    }

    /** What admin-service reads for the console's Settings: what the running service decides with. */
    @Test
    @SuppressWarnings("unchecked")
    void reportsWhatItEnforces_onActuatorInfo() {
        runner.withPropertyValues("moderation.text.enforce=false",
                        "moderation.text.classifier.base-url=http://localhost:8099")
                .run(context -> {
                    Info.Builder builder = new Info.Builder();
                    context.getBean(TextModerationInfoContributor.class).contribute(builder);
                    Map<String, Object> details = (Map<String, Object>) builder.build().get("textModeration");

                    assertThat(details).containsEntry("enforce", false)
                            .containsEntry("classifier", Map.of("enabled", true, "enforce", true));
                    assertThat((String) details.get("version")).startsWith("rules-");
                });
    }

    /** A classifier without an address is never asked, so it is reported off whatever enabled says. */
    @Test
    @SuppressWarnings("unchecked")
    void reportsTheClassifierOff_withoutAnAddress() {
        runner.run(context -> {
            Info.Builder builder = new Info.Builder();
            context.getBean(TextModerationInfoContributor.class).contribute(builder);
            Map<String, Object> classifier = (Map<String, Object>)
                    ((Map<String, Object>) builder.build().get("textModeration")).get("classifier");

            assertThat(classifier).containsEntry("enabled", false);
        });
    }
}
