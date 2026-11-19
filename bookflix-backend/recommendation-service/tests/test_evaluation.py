from app.evaluation.offline import evaluate
from tests.conftest import interactions_for


def test_insufficient_data_is_reported_honestly(model):
    result = evaluate(model, interactions_for([[1, 2], [3]]))
    assert result["status"] == "insufficient_data"
    assert result["eligibleUsers"] == 0
    assert "model" not in result and "popularityBaseline" not in result


def test_metrics_computed_when_enough_users(model):
    users = [[1, 2, 6, 8], [3, 4, 1, 2], [5, 6, 1, 2], [7, 8, 1, 6], [3, 4, 5, 6], [1, 2, 7, 8]]
    result = evaluate(model, interactions_for(users))
    assert result["status"] == "ok"
    assert result["usersEvaluated"] == 6
    for block in ("model", "popularityBaseline"):
        for key in ("precisionAt5", "recallAt5", "catalogCoverage"):
            assert 0.0 <= result[block][key] <= 1.0
    # deterministic
    assert evaluate(model, interactions_for(users)) == result
