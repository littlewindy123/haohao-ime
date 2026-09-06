"""Fixed-text smoke test of a DISABLED gateway; never print the private client token."""
import json
import ssl
import sys
import urllib.error
import urllib.request
from pathlib import Path

config = dict(line.split("=", 1) for line in Path(sys.argv[1]).read_text().splitlines() if "=" in line)
endpoint = config["SPEECH_ENDPOINT"]
assert endpoint == "https://124.221.187.214/api/v1/speech"
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=ssl.create_default_context()))
for token, expected in (("invalid-test-token", "UNAUTHORIZED"), (config["SPEECH_CLIENT_TOKEN"], "NOT_CONFIGURED")):
    request = urllib.request.Request(endpoint, data=b'{"text":"Hello, world!","rate":"normal"}',
                                    headers={"Content-Type": "application/json", "Authorization": "Bearer " + token})
    try:
        with opener.open(request, timeout=10) as response:
            raise SystemExit("Unexpected enabled gateway: stop this disabled-state check")
    except urllib.error.HTTPError as error:
        body = json.loads(error.read(4096))
        assert body == {"code": expected}
        print(f"Verified TLS; gateway returned {error.code} {expected}")
