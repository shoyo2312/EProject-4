from verdict import APPROVED, REJECTED, REVIEW, Decision, Thresholds, decide, decide_together, review_only, worst

T = Thresholds()
HARM = review_only(0.55, min_frames=2, alone_at=0.80)


def test_one_frame_over_the_line_is_not_enough_when_two_are_required():
    assert decide([0.75, 0.1, 0.2], HARM).verdict == APPROVED
    assert decide([0.75, 0.56, 0.2], HARM).verdict == REVIEW


def test_a_frame_at_the_alone_line_needs_no_second():
    assert decide([0.80, 0.1], HARM).verdict == REVIEW
    assert decide([0.79, 0.1], HARM).verdict == APPROVED


def test_an_image_has_no_second_frame_to_wait_for():
    # An image story is one frame: that frame is all of it, so it alone sends it to review.
    assert decide([0.64], HARM).verdict == REVIEW
    assert decide_together({"drugs": [0.71], "gambling": [0.2]}, HARM)[1].verdict == REVIEW
    assert decide([0.54], HARM).verdict == APPROVED


def test_frames_agree_across_labels():
    # One scene, split between two labels frame by frame: each label alone has one frame over.
    label, decision = decide_together({"drugs": [0.62, 0.3, 0.1], "gambling": [0.2, 0.71, 0.1]}, HARM)

    assert decision.verdict == REVIEW
    assert label == "gambling"
    assert decision.max_score == 0.71
    assert decision.suspicious_frames == 2


def test_no_frames_together_is_still_review():
    assert decide_together({"drugs": [], "gambling": []}, HARM)[1].verdict == REVIEW


def test_all_clean_frames_are_approved():
    assert decide([0.01, 0.2, 0.59], T).verdict == APPROVED


def test_one_borderline_frame_goes_to_review():
    assert decide([0.01, 0.75, 0.2], T).verdict == REVIEW


def test_single_high_frame_is_review_not_rejected():
    # The false-positive guard: one frame over the reject line is not enough on its own.
    decision = decide([0.99, 0.1, 0.05], T)
    assert decision.verdict == REVIEW
    assert decision.suspicious_frames == 1


def test_two_corroborating_frames_reject():
    decision = decide([0.99, 0.93, 0.05], T)
    assert decision.verdict == REJECTED
    assert decision.max_score == 0.99
    assert decision.suspicious_frames == 2


def test_two_suspicious_frames_below_reject_line_stay_in_review():
    assert decide([0.7, 0.65], T).verdict == REVIEW


def test_no_frames_is_review_never_approved():
    assert decide([], T).verdict == REVIEW


def test_thresholds_are_tunable():
    strict = Thresholds(review_at=0.3, reject_at=0.5, min_reject_frames=1)
    assert decide([0.55], strict).verdict == REJECTED
    assert decide([0.55], T).verdict == APPROVED


def test_worst_picks_the_most_severe_label():
    label, decision = worst({
        "nsfw": Decision(APPROVED, 0.1, 0),
        "violence": Decision(REJECTED, 0.95, 3),
    })
    assert label == "violence"
    assert decision.verdict == REJECTED


def test_exact_review_boundary_low():
    # Just below threshold → APPROVED
    assert decide([0.59], T).verdict == APPROVED
    # Exactly at threshold → REVIEW (first suspicious frame)
    assert decide([0.60], T).verdict == REVIEW
    assert decide([0.60], T).suspicious_frames == 1


def test_exact_reject_boundary_high():
    # Below threshold with 2 frames → REVIEW
    assert decide([0.89, 0.89], T).verdict == REVIEW
    # At threshold with 1 frame → REVIEW (needs 2+ frames)
    decision = decide([0.90], T)
    assert decision.verdict == REVIEW
    assert decision.suspicious_frames == 1
    assert decision.max_score == 0.90


def test_exact_reject_boundary_with_corroboration():
    # At threshold with 2 frames → REJECTED
    decision = decide([0.90, 0.91], T)
    assert decision.verdict == REJECTED
    assert decision.max_score == 0.91
    assert decision.suspicious_frames == 2


def test_borderline_frame_just_below_review_threshold():
    # Mix of very clean and just-below-threshold → APPROVED
    assert decide([0.01, 0.02, 0.59, 0.10], T).verdict == APPROVED


def test_review_band_boundary_high():
    # maxScore = 0.89 (highest below reject) with multiple suspicious → REVIEW
    decision = decide([0.89, 0.89, 0.88], T)
    assert decision.verdict == REVIEW
    assert decision.max_score == 0.89
    assert decision.suspicious_frames == 3
