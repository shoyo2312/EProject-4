package com.tiktok.userservice.service;

import com.tiktok.userservice.client.DataExportSources;
import com.tiktok.userservice.entity.DataExportCategory;
import com.tiktok.userservice.entity.UserProfile;
import com.tiktok.userservice.repository.UserBlockRepository;
import com.tiktok.userservice.repository.UserFollowRepository;
import com.tiktok.userservice.repository.UserMuteRepository;
import com.tiktok.userservice.repository.UserProfileRepository;
import com.tiktok.userservice.repository.UserSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataExportArchiverTest {

    private static final Long ALICE = 1L;
    private static final Long BOB = 2L;
    private static final Long GONE = 3L;

    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private UserSettingsRepository userSettingsRepository;
    @Mock
    private UserFollowRepository userFollowRepository;
    @Mock
    private UserBlockRepository userBlockRepository;
    @Mock
    private UserMuteRepository userMuteRepository;
    @Mock
    private DataExportSources sources;

    @InjectMocks
    private DataExportArchiver archiver;

    @Test
    void chatsReadOldestFirstAndNameEveryAccount() {
        when(sources.conversationsOf(ALICE)).thenReturn(List.of(
                new DataExportSources.Conversation("c1", List.of(ALICE, BOB), Instant.EPOCH),
                new DataExportSources.Conversation("c2", List.of(GONE, ALICE), Instant.EPOCH)));
        // Newest first, as chat-service pages them.
        when(sources.messagesOf("c1")).thenReturn(List.of(
                new DataExportSources.Message("m2", BOB, "hi back", Instant.parse("2026-10-18T10:01:00Z")),
                new DataExportSources.Message("m1", ALICE, "hi", Instant.parse("2026-10-18T10:00:00Z"))));
        when(sources.messagesOf("c2")).thenReturn(List.of(
                new DataExportSources.Message("m3", GONE, "bye", Instant.parse("2026-10-17T09:00:00Z"))));
        when(userProfileRepository.findByUserIdInAndDeletedAtIsNull(anyList()))
                .thenReturn(List.of(profile(ALICE, "alice"), profile(BOB, "bob")));

        DataExportSection section = archiver.collect(ALICE, List.of(DataExportCategory.DIRECT_MESSAGES)).getFirst();

        assertThat(section.title()).isEqualTo("Direct Messages");
        List<Map<String, String>> chat = section.files().getFirst().records();
        assertThat(chat).extracting(r -> r.get("Message")).containsExactly("hi", "hi back", "bye");
        assertThat(chat).extracting(r -> r.get("From")).containsExactly("alice", "bob", "Deleted account (3)");
        assertThat(chat).extracting(r -> r.get("Chat with")).containsExactly("bob", "bob", "Deleted account (3)");
        assertThat(chat.getFirst().get("Date")).isEqualTo("2026-10-18 10:00:00 UTC");
    }

    @Test
    void sectionsComeInTheOrderAsked() {
        List<DataExportSection> sections = archiver.collect(ALICE,
                List.of(DataExportCategory.LIKES_AND_FAVORITES, DataExportCategory.POSTS));

        assertThat(sections).extracting(DataExportSection::title).containsExactly("Likes and Favorites", "Posts");
        assertThat(sections.getFirst().files()).extracting(DataExportSection.File::title)
                .containsExactly("Like List", "Favorite Videos");
    }

    private static UserProfile profile(Long userId, String username) {
        return UserProfile.builder().userId(userId).username(username).build();
    }
}
