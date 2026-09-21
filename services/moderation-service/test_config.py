"""The settings endpoint the admin console reads.

It is three lines of dictionary, and the thing worth pinning is not the lines: the console
renders these keys by name, and a rename here is a screen full of em dashes with nothing in
either service's log to say why.
"""

import app


def test_config_reports_the_thresholds_in_force():
    config = app.config()

    assert config["reviewAt"] == app.THRESHOLDS.review_at
    assert config["rejectAt"] == app.THRESHOLDS.reject_at
    assert config["minRejectFrames"] == app.THRESHOLDS.min_reject_frames


def test_config_names_the_model_that_made_the_decisions():
    config = app.config()

    assert config["model"] == app.MODEL
    assert config["modelVersion"] == app.MODEL_VERSION


def test_config_says_whether_a_second_opinion_is_configured():
    config = app.config()

    # Off without an Azure endpoint and key, which is the default and what the console must show
    # rather than implying every review-band video is being escalated.
    assert config["escalationEnabled"] is False
    assert config["escalationCategories"] == ["Sexual", "Violence", "SelfHarm", "Hate"]
