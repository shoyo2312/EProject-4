"""The parts of escalation that decide, tested without a network or an Azure account."""

import escalation


def test_severe_answer_rejects():
    assert escalation.resolve(6) == "REJECTED"
    assert escalation.resolve(4) == "REJECTED"


def test_clean_answer_approves():
    assert escalation.resolve(0) == "APPROVED"


def test_middle_answer_still_gets_a_human():
    # The point of escalating is to shrink the review queue, not to empty it by guessing.
    assert escalation.resolve(2) == "REVIEW"


def test_unfamiliar_payload_yields_no_severity():
    # No verdict at all, rather than a zero read out of a response we did not understand.
    assert escalation.worst_severity({}) is None
    assert escalation.worst_severity({"categoriesAnalysis": []}) is None
    assert escalation.worst_severity({"categoriesAnalysis": [{"category": "Sexual"}]}) is None


def test_worst_category_wins():
    payload = {
        "categoriesAnalysis": [
            {"category": "Sexual", "severity": 2},
            {"category": "Violence", "severity": 6},
            {"category": "Hate", "severity": 0},
        ]
    }
    assert escalation.worst_severity(payload) == 6


def test_top_frames_sends_the_worst_ones():
    frames = ["a", "b", "c", "d", "e"]
    scores = [0.10, 0.85, 0.20, 0.91, 0.30]
    # Worst first — these are the frames that put the video in the band being second-guessed.
    assert escalation.top_frames(frames, scores)[:2] == ["d", "b"]


def test_top_frames_survives_mismatched_scores():
    frames = ["a", "b", "c", "d"]
    picked = escalation.top_frames(frames, [0.1])
    assert picked and all(frame in frames for frame in picked)


def test_disabled_without_credentials(monkeypatch):
    monkeypatch.setattr(escalation, "ENDPOINT", "")
    monkeypatch.setattr(escalation, "KEY", "")
    assert not escalation.enabled()
    # And asking anyway is not an error, just an answer that changes nothing.
    assert escalation.second_opinion(["frame"]).verdict is None


def test_a_second_opinion_stops_asking_once_its_budget_is_spent(monkeypatch):
    # media-worker stops waiting at 30s and retries from scratch, billing the same frames again. A
    # second opinion that keeps asking past its budget is an answer nobody reads.
    import httpx

    clock = [0.0]
    requests = []

    def handler(request):
        requests.append(request)
        clock[0] += 6.0  # each answer takes six seconds
        return httpx.Response(200, json={"categoriesAnalysis": [{"category": "Sexual", "severity": 2}]})

    real_client = httpx.Client
    monkeypatch.setattr(escalation, "ENDPOINT", "https://azure.example")
    monkeypatch.setattr(escalation, "KEY", "key")
    monkeypatch.setattr(escalation, "BUDGET_SECONDS", 10.0)
    monkeypatch.setattr(escalation, "_now", lambda: clock[0])
    monkeypatch.setattr(escalation.httpx, "Client",
                        lambda **kwargs: real_client(transport=httpx.MockTransport(handler), **kwargs))

    opinion = escalation.second_opinion(["a", "b", "c"])

    # Frames one and two fit in ten seconds; by the third, twelve have gone.
    assert len(requests) == 2
    assert opinion.verdict is None
    assert opinion.code == "azure-slow"


def test_a_second_opinion_inside_its_budget_still_decides(monkeypatch):
    import httpx

    def handler(request):
        return httpx.Response(200, json={"categoriesAnalysis": [{"category": "Sexual", "severity": 0}]})

    real_client = httpx.Client
    monkeypatch.setattr(escalation, "ENDPOINT", "https://azure.example")
    monkeypatch.setattr(escalation, "KEY", "key")
    monkeypatch.setattr(escalation.httpx, "Client",
                        lambda **kwargs: real_client(transport=httpx.MockTransport(handler), **kwargs))

    opinion = escalation.second_opinion(["a", "b", "c"])

    assert opinion.verdict == "APPROVED"
    assert opinion.code == "azure-sev0"
