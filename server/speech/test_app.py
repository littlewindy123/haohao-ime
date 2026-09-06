from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import datetime, timezone
import threading

import pytest
from fastapi.testclient import TestClient

from app import EXPIRY, Quota, Settings, SpeechError, create_app


@pytest.fixture
def settings(tmp_path):
    return Settings("test-only-token", "test-id", "test-secret", True, free_remaining=100000,
                    database=str(tmp_path / "quota.sqlite3"))


def client(settings, provider=None, now=1788739200):
    return TestClient(create_app(settings, provider or (lambda *_: b"ID3test-audio"), lambda: now))


def post(app, text="Hello, world!", rate="normal", token="test-only-token"):
    return app.post("/api/v1/speech", json={"text": text, "rate": rate},
                    headers={"Authorization": "Bearer " + token})


def test_expiry_matches_contract():
    assert datetime.fromtimestamp(EXPIRY, timezone.utc).isoformat() == "2026-10-01T00:00:00+00:00"


def test_success_and_slow(settings):
    calls = []
    app = client(settings, lambda _, text, rate: calls.append((text, rate)) or b"ID3test")
    response = post(app, rate="slow")
    assert response.status_code == 200
    assert response.headers["content-type"] == "audio/mpeg"
    assert response.headers["cache-control"] == "no-store"
    assert calls == [("Hello, world!", "slow")]


@pytest.mark.parametrize("changed,code,status", [
    ({"token": ""}, "UNAUTHORIZED", 401),
    ({"approved": False}, "NOT_CONFIGURED", 503),
    ({"secret_id": ""}, "NOT_CONFIGURED", 503),
    ({"expires": 1}, "EXPIRED", 403),
    ({"free_remaining": 0}, "QUOTA_UNAVAILABLE", 429),
])
def test_fail_closed(settings, changed, code, status):
    def forbidden(*_):
        raise AssertionError("provider must not run")
    response = post(client(replace(settings, **changed), forbidden))
    assert response.status_code == status
    assert response.json() == {"code": code}


@pytest.mark.parametrize("text", ["", "a" * 451, "中文", "<speak>Hello</speak>", "abc\x00"])
def test_invalid_text_does_not_call_provider(settings, text):
    calls = []
    response = post(client(settings, lambda *_: calls.append(1)), text)
    assert response.status_code == 400
    assert not calls
    assert "input" not in response.json()


def test_unauthorized(settings):
    assert post(client(settings), token="wrong").status_code == 401


def test_bad_audio_is_not_returned_as_success(settings):
    assert post(client(settings, lambda *_: b"<html>error</html>")).json() == {"code": "INVALID_AUDIO"}


def test_absolute_timeout_does_not_release_a_still_running_provider_slot(settings):
    gate = threading.Event()
    calls = []
    def slow(*_):
        calls.append(1)
        gate.wait(2)
        return b"ID3test"
    app = TestClient(create_app(settings, slow, lambda: 1788739200, provider_timeout=0.03))
    try:
        assert post(app).json() == {"code": "TIMEOUT"}
        assert post(app).json() == {"code": "TIMEOUT"}
        assert post(app).json() == {"code": "BUSY"}
        assert len(calls) == 2
    finally:
        gate.set()


def test_rate_limit_survives_restart(settings):
    app = client(settings)
    for _ in range(10):
        assert post(app).status_code == 200
    assert post(client(settings)).json() == {"code": "RATE_LIMITED"}


def test_total_and_daily_limit_survive_restart(settings):
    settings = replace(settings, daily_limit=15)
    assert post(client(settings)).status_code == 200
    assert post(client(settings)).json() == {"code": "QUOTA_UNAVAILABLE"}


def test_provider_failure_reserves_quota_without_leaking_error(settings):
    def fail(*_):
        raise RuntimeError("do not expose credentials or input")
    settings = replace(settings, daily_limit=15)
    assert post(client(settings, fail)).json() == {"code": "UPSTREAM_FAILED"}
    assert post(client(settings)).json() == {"code": "QUOTA_UNAVAILABLE"}


def test_quota_atomic_under_concurrency(settings):
    quota = Quota(settings.database)
    settings = replace(settings, daily_limit=20)
    def reserve(index):
        try:
            quota.reserve(str(index), 10, 1788739200, settings)
            return True
        except SpeechError:
            return False
    with ThreadPoolExecutor(max_workers=8) as pool:
        assert sum(pool.map(reserve, range(8))) == 2


def test_two_inflight_maximum(settings):
    ready = threading.Barrier(3)
    release = threading.Event()
    def provider(*_):
        ready.wait(timeout=3)
        release.wait(timeout=3)
        return b"ID3test"
    app = client(settings, provider)
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(post, app) for _ in range(2)]
        ready.wait(timeout=3)
        try:
            assert post(app).json() == {"code": "BUSY"}
        finally:
            release.set()
        assert all(f.result().status_code == 200 for f in futures)
