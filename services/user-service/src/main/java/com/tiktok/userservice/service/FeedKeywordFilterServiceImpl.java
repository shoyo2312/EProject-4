package com.tiktok.userservice.service;

import com.tiktok.moderation.rules.Blocklist;
import com.tiktok.moderation.text.TextSanitizer;
import com.tiktok.userservice.dto.request.SaveKeywordFilterRequest;
import com.tiktok.userservice.dto.response.KeywordFilterResponse;
import com.tiktok.userservice.entity.FeedKeywordFilter;
import com.tiktok.userservice.exception.DuplicateKeywordFilterException;
import com.tiktok.userservice.exception.InvalidUserSettingsException;
import com.tiktok.userservice.exception.KeywordFilterNotFoundException;
import com.tiktok.userservice.exception.TooManyKeywordFiltersException;
import com.tiktok.userservice.mapper.FeedKeywordFilterMapper;
import com.tiktok.userservice.repository.FeedKeywordFilterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class FeedKeywordFilterServiceImpl implements FeedKeywordFilterService {

    /** "Enter a single word or hashtag": an optional leading #, then no spaces and no other #. */
    private static final Pattern ONE_WORD_OR_HASHTAG = Pattern.compile("^#?[^\\s#]+$");

    /** No Snowflake id is 0, so nothing is excluded when creating. */
    private static final Long NO_ID = 0L;

    private final FeedKeywordFilterRepository repository;
    private final FeedKeywordFilterMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public List<KeywordFilterResponse> list(Long userId) {
        return repository.findByUserIdAndDeletedAtIsNullOrderByIdDesc(userId).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public KeywordFilterResponse create(Long userId, SaveKeywordFilterRequest request) {
        String keyword = validKeyword(request.keyword());
        requireAFeed(request);
        // ponytail: two parallel creates can reach 101; a per-user lock if that ever matters.
        if (repository.countByUserIdAndDeletedAtIsNull(userId) >= MAX_FILTERS) {
            throw new TooManyKeywordFiltersException(MAX_FILTERS);
        }
        if (repository.existsOtherWithKeyword(userId, keyword, NO_ID)) {
            throw new DuplicateKeywordFilterException(keyword);
        }
        return mapper.toResponse(saveUnique(FeedKeywordFilter.builder()
                .userId(userId)
                .keyword(keyword)
                .forYou(request.forYou())
                .following(request.following())
                .friends(request.friends())
                .build()));
    }

    @Override
    @Transactional
    public KeywordFilterResponse update(Long userId, Long id, SaveKeywordFilterRequest request) {
        FeedKeywordFilter filter = owned(userId, id);
        String keyword = validKeyword(request.keyword());
        requireAFeed(request);
        if (repository.existsOtherWithKeyword(userId, keyword, id)) {
            throw new DuplicateKeywordFilterException(keyword);
        }
        filter.replaceWith(keyword, request.forYou(), request.following(), request.friends());
        return mapper.toResponse(saveUnique(filter));
    }

    @Override
    @Transactional
    public void delete(Long userId, Long id) {
        owned(userId, id).markDeleted();
    }

    private FeedKeywordFilter owned(Long userId, Long id) {
        return repository.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new KeywordFilterNotFoundException(id));
    }

    /**
     * The check above and this write can race with a second request adding the same keyword;
     * uq_feed_keyword_filters_user_keyword stops the second one, which is reported as the duplicate
     * it is rather than as a server error.
     */
    private FeedKeywordFilter saveUnique(FeedKeywordFilter filter) {
        try {
            return repository.saveAndFlush(filter);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateKeywordFilterException(filter.getKeyword());
        }
    }

    /** Sanitized like a title, then checked; the spelling typed is the one kept. */
    private static String validKeyword(String raw) {
        String keyword = TextSanitizer.sanitizeLine(raw);
        if (keyword == null || keyword.isBlank()) {
            throw new InvalidUserSettingsException("keyword: must not be blank");
        }
        if (keyword.codePointCount(0, keyword.length()) > MAX_KEYWORD_CODE_POINTS) {
            throw new InvalidUserSettingsException("keyword: must be at most " + MAX_KEYWORD_CODE_POINTS + " characters");
        }
        if (!ONE_WORD_OR_HASHTAG.matcher(keyword).matches()) {
            throw new InvalidUserSettingsException("keyword: must be a single word or hashtag");
        }
        if (!Blocklist.isMatchable(keyword)) {
            // Only emoji or punctuation would never match anything, and the viewer would think it did.
            throw new InvalidUserSettingsException("keyword: '" + keyword + "' has no letters or digits to match");
        }
        return keyword;
    }

    private static void requireAFeed(SaveKeywordFilterRequest request) {
        if (!request.forYou() && !request.following() && !request.friends()) {
            throw new InvalidUserSettingsException("forYou, following, friends: select at least one feed");
        }
    }
}
