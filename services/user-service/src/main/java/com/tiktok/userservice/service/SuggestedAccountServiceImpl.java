package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.SuggestedAccountResponse;
import com.tiktok.userservice.dto.response.SuggestedAccountResponse.Reason;
import com.tiktok.userservice.entity.UserProfile;
import com.tiktok.userservice.mapper.UserProfileMapper;
import com.tiktok.userservice.repository.SuggestedAccountRepository;
import com.tiktok.userservice.repository.SuggestedAccountRepository.FollowedByCandidate;
import com.tiktok.userservice.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.util.stream.Collectors.toMap;

@Service
@RequiredArgsConstructor
public class SuggestedAccountServiceImpl implements SuggestedAccountService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    private final SuggestedAccountRepository suggestedAccountRepository;
    private final UserProfileRepository userProfileRepository;
    private final UserProfileMapper userProfileMapper;

    /** A candidate before its profile is loaded: the tier that found it and, for FOLLOWED_BY, through whom. */
    private record Pick(Reason reason, Long viaId) {
    }

    @Override
    @Transactional(readOnly = true)
    public List<SuggestedAccountResponse> suggest(Long viewerId, Integer limit) {
        int n = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);

        // Each tier is asked for n rows, which is always enough: a duplicate can only be an account
        // an earlier tier already picked, so a tier cut off at n still holds every row still needed.
        Map<Long, Pick> picks = new LinkedHashMap<>();
        for (Long id : suggestedAccountRepository.findFollowersNotFollowedBack(viewerId, n)) {
            picks.putIfAbsent(id, new Pick(Reason.FOLLOWS_YOU, null));
        }
        if (picks.size() < n) {
            for (FollowedByCandidate c : suggestedAccountRepository.findFollowedByFollowings(viewerId, n)) {
                picks.putIfAbsent(c.getUserId(), new Pick(Reason.FOLLOWED_BY, c.getViaId()));
            }
        }
        if (picks.size() < n) {
            for (Long id : suggestedAccountRepository.findMostFollowed(viewerId, n)) {
                picks.putIfAbsent(id, new Pick(Reason.POPULAR, null));
            }
        }

        List<Long> ids = new ArrayList<>();
        for (Map.Entry<Long, Pick> e : picks.entrySet()) {
            if (ids.size() == n) {
                break;
            }
            ids.add(e.getKey());
            if (e.getValue().viaId() != null) {
                ids.add(e.getValue().viaId());
            }
        }
        Map<Long, UserProfile> profiles = userProfileRepository
                .findByUserIdInAndDeletedAtIsNullAndDeactivatedAtIsNull(ids).stream()
                .collect(toMap(UserProfile::getUserId, Function.identity()));

        List<SuggestedAccountResponse> out = new ArrayList<>(n);
        for (Map.Entry<Long, Pick> e : picks.entrySet()) {
            if (out.size() == n) {
                break;
            }
            UserProfile profile = profiles.get(e.getKey());
            if (profile == null) {
                continue; // gone between the query and the load
            }
            UserProfile via = e.getValue().viaId() == null ? null : profiles.get(e.getValue().viaId());
            out.add(new SuggestedAccountResponse(userProfileMapper.toResponse(profile), e.getValue().reason(),
                    via == null ? null : via.getDisplayName() != null ? via.getDisplayName() : via.getUsername()));
        }
        return out;
    }
}
