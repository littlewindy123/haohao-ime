#!/bin/bash
# Run as root from a staging directory containing these reviewed deployment files.
set -euo pipefail
umask 022
test "$(id -u)" = 0
test -f app.py && test -f requirements.txt
nginx=/www/server/nginx/sbin/nginx
vhost=/www/server/panel/vhost/nginx/haohao-ime.conf
test -f "$vhost"
"$nginx" -t
sha256sum /www/wwwroot/haohao-ime/current/index.html
install -d -m 0755 /opt/haohao-speech/current /var/lib/haohao-acme/.well-known/acme-challenge
install -d -m 0700 /etc/haohao-speech /var/backups/haohao-speech
if ! id haohao-speech >/dev/null 2>&1; then
    useradd --system --home-dir /var/lib/haohao-speech --shell /sbin/nologin haohao-speech
fi
install -d -o haohao-speech -g haohao-speech -m 0700 /var/lib/haohao-speech
if ! test -x /opt/haohao-speech/venv/bin/python; then
    /usr/bin/python3 -m venv /opt/haohao-speech/venv
fi
/opt/haohao-speech/venv/bin/python -m pip install --disable-pip-version-check -r requirements.txt
install -m 0644 app.py /opt/haohao-speech/current/app.py
install -m 0644 haohao-speech.service /etc/systemd/system/haohao-speech.service
if ! test -f /etc/haohao-speech/service.env; then
    /opt/haohao-speech/venv/bin/python initialize_config.py
fi
systemctl daemon-reload
systemctl enable --now haohao-speech.service
curl --fail --silent --retry 5 --retry-connrefused --retry-delay 1 http://127.0.0.1:18761/health
if ! grep -q 'root /var/lib/haohao-acme;' "$vhost"; then
    backup="/var/backups/haohao-speech/haohao-ime.$(date +%Y%m%d%H%M%S).conf"
    cp -a "$vhost" "$backup"
    sed -i '/server_name 124.221.187.214;/a\    location ^~ /.well-known/acme-challenge/ { root /var/lib/haohao-acme; default_type text/plain; try_files $uri =404; }' "$vhost"
    if ! "$nginx" -t; then
        cp -a "$backup" "$vhost"
        exit 1
    fi
    "$nginx" -s reload
fi
if ! test -x /opt/haohao-certbot/bin/certbot; then
    /usr/bin/python3 -m venv /opt/haohao-certbot
fi
/opt/haohao-certbot/bin/python -m pip install --disable-pip-version-check 'certbot>=5.4,<6'
/opt/haohao-certbot/bin/certbot --version
sha256sum /www/wwwroot/haohao-ime/current/index.html
