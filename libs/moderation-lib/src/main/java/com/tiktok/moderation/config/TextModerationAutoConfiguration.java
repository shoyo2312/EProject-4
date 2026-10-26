package com.tiktok.moderation.config;

import com.tiktok.common.http.InternalApiToken;
import com.tiktok.common.http.PooledRequestFactory;
import com.tiktok.moderation.TextModerator;
import com.tiktok.moderation.classifier.TextClassifier;
import com.tiktok.moderation.rules.AdminTermsSync;
import com.tiktok.moderation.rules.Blocklist;
import com.tiktok.moderation.rules.SpamSignals;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/**
 * One {@link TextModerator} per service that has this library. The list is read once, at startup,
 * and a list that cannot be read stops the service from starting — the alternative is a service
 * that looks healthy and filters nothing.
 *
 * <p>With RestClient on the classpath (every service that has this library), also the
 * {@link TextClassifier} — a disabled one unless {@code moderation.text.classifier.base-url} is set —
 * and, when {@code moderation.text.admin-terms.base-url} is set, the {@link AdminTermsSync} that keeps
 * the list on admin-service's terms.
 */
@AutoConfiguration
@EnableConfigurationProperties(TextModerationProperties.class)
public class TextModerationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TextModerator textModerator(TextModerationProperties properties, ResourceLoader resourceLoader) {
        Blocklist blocklist = Blocklist.load(resourceLoader.getResource(properties.blocklist()));
        SpamSignals spamSignals = new SpamSignals(properties.captionLinkAllowlist(), properties.mentionFlood());
        return new TextModerator(blocklist, spamSignals, properties.enforce());
    }

    /** With actuator on the classpath, which every service that has this library has. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(InfoContributor.class)
    static class InfoConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public TextModerationInfoContributor textModerationInfoContributor(
                TextModerator textModerator, ObjectProvider<TextClassifier> textClassifier) {
            return new TextModerationInfoContributor(textModerator, textClassifier);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({RestClient.class, PooledRequestFactory.class})
    static class RemoteConfiguration {

        /**
         * No internal token: moderation-service holds no user data and checks none, like
         * rank-service — it is on the internal network only.
         */
        @Bean
        @ConditionalOnMissingBean
        public TextClassifier textClassifier(TextModerationProperties properties) {
            TextModerationProperties.Classifier classifier = properties.classifier();
            if (!classifier.enabled() || classifier.baseUrl() == null || classifier.baseUrl().isBlank()) {
                return TextClassifier.disabled();
            }
            RestClient restClient = RestClient.builder()
                    .baseUrl(classifier.baseUrl())
                    .requestFactory(PooledRequestFactory.create(classifier.timeout(), classifier.timeout()))
                    .build();
            return new TextClassifier(restClient, classifier.enforce(), classifier.cooldown(), Clock.systemUTC());
        }

        /** Only where a URL is configured: a service that sets none keeps the shipped list. */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnExpression("'${moderation.text.admin-terms.base-url:}' != ''")
        public AdminTermsSync adminTermsSync(TextModerator textModerator, TextModerationProperties properties,
                                             ObjectProvider<InternalApiToken> internalApiToken) {
            TextModerationProperties.AdminTerms adminTerms = properties.adminTerms();
            RestClient restClient = RestClient.builder()
                    .baseUrl(adminTerms.baseUrl())
                    .defaultHeader(InternalApiToken.HEADER, internalApiToken.getObject().value())
                    .requestFactory(PooledRequestFactory.create(adminTerms.timeout(), adminTerms.timeout()))
                    .build();
            return new AdminTermsSync(textModerator, restClient, adminTerms.refresh());
        }
    }
}
