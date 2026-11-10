package com.tiktok.adminservice.dto.response;

/**
 * What one service does with what text moderation finds, from moderation-lib's entry on its
 * /actuator/info. A null field is a build that does not report it, not "off".
 *
 * @param reachable         false when its actuator did not answer; every field after it is then null
 * @param enforce           the word list and spam rules hold and refuse; false only records verdicts
 * @param version           {@code rules-<shipped>+admin-<revision>}, the list it screens with
 * @param classifierEnabled the hate-speech classifier is asked at all
 * @param classifierEnforce its holds are acted on, rather than noted on the comment or video
 */
public record TextModerationStatusResponse(
        String service,
        boolean reachable,
        Boolean enforce,
        String version,
        Boolean classifierEnabled,
        Boolean classifierEnforce
) {
}
