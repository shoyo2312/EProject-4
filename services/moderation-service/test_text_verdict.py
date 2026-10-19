"""The text decision rule: only HATE holds, and a text is as bad as its worst window."""

from text_verdict import ALLOW, CLEAN, HATE, HOLD, OFFENSIVE, TextThresholds, decide_text, windows

T = TextThresholds()


def probabilities(clean, offensive, hate):
    return {CLEAN: clean, OFFENSIVE: offensive, HATE: hate}


def test_a_clean_text_is_allowed_as_clean():
    decision = decide_text([probabilities(0.97, 0.02, 0.01)], T)
    assert decision.verdict == ALLOW
    assert decision.label == CLEAN


def test_hate_over_the_threshold_holds():
    decision = decide_text([probabilities(0.02, 0.03, 0.95)], T)
    assert decision.verdict == HOLD
    assert decision.label == HATE
    assert decision.score == 0.95


def test_hate_under_the_threshold_is_allowed_but_labelled():
    # Labelled so the caller can record it: that note is what the threshold gets tuned on.
    decision = decide_text([probabilities(0.30, 0.10, 0.60)], T)
    assert decision.verdict == ALLOW
    assert decision.label == HATE


def test_offensive_never_holds_however_sure():
    # Profanity as an intensifier is everyday speech; the word list holds the abusive kind.
    decision = decide_text([probabilities(0.01, 0.98, 0.01)], T)
    assert decision.verdict == ALLOW
    assert decision.label == OFFENSIVE
    assert decision.score == 0.98


def test_the_hateful_window_decides_even_when_another_is_more_offensive():
    # Picking the most toxic window first would report the profanity and miss the hate.
    decision = decide_text([probabilities(0.02, 0.97, 0.01), probabilities(0.03, 0.02, 0.95)], T)
    assert decision.verdict == HOLD
    assert decision.scores[HATE] == 0.95


def test_without_hate_the_most_toxic_window_is_reported():
    decision = decide_text([probabilities(0.95, 0.04, 0.01), probabilities(0.20, 0.75, 0.05)], T)
    assert decision.verdict == ALLOW
    assert decision.label == OFFENSIVE
    assert decision.scores[CLEAN] == 0.20


def test_no_windows_is_clean():
    decision = decide_text([], T)
    assert decision.verdict == ALLOW
    assert decision.label == CLEAN


def test_the_threshold_is_inclusive():
    assert decide_text([probabilities(0.05, 0.05, 0.90)], T).verdict == HOLD
    assert decide_text([probabilities(0.10, 0.08, 0.82)], T).verdict == ALLOW


def test_windows_overlap_and_the_last_one_reaches_the_end():
    ids = list(range(10))
    assert windows(ids, 4, 3, 8) == [[0, 1, 2, 3], [3, 4, 5, 6], [6, 7, 8, 9]]
    assert windows(list(range(11)), 4, 3, 8) == [[0, 1, 2, 3], [3, 4, 5, 6], [6, 7, 8, 9], [9, 10]]


def test_a_short_text_is_one_window():
    assert windows([1, 2, 3], 64, 48, 32) == [[1, 2, 3]]


def test_windows_stop_at_the_cap():
    assert windows(list(range(100)), 4, 3, 2) == [[0, 1, 2, 3], [3, 4, 5, 6]]


def test_an_empty_text_is_one_empty_window():
    assert windows([], 64, 48, 32) == [[]]
