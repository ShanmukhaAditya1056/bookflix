import threading

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings
from app.core.model_store import ModelStore, RebuildInProgressError
from app.main import create_app
from app.schemas import RebuildRequest
from tests.conftest import small_catalog

TOKEN = "test-internal-token"
HEADERS = {"X-Internal-Token": TOKEN}


def rebuild_payload(fingerprint="fp-1"):
    return {
        "catalogFingerprint": fingerprint,
        "books": [b.model_dump(by_alias=True) for b in small_catalog()],
        "interactions": [{"user": 0, "positives": [1, 2]}],
    }


@pytest.fixture
def client(tmp_path):
    app = create_app(Settings(internal_token=TOKEN, model_dir=tmp_path))
    with TestClient(app) as c:
        yield c


def test_health_before_and_after_rebuild(client):
    assert client.get("/health").json() == {"status": "ok", "modelLoaded": False, "modelVersion": None,
                                            "builtAt": None, "bookCount": None, "catalogFingerprint": None}
    res = client.post("/internal/model/rebuild", json=rebuild_payload(), headers=HEADERS)
    assert res.status_code == 200
    body = res.json()
    assert body["bookCount"] == 8
    assert body["catalogFingerprint"] == "fp-1"
    assert body["evaluation"]["status"] == "insufficient_data"
    health = client.get("/health").json()
    assert health["modelLoaded"] is True and health["modelVersion"] == body["modelVersion"]


def test_internal_endpoints_require_token(client):
    payload = {"likedBookIds": [1], "limit": 3}
    assert client.post("/internal/recommendations/for-you", json=payload).status_code == 401
    assert client.post("/internal/recommendations/for-you", json=payload,
                       headers={"X-Internal-Token": "wrong"}).status_code == 401
    assert client.post("/internal/model/rebuild", json=rebuild_payload()).status_code == 401


def test_token_not_configured_fails_closed(tmp_path):
    app = create_app(Settings(internal_token="", model_dir=tmp_path))
    with TestClient(app) as c:
        res = c.post("/internal/recommendations/for-you", json={}, headers={"X-Internal-Token": ""})
        assert res.status_code == 503


def test_recommendations_before_model_is_built_return_503(client):
    res = client.post("/internal/recommendations/for-you", json={"limit": 3}, headers=HEADERS)
    assert res.status_code == 503
    assert res.json()["detail"]["code"] == "MODEL_NOT_READY"


def test_for_you_and_similar_contract(client):
    client.post("/internal/model/rebuild", json=rebuild_payload(), headers=HEADERS)
    res = client.post("/internal/recommendations/for-you", headers=HEADERS, json={
        "preferredGenreIds": [2], "likedBookIds": [1], "ratings": [{"bookId": 5, "rating": 2}],
        "shelf": [{"bookId": 7, "status": "READING"}], "excludeBookIds": [3], "limit": 4, "offset": 0})
    assert res.status_code == 200
    body = res.json()
    assert body["strategy"] == "CONTENT_HYBRID"
    assert body["modelVersion"]
    assert len(body["items"]) == 4
    assert set(body["items"][0]) == {"bookId", "score", "reason", "strategy"}
    assert not {1, 3, 5, 7} & {i["bookId"] for i in body["items"]}

    sim = client.post("/internal/recommendations/similar", headers=HEADERS, json={"bookId": 3, "limit": 2})
    assert sim.status_code == 200
    assert 3 not in [i["bookId"] for i in sim.json()["items"]]
    missing = client.post("/internal/recommendations/similar", headers=HEADERS, json={"bookId": 404})
    assert missing.status_code == 404


def test_request_validation(client):
    client.post("/internal/model/rebuild", json=rebuild_payload(), headers=HEADERS)
    bad = client.post("/internal/recommendations/for-you", headers=HEADERS,
                      json={"limit": 0, "ratings": [{"bookId": 1, "rating": 9}]})
    assert bad.status_code == 422
    empty = client.post("/internal/model/rebuild", headers=HEADERS, json={"catalogFingerprint": "x", "books": []})
    assert empty.status_code == 422


def test_model_persists_and_reloads_across_restarts(tmp_path):
    settings = Settings(internal_token=TOKEN, model_dir=tmp_path)
    with TestClient(create_app(settings)) as c:
        version = c.post("/internal/model/rebuild", json=rebuild_payload(), headers=HEADERS).json()["modelVersion"]
    with TestClient(create_app(settings)) as c:
        health = c.get("/health").json()
        assert health["modelLoaded"] is True and health["modelVersion"] == version


def test_stale_or_corrupt_artifacts_are_ignored(tmp_path):
    (tmp_path / "versions" / "broken").mkdir(parents=True)
    (tmp_path / "versions" / "broken" / "model.joblib").write_bytes(b"not a model")
    (tmp_path / "CURRENT").write_text("broken")
    with TestClient(create_app(Settings(internal_token=TOKEN, model_dir=tmp_path))) as c:
        assert c.get("/health").json()["modelLoaded"] is False


def test_rebuild_swaps_atomically_and_prunes_old_versions(tmp_path):
    store = ModelStore(tmp_path, keep_versions=2)
    req = RebuildRequest.model_validate(rebuild_payload())
    versions = [store.rebuild(req.model_copy(update={"catalog_fingerprint": f"fp{i}"})).model_version
                for i in range(4)]
    assert store.current().model_version == versions[-1]
    assert (tmp_path / "CURRENT").read_text() == versions[-1]
    remaining = sorted(p.name for p in (tmp_path / "versions").iterdir())
    assert remaining == sorted(versions[-2:])


def test_concurrent_rebuild_is_rejected(tmp_path):
    store = ModelStore(tmp_path)
    req = RebuildRequest.model_validate(rebuild_payload())
    store._rebuild_lock.acquire()
    try:
        with pytest.raises(RebuildInProgressError):
            store.rebuild(req)
    finally:
        store._rebuild_lock.release()
    # readers keep working during a rebuild
    store.rebuild(req)
    results = []
    t = threading.Thread(target=lambda: results.append(store.rebuild(req)))
    t.start()
    for _ in range(50):
        assert store.current() is not None
    t.join()
    assert results
