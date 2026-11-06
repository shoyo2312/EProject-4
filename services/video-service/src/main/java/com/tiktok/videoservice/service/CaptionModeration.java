package com.tiktok.videoservice.service;

import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.moderation.Decision;
import com.tiktok.moderation.Flag;
import com.tiktok.moderation.Surface;
import com.tiktok.moderation.TextModerator;
import com.tiktok.moderation.TextVerdict;
import com.tiktok.moderation.classifier.TextClassifier;
import com.tiktok.videoservice.entity.VideoTextModeration;
import com.tiktok.videoservice.exception.CaptionRejectedException;
import com.tiktok.videoservice.exception.CaptionRejectedException.Field;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Screens what an uploader wrote about their video, at publish, before the upload is transcoded.
 *
 * <p>Text never removes a video by itself. The worst terms refuse the request — nothing is stored,
 * the raw upload stays where it is, and the uploader publishes it again with the caption fixed —
 * and everything else the rules or the classifier flag sends the video to admin review once its
 * frames are screened. That second half happens in media-worker: the verdict rides on
 * VideoPublishedEvent, because search-service and recommendation-service read the moderation
 * verdict themselves, and a video held only here would still be searchable.
 *
 * <p>Tags are screened as well as the caption: they feed search, tag pages and recommendation's
 * content profile, and spelling a word across two tags is the obvious way around a caption check —
 * so they are read as one text.
 *
 * <p>The classifier reads all three fields in one call, whatever the rules said, after any refusal:
 * captions are few, and an admin reviewing a held video wants every opinion on it. It can hold, never
 * refuse; unreachable, the rules decide alone.
 */
@Component
@RequiredArgsConstructor
public class CaptionModeration {

    private final TextModerator textModerator;
    private final TextClassifier textClassifier;

    /** Without words over the picture. */
    public VideoTextModeration screen(String title, String description, List<String> tags) {
        return screen(title, description, tags, List.of());
    }

    /**
     * @param overlayTexts the words the uploader laid over the picture in the editor. Read as one
     *                     text, like the tags, so a word split across two stickers is still read
     *                     whole; screened only when there are any, so a video without them asks
     *                     the classifier exactly what it did before
     * @throws CaptionRejectedException for the first field, in the order title, description, tags,
     *                                  overlay, that holds a refused term — only while enforcing
     */
    public VideoTextModeration screen(String title, String description, List<String> tags,
                                      List<String> overlayTexts) {
        String tagText = String.join(" ", tags);
        String overlayText = overlayTexts == null ? "" : String.join(" ", overlayTexts).strip();
        TextVerdict titleVerdict = textModerator.screen(title, Surface.VIDEO_TITLE);
        TextVerdict descriptionVerdict = textModerator.screen(description, Surface.VIDEO_DESCRIPTION);
        TextVerdict tagsVerdict = textModerator.screen(tagText, Surface.VIDEO_TAGS);
        TextVerdict overlayVerdict = overlayText.isEmpty() ? null
                : textModerator.screen(overlayText, Surface.VIDEO_OVERLAY);

        boolean rulesEnforcing = textModerator.enforcing();
        if (rulesEnforcing) {
            refuseIfRejected(titleVerdict, Field.TITLE);
            refuseIfRejected(descriptionVerdict, Field.DESCRIPTION);
            refuseIfRejected(tagsVerdict, Field.TAGS);
            if (overlayVerdict != null) {
                refuseIfRejected(overlayVerdict, Field.OVERLAY);
            }
        }

        List<TextClassifier.Input> inputs = new ArrayList<>(List.of(
                new TextClassifier.Input(title, Surface.VIDEO_TITLE, titleVerdict),
                new TextClassifier.Input(description, Surface.VIDEO_DESCRIPTION, descriptionVerdict),
                new TextClassifier.Input(tagText, Surface.VIDEO_TAGS, tagsVerdict)));
        List<Field> fields = new ArrayList<>(List.of(Field.TITLE, Field.DESCRIPTION, Field.TAGS));
        if (overlayVerdict != null) {
            inputs.add(new TextClassifier.Input(overlayText, Surface.VIDEO_OVERLAY, overlayVerdict));
            fields.add(Field.OVERLAY);
        }
        List<TextVerdict> verdicts = textClassifier.refine(inputs);

        List<String> flags = new ArrayList<>();
        Decision worst = Decision.ALLOW;
        boolean modelHeld = false;
        for (int i = 0; i < fields.size(); i++) {
            TextVerdict verdict = verdicts.get(i);
            Field field = fields.get(i);
            verdict.flags().forEach(flag -> flags.add(field + ":" + flag));
            if (verdict.decision().compareTo(worst) > 0) {
                worst = verdict.decision();
            }
            // A model flag at severity 2 is a hold the classifier enforces — see TextClassifier.
            modelHeld |= verdict.flags().stream()
                    .anyMatch(flag -> flag.source() == Flag.Source.MODEL && flag.severity() >= Flag.HOLD);
        }

        return VideoTextModeration.builder()
                .verdict(switch (worst) {
                    case ALLOW -> ModerationVerdict.APPROVED;
                    case HOLD -> ModerationVerdict.REVIEW;
                    case REJECT -> ModerationVerdict.REJECTED;
                })
                .flags(List.copyOf(flags))
                // The title is never blank, so its verdict names the classifier when it answered.
                .version(verdicts.getFirst().version())
                // Acted on when the rules enforce, or when the classifier enforced a hold of its own
                // while the rules were only being measured.
                .enforced(rulesEnforcing || modelHeld)
                .checkedAt(Instant.now())
                .build();
    }

    /**
     * What VideoPublishedEvent carries for media-worker to fold in: the stored verdict when it was
     * enforced, otherwise nothing — a verdict that was only measured must not hold the video
     * downstream either, and an old video with no record has nothing to add.
     *
     * <p>Never REJECTED, as the event promises: a refusal the rules enforce stops publish before the
     * video exists. A stored REJECTED is what the rules said while only being measured, on a video
     * the classifier held — the hold is the part that was enforced, and it goes out as that.
     */
    public static ModerationVerdict eventVerdict(VideoTextModeration moderation) {
        if (moderation == null || !moderation.isEnforced()) {
            return null;
        }
        return moderation.getVerdict() == ModerationVerdict.REJECTED
                ? ModerationVerdict.REVIEW
                : moderation.getVerdict();
    }

    private static void refuseIfRejected(TextVerdict verdict, Field field) {
        if (verdict.decision() == Decision.REJECT) {
            throw new CaptionRejectedException(field);
        }
    }
}
