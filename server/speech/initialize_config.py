"""Initialize runtime secrets once, never print them or add provider credentials."""
import os
import secrets
from pathlib import Path

destination = Path("/etc/haohao-speech/service.env")
client = Path("/etc/haohao-speech/internal-client.properties")
if destination.exists() or client.exists():
    raise SystemExit("Configuration already exists; refusing to replace credentials")
token = secrets.token_urlsafe(48)
for path, body in (
    (destination, "SPEECH_CLIENT_TOKEN=" + token + "\nTENCENT_SECRET_ID=\nTENCENT_SECRET_KEY=\nSPEECH_FREE_ONLY_VERIFIED=false\nSPEECH_FREE_EXPIRES_AT=0\nSPEECH_FREE_CHARACTER_ALLOWANCE=0\n"),
    (client, "SPEECH_ENDPOINT=https://124.221.187.214/api/v1/speech\nSPEECH_CLIENT_TOKEN=" + token + "\n"),
):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as output:
        output.write(body)
print("Restricted gateway configuration initialized; cloud calls disabled.")
