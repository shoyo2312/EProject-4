package com.tiktok.analyticsservice.controller;

import com.tiktok.analyticsservice.service.AnalyticsService;
import jakarta.validation.Validation;
import jakarta.validation.executable.ExecutableValidator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The windows the admin console asks for. It reaches back up to 1095 days on every daily series —
 * a year of trend ending on its "as of" day, plus the gap back to that day — and a series capped
 * below that answers an earlier date with a 400 that takes the whole page down, which is what
 * the engagement series did while it stopped at 365.
 */
class AnalyticsControllerTest {

    private final ExecutableValidator validator =
            Validation.buildDefaultValidatorFactory().getValidator().forExecutables();

    private final AnalyticsController controller = new AnalyticsController(mock(AnalyticsService.class));

    @ParameterizedTest
    @ValueSource(strings = {"getDailyEngagement", "getDailyActiveUsers", "getDailySignups"})
    void everyDailySeriesTakesTheThreeYearsTheConsoleAsksFor(String endpoint) throws NoSuchMethodException {
        Method method = AnalyticsController.class.getMethod(endpoint, int.class);

        assertThat(validator.validateParameters(controller, method, new Object[]{1095})).isEmpty();
        assertThat(validator.validateParameters(controller, method, new Object[]{1096})).isNotEmpty();
    }
}
