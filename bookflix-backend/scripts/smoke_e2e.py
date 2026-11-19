"""End-to-end smoke test against a running stack (docker compose up).

    BOOKFLIX_API=http://localhost:8080 \
    BOOKFLIX_DEV_ADMIN_EMAIL=... BOOKFLIX_DEV_ADMIN_PASSWORD=... \
    python bookflix-backend/scripts/smoke_e2e.py

Exercises register -> onboarding -> interactions -> ML-backed recommendations -> admin rebuild.
Exits non-zero on the first failed check. Uses only the standard library.
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
import uuid

API = os.environ.get("BOOKFLIX_API", "http://localhost:8080").rstrip("/")


def call(method, path, token=None, body=None, expect=(200,)):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=90) as res:
            status, raw = res.status, res.read()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read()
    payload = json.loads(raw) if raw else None
    if status not in expect:
        sys.exit(f"FAIL {method} {path}: HTTP {status} {payload}")
    return payload


def check(cond, msg):
    if not cond:
        sys.exit(f"FAIL {msg}")
    print(f"ok   {msg}")


def main():
    email = f"smoke-{uuid.uuid4().hex[:8]}@example.test"
    auth = call("POST", "/api/auth/register", body={"name": "Smoke", "email": email, "password": "smoke-pass-123"},
                expect=(201,))
    tok = auth["accessToken"]
    check(auth["user"]["role"] == "READER", "register creates a READER")

    call("PUT", "/api/users/me/genres", tok, {"genreIds": [6, 14]})
    cold = call("GET", "/api/recommendations/for-you?limit=5", tok)
    print(f"     cold start: strategy={cold['strategy']} degraded={cold['degraded']} model={cold['modelVersion']}")
    check(len(cold["items"]) == 5, "cold-start for-you returns 5 items")

    call("POST", "/api/books/7/like", tok)                                        # Dracula
    call("POST", "/api/books/6/reviews", tok, {"rating": 5}, expect=(201,))       # Frankenstein
    call("POST", "/api/books/1/reviews", tok, {"rating": 1}, expect=(201,))       # Pride and Prejudice
    call("PUT", "/api/bookshelf/32", tok, {"status": "READING", "progress": 30})  # Jekyll & Hyde

    fy = call("GET", "/api/recommendations/for-you?limit=8", tok)
    ids = [i["book"]["id"] for i in fy["items"]]
    print(f"     for-you: strategy={fy['strategy']} degraded={fy['degraded']} model={fy['modelVersion']}")
    for i in fy["items"][:5]:
        print(f"       {i['score']:.3f}  {i['book']['title']:<40} {i['reason']}")
    check(fy["degraded"] is False and fy["strategy"] == "CONTENT_HYBRID", "for-you is served by the ML model")
    check(not {7, 6, 1, 32} & set(ids), "already-interacted books are excluded")
    check(len(ids) == len(set(ids)), "no duplicates")

    byl = call("GET", "/api/recommendations/because-you-liked?limit=5", tok)
    check(byl["sourceBook"]["id"] == 7 and byl["items"], f"because-you-liked anchors on {byl['sourceBook']['title']}")

    sim = call("GET", "/api/recommendations/similar/16?limit=5")
    titles = [i["book"]["title"] for i in sim["items"]]
    print(f"     similar to Hound of the Baskervilles: {titles}")
    check(sim["strategy"] == "CONTENT_SIMILARITY" and 16 not in [i["book"]["id"] for i in sim["items"]],
          "similar is ML-ranked and excludes the source")

    trending = call("GET", "/api/recommendations/trending?limit=5")
    check(trending["strategy"] == "TRENDING" and len(trending["items"]) == 5, "trending (Spring-owned) works anonymously")

    call("GET", "/api/admin/stats", tok, expect=(403,))
    admin_email, admin_pw = os.environ.get("BOOKFLIX_DEV_ADMIN_EMAIL"), os.environ.get("BOOKFLIX_DEV_ADMIN_PASSWORD")
    if admin_email and admin_pw:
        admin = call("POST", "/api/auth/login", body={"email": admin_email, "password": admin_pw})["accessToken"]
        stats = call("GET", "/api/admin/stats", admin)
        check(stats["model"]["available"], f"admin stats report model {stats['model']['modelVersion']}")
        rebuilt = call("POST", "/api/admin/recommendations/rebuild", admin)
        check(rebuilt["status"] == "REBUILT", f"admin rebuild -> {rebuilt['modelVersion']}")
        print(f"     evaluation: {json.dumps(rebuilt['evaluation'])}")
    else:
        print("skip admin checks (BOOKFLIX_DEV_ADMIN_EMAIL/PASSWORD not set)")
    print("SMOKE TEST PASSED")


if __name__ == "__main__":
    main()
