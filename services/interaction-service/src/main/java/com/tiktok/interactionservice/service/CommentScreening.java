package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.client.CommentPolicy;
import com.tiktok.moderation.Category;
import com.tiktok.moderation.Decision;
import com.tiktok.moderation.Flag;
import com.tiktok.moderation.Surface;
import com.tiktok.moderation.TextModerator;
import com.tiktok.moderation.TextVerdict;
import com.tiktok.moderation.classifier.TextClassifier;
import com.tiktok.moderation.rules.KeywordFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What happens to a comment's text: posted, held for the video's owner, or refused — the rules, the
 * owner's own filters and the classifier, in that order, each with its own switch.
 *
 * <ol>
 *   <li>The rules (the shipped list, admin terms, spam signals). An enforced REJECT ends it.</li>
 *   <li>The owner's own comment is never held — they are the one who approves — but is still refused
 *       above, and its flags are kept.</li>
 *   <li>The owner's filters: hold everything, or a keyword.</li>
 *   <li>The classifier, only when nothing has held the comment yet: a comment already waiting for the
 *       owner gains nothing from a model call.</li>
 * </ol>
 *
 * <p>Which flags hold, at severity 2 and up: the rules' only while {@code moderation.text.enforce};
 * the model's always (its severity already says whether it enforces); the owner's always. An owner
 * who switched {@code filterSpamOffensive} off waives profanity, insults and spam on their videos —
 * not hate, sexual content, threats, scams or someone's phone number, which protect the people
 * reading the thread rather than the owner's taste, and never a REJECT.
 */
@Component
@RequiredArgsConstructor
public class CommentScreening {

    /** What {@code filterSpamOffensive=false} waives. */
    static final Set<Category> WAIVABLE = EnumSet.of(Category.PROFANITY, Category.INSULT, Category.SPAM);

    /** The flag {@code filterAllComments} raises. */
    static final Flag ALL_COMMENTS = new Flag(Category.CREATOR_FILTER, Flag.HOLD, "all", Flag.Source.CREATOR);

    private final TextModerator textModerator;
    private final TextClassifier textClassifier;

    /**
     * @param decision what to do: REJECT (refuse, strike), HOLD (store for the owner), ALLOW (post)
     * @param verdict  everything that fired, kept with the comment whatever the decision — a flag on a
     *                 comment that was posted anyway is how a filter's misses and an owner's waivers
     *                 get seen
     */
    public record Outcome(Decision decision, TextVerdict verdict) {
    }

    public Outcome screen(String content, Long authorId, CommentPolicy policy) {
        TextVerdict verdict = textModerator.screen(content, Surface.COMMENT);
        boolean rulesEnforcing = textModerator.enforcing();
        if (rulesEnforcing && verdict.decision() == Decision.REJECT) {
            return new Outcome(Decision.REJECT, verdict);
        }
        if (authorId.equals(policy.ownerId())) {
            return new Outcome(Decision.ALLOW, verdict);
        }

        List<Flag> creator = new ArrayList<>();
        if (policy.filterAllComments()) {
            creator.add(ALL_COMMENTS);
        }
        creator.addAll(KeywordFilter.match(content, policy.commentKeywords()));
        verdict = verdict.with(creator, verdict.version());

        if (!holds(verdict, policy, rulesEnforcing)) {
            verdict = textClassifier.refine(content, Surface.COMMENT, verdict);
        }
        return new Outcome(holds(verdict, policy, rulesEnforcing) ? Decision.HOLD : Decision.ALLOW, verdict);
    }

    static boolean holds(TextVerdict verdict, CommentPolicy policy, boolean rulesEnforcing) {
        return verdict.flags().stream().anyMatch(flag -> flag.severity() >= Flag.HOLD
                && (flag.source() != Flag.Source.RULES || rulesEnforcing)
                && (policy.filterSpamOffensive() || flag.source() == Flag.Source.CREATOR
                        || !WAIVABLE.contains(flag.category())));
    }
}
