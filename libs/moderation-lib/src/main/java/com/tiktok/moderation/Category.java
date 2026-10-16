package com.tiktok.moderation;

/**
 * What a flagged piece of text is. Coarse on purpose: the category goes onto metrics and into the
 * record a moderator reads, and neither is helped by a finer split than a person deciding "keep or
 * remove" needs.
 */
public enum Category {
    /** Vulgar language. */
    PROFANITY,
    /** Abuse aimed at a person. */
    INSULT,
    /** Slurs against a group: region, ethnicity, nationality, orientation, politics. */
    HATE,
    SEXUAL,
    /** Threats of harm against a person. */
    VIOLENCE,
    /** Gambling, betting brands, loan sharks, get-rich-quick offers. */
    SCAM,
    /** Links, link look-alikes, mention floods. */
    SPAM,
    /** A phone number or an e-mail address. */
    PERSONAL_INFO,
    /**
     * Held because of the video owner's own settings — a keyword they filter, or every comment —
     * rather than for anything wrong with the text. Never on a caption.
     */
    CREATOR_FILTER
}
