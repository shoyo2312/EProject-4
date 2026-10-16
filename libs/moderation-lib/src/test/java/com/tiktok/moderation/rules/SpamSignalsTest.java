package com.tiktok.moderation.rules;

import com.tiktok.moderation.Category;
import com.tiktok.moderation.Flag;
import com.tiktok.moderation.Surface;
import com.tiktok.moderation.text.TextNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpamSignalsTest {

    private final SpamSignals signals = new SpamSignals(List.of("youtube.com", "facebook.com"), 5);

    private List<Flag> detect(String text, Surface surface) {
        return signals.detect(text, TextNormalizer.normalize(text), surface);
    }

    private List<String> rules(String text, Surface surface) {
        return detect(text, surface).stream().map(Flag::rule).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "vào https://abc.xyz/khuyen-mai nhé",
            "www.abc.xyz",
            "ghé abc.xyz ngay",
            "link: ABC.XYZ",
            "bit.ly/3abc",
            "t.me/kenhcuatoi",
            "shop.com.vn"
    })
    void links_inAComment_areHeld(String text) {
        assertThat(detect(text, Surface.COMMENT)).isNotEmpty()
                .allSatisfy(flag -> {
                    assertThat(flag.category()).isEqualTo(Category.SPAM);
                    assertThat(flag.severity()).isEqualTo(Flag.HOLD);
                    assertThat(flag.rule()).startsWith("link:");
                });
    }

    @Test
    void aLink_isReportedByItsHost_once() {
        assertThat(rules("https://www.abc.xyz/a và abc.xyz", Surface.COMMENT)).containsExactly("link:abc.xyz");
    }

    @Test
    void allowListedDomains_passInACaption_butNotInAComment() {
        String text = "kênh của mình: https://m.youtube.com/@minh và facebook.com/minh";

        assertThat(rules(text, Surface.VIDEO_DESCRIPTION)).isEmpty();
        assertThat(rules(text, Surface.COMMENT)).contains("link:m.youtube.com", "link:facebook.com");
    }

    @Test
    void aCaptionLinkOffTheAllowList_isHeld() {
        assertThat(rules("mua tại abc.xyz", Surface.VIDEO_DESCRIPTION)).containsExactly("link:abc.xyz");
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1.2.3", "giá 1.500.000đ", "TP.HCM", "v.v.", "hello.world", "tỉ lệ 3.5", "file.mp4"})
    void ordinaryTextWithDots_isNotALink(String text) {
        assertThat(detect(text, Surface.COMMENT)).isEmpty();
    }

    @Test
    void aLinkSpelledToDodgeThePattern_isHeld() {
        assertThat(rules("vào abc(.)com nhé", Surface.COMMENT)).contains("link:obfuscated");
        assertThat(rules("abc [dot] net", Surface.COMMENT)).contains("link:obfuscated");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0912345678", "0912 345 678", "0912.345.678", "+84 912 345 678", "84912345678", "0 9 1 2 3 4 5 6 7 8"})
    void vietnameseMobileNumbers_areHeld_withoutRecordingTheNumber(String number) {
        assertThat(detect("liên hệ " + number, Surface.COMMENT))
                .singleElement()
                .satisfies(flag -> {
                    assertThat(flag.category()).isEqualTo(Category.PERSONAL_INFO);
                    assertThat(flag.rule()).isEqualTo("phone");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"năm 2026", "mã đơn 0071000123456", "giá 0.950.000", "lúc 09:30", "0123456789", "091234567"})
    void numbersThatAreNotMobiles_areLeftAlone(String text) {
        assertThat(detect(text, Surface.COMMENT)).isEmpty();
    }

    @Test
    void anEmailAddress_isPersonalInfo_notALink() {
        assertThat(rules("mail mình minh.nguyen@gmail.com", Surface.COMMENT)).containsExactly("email");
    }

    @ParameterizedTest
    @ValueSource(strings = {"fb88", "jun88", "W88", "sv388", "789bet", "f8bet", "8xbet", "bet365"})
    void bettingBrands_areScams(String brand) {
        assertThat(detect("vào " + brand + " nhận thưởng", Surface.COMMENT))
                .singleElement()
                .extracting(Flag::category)
                .isEqualTo(Category.SCAM);
    }

    @ParameterizedTest
    @ValueSource(strings = {"alphabet", "tibet", "88", "2088", "sorbet"})
    void wordsThatOnlyLookLikeBettingBrands_areLeftAlone(String word) {
        assertThat(detect(word, Surface.COMMENT)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"@minh1988 vào xem nè", "chúc mừng sinh nhật hoa1988"})
    void aNameWithItsBirthYear_isNotABettingBrand(String text) {
        assertThat(detect(text, Surface.COMMENT)).isEmpty();
    }

    @Test
    void aMentionFlood_isSpam_inACommentOrADescription_notInATitle() {
        String text = "@an @binh @chi @dung @em vào xem";

        assertThat(rules(text, Surface.COMMENT)).containsExactly("mentions:5");
        assertThat(rules(text, Surface.VIDEO_DESCRIPTION)).containsExactly("mentions:5");
        assertThat(rules(text, Surface.VIDEO_TITLE)).isEmpty();
        assertThat(rules("@an @binh @chi @dung hi", Surface.COMMENT)).isEmpty();
    }
}
