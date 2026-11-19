"""Live contract verification against a running stack (docker compose up).

    BOOKFLIX_API=http://localhost:8081 \
    BOOKFLIX_DEV_ADMIN_EMAIL=... BOOKFLIX_DEV_ADMIN_PASSWORD=... \
    python bookflix-backend/scripts/verify_contract.py            # ML up
    python bookflix-backend/scripts/verify_contract.py --fallback # after: docker compose stop recommendation-service

Checks every endpoint group in docs/api/contracts.md: response shapes, JWT/role/ownership rules, the
standard error payload, that recommendation rankings react to genres/likes/ratings/shelf changes, and
(with --fallback) that every recommendation shelf degrades to 200 when the ML service is down.

Uses about 7 login/register calls (the auth rate limit is 10/minute per IP). Standard library only.
Prints every check and exits non-zero if any failed.
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
import uuid

API = os.environ.get("BOOKFLIX_API", "http://localhost:8080").rstrip("/")
FAILURES: list[str] = []
PASSED = 0

ERROR_KEYS = {"code", "message", "details", "timestamp", "path"}
USER_KEYS = {"id", "name", "email", "role", "onboardingCompleted", "genres", "createdAt"}
AUTH_KEYS = {"accessToken", "tokenType", "expiresIn", "user"}
SUMMARY_KEYS = {"id", "title", "author", "coverUrl", "publishedYear", "genres", "averageRating",
                "ratingCount", "likeCount"}
DETAIL_KEYS = SUMMARY_KEYS | {"description", "isbn", "language", "createdAt", "updatedAt", "me"}
ME_KEYS = {"liked", "rating", "shelfStatus", "progress"}
PAGE_KEYS = {"items", "page", "size", "totalElements", "totalPages"}
GENRE_KEYS = {"id", "name", "slug"}
AUTHOR_KEYS = {"id", "name", "bio", "createdAt"}
LIKE_KEYS = {"bookId", "liked", "likeCount"}
REVIEW_KEYS = {"id", "bookId", "user", "rating", "comment", "createdAt", "updatedAt"}
SHELF_KEYS = {"bookId", "book", "status", "progress", "updatedAt"}
STATS_KEYS = {"likedCount", "reviewCount", "averageRatingGiven", "bookshelf"}
REC_KEYS = {"shelf", "strategy", "degraded", "modelVersion", "sourceBook", "items"}
REC_ITEM_KEYS = {"book", "score", "reason", "strategy"}
ADMIN_STATS_KEYS = {"users", "readers", "admins", "books", "deletedBooks", "authors", "genres", "reviews",
                    "likes", "bookshelfEntries", "model"}
MODEL_KEYS = {"enabled", "available", "modelVersion", "builtAt", "bookCount", "stale", "lastError"}


def call(method, path, token=None, body=None, raw_body=None, headers=None):
    data = raw_body if raw_body is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=90) as res:
            status, raw = res.status, res.read()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read()
    try:
        payload = json.loads(raw) if raw else None
    except json.JSONDecodeError:
        payload = raw.decode(errors="replace")
    return status, payload


def check(cond, msg):
    global PASSED
    if cond:
        PASSED += 1
        print(f"ok   {msg}")
    else:
        FAILURES.append(msg)
        print(f"FAIL {msg}")
    return cond


def has_keys(obj, keys):
    return isinstance(obj, dict) and keys <= obj.keys()


def expect(method, path, status, token=None, body=None, keys=None, **kw):
    s, p = call(method, path, token, body, **kw)
    ok = s == status and (keys is None or has_keys(p, keys))
    check(ok, f"{method} {path} -> {status}" + ("" if ok else f" (got {s}: {str(p)[:200]})"))
    return p


def expect_error(method, path, status, code, token=None, body=None, **kw):
    s, p = call(method, path, token, body, **kw)
    ok = (s == status and has_keys(p, ERROR_KEYS) and p["code"] == code and isinstance(p["details"], list)
          and "trace" not in p and "exception" not in p)
    check(ok, f"{method} {path} -> {status} {code}" + ("" if ok else f" (got {s}: {str(p)[:200]})"))
    return p


def ids(rec):
    return [i["book"]["id"] for i in rec["items"]]


def rec_ok(rec, shelf, msg):
    ok = (has_keys(rec, REC_KEYS) and rec["shelf"] == shelf
          and all(has_keys(i, REC_ITEM_KEYS) and has_keys(i["book"], SUMMARY_KEYS)
                  and 0.0 <= i["score"] <= 1.0 and i["reason"] for i in rec["items"])
          and len(set(ids(rec))) == len(rec["items"]))
    check(ok, f"{msg}: envelope/items well-formed, scores in [0,1], no duplicates")


def book_id(title):
    _, p = call("GET", "/api/books?size=5&q=" + urllib.request.quote(title))
    for b in p["items"]:
        if b["title"].lower() == title.lower():
            return b["id"]
    raise SystemExit(f"seed book not found: {title}")


def genre_ids():
    _, p = call("GET", "/api/genres")
    return {g["slug"]: g["id"] for g in p}


def register(name):
    email = f"verify-{uuid.uuid4().hex[:10]}@example.test"
    p = expect("POST", "/api/auth/register", 201,
               body={"name": name, "email": email, "password": "verify-pass-123", "role": "ADMIN"}, keys=AUTH_KEYS)
    return p, email


# --------------------------------------------------------------------------------------------------------
def live_checks():
    g = genre_ids()
    admin_email = os.environ.get("BOOKFLIX_DEV_ADMIN_EMAIL")
    admin_pw = os.environ.get("BOOKFLIX_DEV_ADMIN_PASSWORD")

    print("\n== Auth & users")
    a_auth, a_email = register("Reader A")
    a = a_auth["accessToken"]
    check(a_auth["tokenType"] == "Bearer" and a_auth["expiresIn"] > 0, "AuthResponse tokenType/expiresIn")
    check(a_auth["user"]["role"] == "READER", "register ignores role=ADMIN in body -> READER")
    check(has_keys(a_auth["user"], USER_KEYS) and "passwordHash" not in a_auth["user"], "UserDto shape, no hash")
    check(a_auth["user"]["onboardingCompleted"] is False, "new user onboardingCompleted=false")
    expect_error("POST", "/api/auth/register", 409, "EMAIL_ALREADY_EXISTS",
                 body={"name": "Dup", "email": a_email.upper(), "password": "verify-pass-123"})
    e = expect_error("POST", "/api/auth/register", 400, "VALIDATION_ERROR",
                     body={"name": "", "email": "not-an-email", "password": "short"})
    check(e is not None and {d.get("field") for d in e["details"]} >= {"name", "email", "password"},
          "validation details list every bad field")
    expect("POST", "/api/auth/login", 200, body={"email": a_email, "password": "verify-pass-123"}, keys=AUTH_KEYS)
    expect_error("POST", "/api/auth/login", 401, "INVALID_CREDENTIALS",
                 body={"email": a_email, "password": "wrong-password"})
    expect_error("POST", "/api/auth/login", 400, "MALFORMED_REQUEST", raw_body=b"{not json")
    expect_error("GET", "/api/users/me", 401, "UNAUTHORIZED")
    expect_error("GET", "/api/users/me", 401, "UNAUTHORIZED", token="garbage.token.value")
    expect_error("GET", "/api/books", 401, "UNAUTHORIZED", token="garbage.token.value")
    expect("GET", "/api/users/me", 200, a, keys=USER_KEYS)
    p = expect("PUT", "/api/users/me", 200, a, {"name": "Reader A2"}, keys=USER_KEYS)
    check(p and p["name"] == "Reader A2", "PUT /users/me updates name")
    expect_error("PUT", "/api/users/me/genres", 400, "BAD_REQUEST", a, {"genreIds": [999999]})
    p = expect("PUT", "/api/users/me/genres", 200, a, {"genreIds": [g["horror"], g["gothic"]]}, keys=USER_KEYS)
    check(p and p["onboardingCompleted"] and {x["id"] for x in p["genres"]} == {g["horror"], g["gothic"]},
          "PUT /users/me/genres sets genres and onboardingCompleted")
    p = expect("GET", "/api/users/me/stats", 200, a, keys=STATS_KEYS)
    check(p and set(p["bookshelf"]) == {"WANT_TO_READ", "READING", "COMPLETED"}, "stats bookshelf keys")

    print("\n== Catalog")
    p = expect("GET", "/api/books?page=0&size=5", 200, keys=PAGE_KEYS)
    check(p and len(p["items"]) == 5 and all(has_keys(b, SUMMARY_KEYS) for b in p["items"])
          and p["totalElements"] >= 60, "book page shape, seeded catalog present")
    p = expect("GET", "/api/books?q=vampir&size=10", 200, keys=PAGE_KEYS)
    check(p and any(b["title"] == "Dracula" for b in p["items"]), "search prefix 'vampir' finds Dracula")
    p = expect("GET", f"/api/books?genreId={g['science-fiction']}&size=50", 200, keys=PAGE_KEYS)
    check(p and p["items"] and all(any(x["id"] == g["science-fiction"] for x in b["genres"]) for b in p["items"]),
          "genreId filter")
    p = expect("GET", "/api/books?sort=year_desc&size=10", 200)
    yrs = [b["publishedYear"] for b in p["items"] if b["publishedYear"] is not None]
    check(yrs == sorted(yrs, reverse=True), "sort=year_desc ordering")
    expect_error("GET", "/api/books?sort=bogus", 400, "BAD_REQUEST")
    expect_error("GET", "/api/books?size=51", 400, "VALIDATION_ERROR")
    expect_error("GET", "/api/books?page=-1", 400, "VALIDATION_ERROR")
    dracula, frank, jekyll = book_id("Dracula"), book_id("Frankenstein"), book_id("Carmilla")
    p = expect("GET", f"/api/books/{dracula}", 200, keys=DETAIL_KEYS)
    check(p and p["me"] is None, "anonymous detail has me=null")
    p = expect("GET", f"/api/books/{dracula}", 200, a, keys=DETAIL_KEYS)
    check(p and has_keys(p["me"], ME_KEYS), "authenticated detail has me state")
    expect_error("GET", "/api/books/999999", 404, "NOT_FOUND")
    e = expect_error("GET", "/api/books/abc", 400, "VALIDATION_ERROR")
    check(e is not None and e["details"] and e["details"][0]["field"] == "id", "type mismatch names the field")
    p = expect("GET", "/api/genres", 200)
    check(isinstance(p, list) and all(has_keys(x, GENRE_KEYS) for x in p)
          and [x["name"] for x in p] == sorted(x["name"] for x in p), "genres sorted by name")
    p = expect("GET", "/api/authors?q=shelley", 200)
    check(isinstance(p, list) and p and all(has_keys(x, AUTHOR_KEYS) and "shelley" in x["name"].lower() for x in p),
          "authors filter q")

    print("\n== Recommendations: rankings react to user signals (ML up)")
    b_auth, _ = register("Reader B")
    b = b_auth["accessToken"]
    r0 = expect("GET", "/api/recommendations/for-you?limit=10", 200, b, keys=REC_KEYS)
    rec_ok(r0, "FOR_YOU", "no-signal for-you")
    check(r0["strategy"] == "POPULARITY" and not r0["degraded"] and r0["modelVersion"],
          f"no signals -> ML POPULARITY (got {r0['strategy']}, degraded={r0['degraded']})")
    call("PUT", "/api/users/me/genres", b, {"genreIds": [g["science-fiction"]]})
    r1 = expect("GET", "/api/recommendations/for-you?limit=10", 200, b)
    check(r1["strategy"] == "GENRE_POPULARITY", f"genres only -> GENRE_POPULARITY (got {r1['strategy']})")
    check(ids(r1) != ids(r0), "setting genres changes the ranking")
    sf = g["science-fiction"]
    check(sum(any(x["id"] == sf for x in i["book"]["genres"]) for i in r1["items"][:5]) >= 4,
          "cold start top-5 dominated by chosen genre (science-fiction)")
    call("PUT", "/api/users/me/genres", b, {"genreIds": [g["romance"]]})
    r2 = expect("GET", "/api/recommendations/for-you?limit=10", 200, b)
    check(ids(r2) != ids(r1), "changing genres (sci-fi -> romance) changes the ranking")
    call("PUT", "/api/users/me/genres", b, {"genreIds": [g["horror"]]})
    r3 = expect("GET", "/api/recommendations/for-you?limit=10", 200, b)

    like = expect("POST", f"/api/books/{dracula}/like", 200, b, keys=LIKE_KEYS)
    check(like and like["liked"] is True, "like -> liked=true")
    again = expect("POST", f"/api/books/{dracula}/like", 200, b, keys=LIKE_KEYS)
    check(again and again["likeCount"] == like["likeCount"], "like is idempotent (count unchanged)")
    r4 = expect("GET", "/api/recommendations/for-you?limit=10", 200, b)
    rec_ok(r4, "FOR_YOU", "after like")
    check(r4["strategy"] == "CONTENT_HYBRID" and not r4["degraded"],
          f"after like -> CONTENT_HYBRID (got {r4['strategy']})")
    check(ids(r4) != ids(r3), "liking Dracula changes the ranking")
    check(dracula not in ids(r4), "liked book excluded from for-you")
    check(any("Dracula" in i["reason"] for i in r4["items"]), "a reason cites 'Similar to Dracula'")

    expect("POST", f"/api/books/{frank}/reviews", 201, b, {"rating": 5, "comment": "Great"}, keys=REVIEW_KEYS)
    r5 = expect("GET", "/api/recommendations/for-you?limit=10", 200, b)
    check(ids(r5) != ids(r4), "a 5-star rating changes the ranking")
    check(frank not in ids(r5), "rated book excluded from for-you")

    expect("PUT", f"/api/bookshelf/{jekyll}", 200, b, {"status": "COMPLETED"}, keys=SHELF_KEYS)
    r6 = expect("GET", "/api/recommendations/for-you?limit=10", 200, b)
    check(ids(r6) != ids(r5), "a COMPLETED shelf entry changes the ranking")
    check(jekyll not in ids(r6), "shelved book excluded from for-you")

    neg = r6["items"][0]["book"]["id"]
    expect("POST", f"/api/books/{neg}/reviews", 201, b, {"rating": 1})
    r7 = expect("GET", "/api/recommendations/for-you?limit=10", 200, b)
    check(neg not in ids(r7) and ids(r7) != ids(r6), "a 1-star rating removes that book and re-ranks")
    r7b = expect("GET", "/api/recommendations/for-you?limit=10", 200, b)
    check(ids(r7b) == ids(r7) and [i["score"] for i in r7b["items"]] == [i["score"] for i in r7["items"]],
          "for-you is deterministic for unchanged signals")
    check([i["score"] for i in r7["items"]] == sorted((i["score"] for i in r7["items"]), reverse=True),
          "for-you sorted by score desc")

    s = expect("GET", f"/api/recommendations/similar/{dracula}?limit=8", 200, keys=REC_KEYS)
    rec_ok(s, "SIMILAR", "similar (anonymous)")
    check(s["strategy"] == "CONTENT_SIMILARITY" and s["modelVersion"] and dracula not in ids(s)
          and s["sourceBook"]["id"] == dracula, "similar: ML CONTENT_SIMILARITY, source excluded, sourceBook set")
    s2 = expect("GET", f"/api/recommendations/similar/{book_id('Pride and Prejudice')}?limit=8", 200)
    check(ids(s2) != ids(s), "similar differs by source book")
    expect_error("GET", "/api/recommendations/similar/999999", 404, "NOT_FOUND")
    byl = expect("GET", "/api/recommendations/because-you-liked?limit=6", 200, b, keys=REC_KEYS)
    rec_ok(byl, "BECAUSE_YOU_LIKED", "because-you-liked")
    check(byl["sourceBook"] and byl["sourceBook"]["id"] == dracula
          and all(i["reason"] == "Because you liked Dracula" for i in byl["items"]),
          "because-you-liked anchors on latest like")
    bg = expect("GET", "/api/recommendations/genres?limit=6", 200, b, keys=REC_KEYS)
    rec_ok(bg, "BY_GENRES", "genres shelf")
    t = expect("GET", "/api/recommendations/trending?limit=10", 200, keys=REC_KEYS)
    rec_ok(t, "TRENDING", "trending (anonymous)")
    check(t["strategy"] == "TRENDING", "trending is SQL-owned (strategy TRENDING)")
    expect_error("GET", "/api/recommendations/for-you", 401, "UNAUTHORIZED")
    expect_error("GET", "/api/recommendations/for-you?limit=0", 400, "VALIDATION_ERROR", b)
    expect_error("GET", "/api/recommendations/for-you?limit=51", 400, "VALIDATION_ERROR", b)

    print("\n== Likes & reviews")
    p = expect("DELETE", f"/api/books/{dracula}/like", 200, b, keys=LIKE_KEYS)
    check(p and p["liked"] is False, "unlike -> liked=false")
    expect("DELETE", f"/api/books/{dracula}/like", 200, b, keys=LIKE_KEYS)
    expect_error("POST", f"/api/books/{dracula}/like", 401, "UNAUTHORIZED")
    expect_error("POST", "/api/books/999999/like", 404, "NOT_FOUND", b)
    expect("POST", f"/api/books/{frank}/reviews", 200, b, {"rating": 4, "comment": "Updated"}, keys=REVIEW_KEYS)
    expect_error("POST", f"/api/books/{frank}/reviews", 400, "VALIDATION_ERROR", b, {"rating": 6})
    p = expect("GET", f"/api/books/{frank}/reviews?page=0&size=10", 200, keys=PAGE_KEYS)
    mine = [r for r in p["items"] if r["user"]["name"] == "Reader B"]
    check(len(mine) == 1 and mine[0]["rating"] == 4 and "email" not in mine[0]["user"],
          "one review per user (upsert), no email exposed")
    d = expect("GET", f"/api/books/{frank}", 200, b)
    check(d["me"]["rating"] == 4 and d["ratingCount"] >= 1, "detail me.rating and aggregates updated")
    expect("DELETE", f"/api/books/{frank}/reviews/me", 204, b)
    expect("DELETE", f"/api/books/{frank}/reviews/me", 204, b)

    print("\n== Bookshelf & ownership")
    p = expect("PUT", f"/api/bookshelf/{dracula}", 200, a, {"status": "READING", "progress": 40}, keys=SHELF_KEYS)
    check(p and p["status"] == "READING" and p["progress"] == 40, "shelf upsert READING 40%")
    p = expect("PUT", f"/api/bookshelf/{dracula}", 200, a, {"status": "COMPLETED"})
    check(p and p["progress"] == 100, "COMPLETED forces progress=100")
    expect("PUT", f"/api/bookshelf/{frank}", 200, a, {"status": "WANT_TO_READ"})
    p = expect("GET", "/api/bookshelf?status=COMPLETED", 200, a)
    check(isinstance(p, list) and [e["bookId"] for e in p] == [dracula], "GET bookshelf?status filter")
    expect_error("PUT", f"/api/bookshelf/{dracula}", 400, "VALIDATION_ERROR", a, {"status": "READING", "progress": 101})
    expect_error("PUT", f"/api/bookshelf/{dracula}", 400, "MALFORMED_REQUEST", a, {"status": "FINISHED"})
    expect_error("GET", "/api/bookshelf?status=FINISHED", 400, "VALIDATION_ERROR", a)
    expect_error("GET", "/api/bookshelf", 401, "UNAUTHORIZED")
    p = expect("GET", "/api/bookshelf", 200, b)
    check(all(e["bookId"] != frank for e in p) and [e["bookId"] for e in p] == [jekyll],
          "reader B sees only B's shelf (A's entries invisible)")
    expect("DELETE", f"/api/bookshelf/{frank}", 204, b)  # B deleting a book only A shelved is a no-op
    p = expect("GET", "/api/bookshelf?status=WANT_TO_READ", 200, a)
    check([e["bookId"] for e in p] == [frank], "B cannot delete A's shelf entry")
    p = expect("GET", "/api/users/me/stats", 200, a)
    check(p["bookshelf"] == {"WANT_TO_READ": 1, "READING": 0, "COMPLETED": 1}, "stats reflect own shelf")
    expect("DELETE", f"/api/bookshelf/{frank}", 204, a)

    print("\n== Admin")
    expect_error("GET", "/api/admin/stats", 401, "UNAUTHORIZED")
    expect_error("GET", "/api/admin/stats", 403, "FORBIDDEN", a)
    expect_error("POST", "/api/admin/genres", 403, "FORBIDDEN", a, {"name": "Nope"})
    expect_error("POST", "/api/admin/recommendations/rebuild", 403, "FORBIDDEN", a)
    if not (admin_email and admin_pw):
        print("skip admin CRUD: BOOKFLIX_DEV_ADMIN_EMAIL/PASSWORD not set")
        return
    adm = expect("POST", "/api/auth/login", 200, body={"email": admin_email, "password": admin_pw})["accessToken"]
    p = expect("GET", "/api/admin/stats", 200, adm, keys=ADMIN_STATS_KEYS)
    check(p and has_keys(p["model"], MODEL_KEYS) and p["model"]["available"], "admin stats model block, ML available")
    tag = uuid.uuid4().hex[:6]
    gen = expect("POST", "/api/admin/genres", 201, adm, {"name": f"Verify Genre {tag}"}, keys=GENRE_KEYS)
    check(gen["slug"] == f"verify-genre-{tag}", "genre slug derived from name")
    expect_error("POST", "/api/admin/genres", 409, "CONFLICT", adm, {"name": f"Verify Genre {tag}"})
    gen = expect("PUT", f"/api/admin/genres/{gen['id']}", 200, adm, {"name": f"Verify Genre {tag} X"}, keys=GENRE_KEYS)
    au = expect("POST", "/api/admin/authors", 201, adm, {"name": f"Verify Author {tag}", "bio": "b"}, keys=AUTHOR_KEYS)
    expect("PUT", f"/api/admin/authors/{au['id']}", 200, adm, {"name": f"Verify Author {tag} X"}, keys=AUTHOR_KEYS)
    bk = expect("POST", "/api/admin/books", 201, adm,
                {"title": f"Verify Book {tag}", "description": "A test vampire tale.", "authorId": au["id"],
                 "genreIds": [gen["id"], g["horror"]], "publishedYear": 1900}, keys=DETAIL_KEYS)
    expect_error("POST", "/api/admin/books", 400, "VALIDATION_ERROR", adm, {"title": "", "authorId": au["id"], "genreIds": []})
    expect("PUT", f"/api/admin/books/{bk['id']}", 200, adm,
           {"title": f"Verify Book {tag} X", "authorId": au["id"], "genreIds": [gen["id"]]}, keys=DETAIL_KEYS)
    expect_error("DELETE", f"/api/admin/authors/{au['id']}", 409, "CONFLICT", adm)
    expect_error("DELETE", f"/api/admin/genres/{gen['id']}", 409, "CONFLICT", adm)
    stale = expect("GET", "/api/admin/stats", 200, adm)["model"]["stale"]
    check(stale is True, "catalog change marks model stale")
    rb = expect("POST", "/api/admin/recommendations/rebuild", 200, adm)
    check(rb and rb["status"] == "REBUILT" and rb["bookCount"] >= 60 and "status" in rb["evaluation"],
          f"rebuild REBUILT, evaluation status={rb and rb['evaluation'].get('status')}")
    s = expect("GET", f"/api/recommendations/similar/{bk['id']}?limit=3", 200)
    check(s["modelVersion"] == rb["modelVersion"], "new book is in the rebuilt model")
    expect("DELETE", f"/api/admin/books/{bk['id']}", 204, adm)
    expect_error("GET", f"/api/books/{bk['id']}", 404, "NOT_FOUND")
    expect("DELETE", f"/api/admin/genres/{gen['id']}", 204, adm)
    expect_error("DELETE", f"/api/admin/authors/{au['id']}", 409, "CONFLICT", adm)  # soft-deleted book still refs
    expect_error("DELETE", "/api/admin/books/999999", 404, "NOT_FOUND", adm)


def fallback_checks():
    """Run with the recommendation-service container stopped."""
    g = genre_ids()
    dracula = book_id("Dracula")
    auth, _ = register("Fallback")
    t = auth["accessToken"]
    r = expect("GET", "/api/recommendations/for-you?limit=10", 200, t, keys=REC_KEYS)
    check(r["degraded"] and r["strategy"] == "FALLBACK_POPULARITY" and r["items"],
          f"no-signal for-you degraded -> {r['strategy']}")
    call("PUT", "/api/users/me/genres", t, {"genreIds": [g["science-fiction"]]})
    r = expect("GET", "/api/recommendations/for-you?limit=10", 200, t)
    check(r["degraded"] and r["strategy"] == "FALLBACK_GENRE_POPULARITY" and r["items"],
          f"genre for-you degraded -> {r['strategy']}")
    call("POST", f"/api/books/{dracula}/like", t)
    r = expect("GET", "/api/recommendations/for-you?limit=10", 200, t)
    check(r["degraded"] and dracula not in ids(r) and r["items"], "liked-user for-you degraded, exclusions kept")
    rec_ok(r, "FOR_YOU", "degraded for-you")
    s = expect("GET", f"/api/recommendations/similar/{dracula}?limit=8", 200)
    check(s["degraded"] and s["strategy"] == "FALLBACK_METADATA_SIMILARITY" and dracula not in ids(s) and s["items"],
          "similar degraded -> FALLBACK_METADATA_SIMILARITY")
    b = expect("GET", "/api/recommendations/because-you-liked?limit=6", 200, t)
    check(b["degraded"] and b["sourceBook"]["id"] == dracula and b["items"], "because-you-liked degraded")
    expect("GET", "/api/recommendations/genres?limit=6", 200, t, keys=REC_KEYS)
    tr = expect("GET", "/api/recommendations/trending?limit=6", 200, keys=REC_KEYS)
    check(tr["items"] and not tr["degraded"], "trending unaffected (SQL-owned)")
    admin_email = os.environ.get("BOOKFLIX_DEV_ADMIN_EMAIL")
    admin_pw = os.environ.get("BOOKFLIX_DEV_ADMIN_PASSWORD")
    if admin_email and admin_pw:
        p = expect("POST", "/api/auth/login", 200, body={"email": admin_email, "password": admin_pw}, keys=AUTH_KEYS)
        if not p:
            return
        adm = p["accessToken"]
        expect_error("POST", "/api/admin/recommendations/rebuild", 503, "ML_UNAVAILABLE", adm)
        m = expect("GET", "/api/admin/stats", 200, adm)["model"]
        check(m["available"] is False, "admin stats reports ML unavailable")


if __name__ == "__main__":
    print(f"API = {API}")
    fallback_checks() if "--fallback" in sys.argv else live_checks()
    print(f"\n{PASSED} passed, {len(FAILURES)} failed")
    for f in FAILURES:
        print(f"  FAIL {f}")
    sys.exit(1 if FAILURES else 0)
