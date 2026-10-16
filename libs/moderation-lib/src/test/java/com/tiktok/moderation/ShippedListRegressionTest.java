package com.tiktok.moderation;

import com.tiktok.moderation.rules.Blocklist;
import com.tiktok.moderation.rules.SpamSignals;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped list against ordinary Vietnamese, and against what it exists to catch. Run it after
 * every change to blocklist-vi.txt — and when a real comment is held that should not have been, it
 * goes here first.
 *
 * <p>Most of the ordinary sentences are the trap the list's header warns about: an everyday word
 * whose letters, with the diacritics removed, spell a listed one.
 */
class ShippedListRegressionTest {

    private static final TextModerator MODERATOR = new TextModerator(
            Blocklist.load(new ClassPathResource("moderation/blocklist-vi.txt")),
            new SpamSignals(List.of("youtube.com", "facebook.com", "instagram.com", "tiktok.com"), 5),
            true);

    @ParameterizedTest
    @ValueSource(strings = {
            "các bạn ơi xem hết video nhé",
            "to lớn quá trời",
            "buổi sáng tốt lành cả nhà",
            "đi chơi không bạn",
            "hạt óc chó rất tốt cho sức khoẻ",
            "xoạc chân đẹp quá",
            "nhà mình nuôi súc vật ở quê",
            "con chó nhà mình dễ thương ghê",
            "về quê với ba mẹ",
            "ba quê ở Huế",
            "thử nha cái này ngon lắm",
            "vlog cuộc sống đời thường",
            "sợi keo nến dùng làm đồ thủ công",
            "mẹ chăm cơm nước cho cả nhà",
            "dụ mà mày cũng tin",
            "đẹp vãi",
            "hay vl luôn",
            "chết cười với video này",
            "thằng bé nhà mình lớn nhanh quá",
            "sexy quá chị ơi",
            "giá bao nhiêu vậy shop",
            "năm 2026 rồi mà vẫn hot",
            "tỉ lệ 1.5 lần",
            "anh ơi bài hát tên gì vậy",
            "dì ơi con nhớ dì",
            "cảm ơn bạn nhiều nha",
            "🔥🔥🔥",
            "@minh vào xem nè",
            "yêu em cả cuộc đời",
            "cả cuộc sống này",
            "về quê thắp 3 que hương cho ông bà",
            "mua 3 que kem",
            "tải app vậy ạ",
            "mua váy online ở đâu vậy",
            "❤️❤️ đẹp quá",
            "#xuhuong #fyp #tiktokvietnam #cảnhđẹp"
    })
    void ordinaryComments_areAllowed(String text) {
        TextVerdict verdict = MODERATOR.screen(text, Surface.COMMENT);

        assertThat(verdict.decision()).as("%s → %s", text, verdict.summary()).isEqualTo(Decision.ALLOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "đm hay quá",
            "Đ.M video gì vậy",
            "đồ ngu",
            "cái lồn gì vậy",
            "vào nhà cái uy tín nhận thưởng",
            "soi kèo trận tối nay",
            "vào fb88 nhận quà",
            "liên hệ 0912 345 678",
            "ghé shop tại abc.xyz",
            "kiem tien online tai nha",
            "@an @binh @chi @dung @em vào đây",
            "cái lồn︎ gì vậy",
            "l̲ồ̲n̲",
            "cá cược bóng đá uy tín",
            "ca cuoc bong da uy tin",
            "tải app vay tiền nhanh",
            "vay tien online khong the chap",
            "#nhàcái #soikèo hôm nay",
            "bọn 3 que",
            // Ð is Icelandic, not Đ, though drawn the same; then a line struck through each letter.
            "ÐM video gì vậy",
            "đ̶ồ̶ n̶g̶u̶"
    })
    void whatTheListIsFor_isHeld(String text) {
        assertThat(MODERATOR.screen(text, Surface.COMMENT).decision()).as(text).isEqualTo(Decision.HOLD);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "địt mẹ mày", "đ1t m3 m4y", "con đĩ", "dcm may", "con đĩ︎", "địtmẹmày", "dcmmay",
            // Icelandic Ð and ð for Đ and đ.
            "ÐỊT MẸ MÀY", "ðit me may"
    })
    void theWorst_isRefused(String text) {
        assertThat(MODERATOR.screen(text, Surface.COMMENT).decision()).as(text).isEqualTo(Decision.REJECT);
    }
}
