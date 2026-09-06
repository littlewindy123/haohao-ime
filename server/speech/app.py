"""Private English speech gateway. No request text, audio, or credentials are logged."""

import base64
import hashlib
import hmac
import os
import re
import sqlite3
import threading
import time
import uuid
from dataclasses import dataclass
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeout
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Literal

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, ConfigDict, Field

EXPIRY = 1790812800  # 2026-10-01 00:00:00 UTC: September 30 is the final test day.


class SpeechError(Exception):
    def __init__(self, code: str, status: int):
        self.code, self.status = code, status


@dataclass(frozen=True)
class Settings:
    token: str
    secret_id: str = ""
    secret_key: str = ""
    approved: bool = False
    expires: int = EXPIRY
    daily_limit: int = 50000
    free_remaining: int = 0
    database: str = "/var/lib/haohao-speech/quota.sqlite3"

    @classmethod
    def environment(cls):
        return cls(
            token=os.getenv("SPEECH_CLIENT_TOKEN", ""),
            secret_id=os.getenv("TENCENT_SECRET_ID", ""),
            secret_key=os.getenv("TENCENT_SECRET_KEY", ""),
            approved=os.getenv("SPEECH_FREE_ONLY_VERIFIED") == "true",
            expires=min(EXPIRY, int(os.getenv("SPEECH_FREE_EXPIRES_AT", "0"))),
            free_remaining=max(0, int(os.getenv("SPEECH_FREE_CHARACTER_ALLOWANCE", "0"))),
            database=os.getenv("SPEECH_QUOTA_DB", "/var/lib/haohao-speech/quota.sqlite3"),
        )


class Payload(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: str = Field(min_length=1, max_length=450)
    rate: Literal["normal", "slow"] = "normal"


def validate_text(text: str) -> str:
    text = text.strip()
    if (not re.search(r"[A-Za-z]", text) or re.search(r"[<>\x00-\x08\x0b\x0c\x0e-\x1f]", text)
            or any(c.isalpha() and not ("a" <= c.lower() <= "z") for c in text)):
        raise SpeechError("INVALID_TEXT", 400)
    return text


class Quota:
    """Atomic reservation; failures are conservatively counted, not refunded."""
    def __init__(self, path: str):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        with self.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS days(day TEXT PRIMARY KEY, characters INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS sources(source TEXT, minute INTEGER, requests INTEGER,
                    PRIMARY KEY(source, minute));
            """)

    def connect(self):
        return sqlite3.connect(self.path, timeout=3)

    def reserve(self, source: str, count: int, now: float, settings: Settings):
        day = datetime.fromtimestamp(now, timezone.utc).strftime("%Y-%m-%d")
        minute = int(now // 60)
        source = hashlib.sha256(source.encode()).hexdigest()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            requests = db.execute("SELECT requests FROM sources WHERE source=? AND minute=?", (source, minute)).fetchone()
            if requests and requests[0] >= 10:
                raise SpeechError("RATE_LIMITED", 429)
            used = db.execute("SELECT characters FROM days WHERE day=?", (day,)).fetchone()
            total = db.execute("SELECT COALESCE(SUM(characters), 0) FROM days").fetchone()[0]
            if (used[0] if used else 0) + count > settings.daily_limit or total + count > settings.free_remaining:
                raise SpeechError("QUOTA_UNAVAILABLE", 429)
            db.execute("INSERT INTO days VALUES(?, ?) ON CONFLICT(day) DO UPDATE SET characters=characters+excluded.characters", (day, count))
            db.execute("INSERT INTO sources VALUES(?, ?, 1) ON CONFLICT(source, minute) DO UPDATE SET requests=requests+1", (source, minute))
            db.execute("DELETE FROM sources WHERE minute < ?", (minute - 2,))


def tencent_provider(settings: Settings, text: str, rate: str) -> bytes:
    from tencentcloud.common import credential
    from tencentcloud.common.exception.tencent_cloud_sdk_exception import TencentCloudSDKException
    from tencentcloud.common.profile.client_profile import ClientProfile
    from tencentcloud.common.profile.http_profile import HttpProfile
    from tencentcloud.tts.v20190823 import models, tts_client

    http = HttpProfile(reqTimeout=6, endpoint="tts.tencentcloudapi.com", protocol="https")
    profile = ClientProfile(httpProfile=http)
    profile.retryer = None
    client = tts_client.TtsClient(credential.Credential(settings.secret_id, settings.secret_key), "", profile)
    req = models.TextToVoiceRequest()
    req.Text, req.SessionId = text, str(uuid.uuid4())
    req.VoiceType, req.PrimaryLanguage, req.ModelType = 101050, 2, 1
    req.Codec, req.SampleRate, req.Speed = "mp3", 16000, -1 if rate == "slow" else 0
    try:
        result = client.TextToVoice(req)
        audio = base64.b64decode(result.Audio, validate=True)
        if not audio or len(audio) > 2_000_000:
            raise SpeechError("INVALID_AUDIO", 502)
        return audio
    except TencentCloudSDKException as error:
        code = error.get_code() or ""
        if code.startswith("AuthFailure"):
            raise SpeechError("UPSTREAM_AUTH", 503) from None
        if "Limit" in code:
            raise SpeechError("RATE_LIMITED", 429) from None
        if "Arrears" in code or "Resource" in code or "NotRegistered" in code:
            raise SpeechError("QUOTA_UNAVAILABLE", 503) from None
        if "Timeout" in code:
            raise SpeechError("TIMEOUT", 504) from None
        raise SpeechError("UPSTREAM_FAILED", 502) from None


def create_app(settings: Settings, provider: Callable = tencent_provider, clock: Callable = time.time, provider_timeout: float = 7):
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    quota = Quota(settings.database)
    slots = threading.BoundedSemaphore(2)
    workers = ThreadPoolExecutor(max_workers=2, thread_name_prefix="speech-provider")

    @app.exception_handler(SpeechError)
    async def speech_error(_request, error):
        return JSONResponse({"code": error.code}, status_code=error.status, headers={"Cache-Control": "no-store"})

    @app.exception_handler(RequestValidationError)
    async def invalid(_request, _error):
        return JSONResponse({"code": "INVALID_REQUEST"}, status_code=400)

    @app.get("/health")
    def health():
        return {"status": "ok"}

    @app.post("/api/v1/speech")
    def speech(payload: Payload, request: Request):
        auth = request.headers.get("authorization", "")
        if not settings.token or not hmac.compare_digest(auth, "Bearer " + settings.token):
            raise SpeechError("UNAUTHORIZED", 401)
        now = clock()
        if not settings.approved or not settings.secret_id or not settings.secret_key:
            raise SpeechError("NOT_CONFIGURED", 503)
        if now >= settings.expires:
            raise SpeechError("EXPIRED", 403)
        text = validate_text(payload.text)
        if not slots.acquire(blocking=False):
            raise SpeechError("BUSY", 429)
        try:
            # This process binds loopback only; Nginx replaces X-Real-IP, never forwards the caller's value.
            source = request.headers.get("x-real-ip") or (request.client.host if request.client else "unknown")
            quota.reserve(source, len(text), now, settings)
            pending = workers.submit(provider, settings, text, payload.rate)
        except Exception:
            slots.release()
            raise
        # A timed-out request keeps its provider slot until the SDK actually finishes.
        # A retry can therefore never create more than two in-flight paid operations.
        pending.add_done_callback(lambda _: slots.release())
        try:
            audio = pending.result(timeout=provider_timeout)
            valid = isinstance(audio, bytes) and 3 <= len(audio) <= 2_000_000 and (
                audio.startswith(b"ID3") or (audio[0] == 0xFF and audio[1] & 0xE0 == 0xE0))
            if not valid:
                raise SpeechError("INVALID_AUDIO", 502)
            return Response(audio, media_type="audio/mpeg", headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"})
        except FutureTimeout:
            pending.cancel()
            raise SpeechError("TIMEOUT", 504) from None
        except SpeechError:
            raise
        except Exception:
            raise SpeechError("UPSTREAM_FAILED", 502) from None

    return app


def application():
    return create_app(Settings.environment())
