#!/bin/bash
set -euo pipefail
test "$(id -u)" = 0
test -s /etc/letsencrypt/live/haohao-ip/fullchain.pem
target=/www/server/panel/vhost/nginx/haohao-speech-https.conf
test ! -e "$target" # No unreviewed replacement of an existing TLS vhost.
install -m 0644 haohao-speech-https.conf "$target"
if ! /www/server/nginx/sbin/nginx -t; then
    mv "$target" /var/backups/haohao-speech/rejected-https.conf
    exit 1
fi
/www/server/nginx/sbin/nginx -s reload
install -m 0755 haohao-reload-tls /usr/local/sbin/haohao-reload-tls
install -m 0644 haohao-certbot-renew.service haohao-certbot-renew.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now haohao-certbot-renew.timer
/opt/haohao-certbot/bin/certbot renew --dry-run --cert-name haohao-ip --run-deploy-hooks --deploy-hook /usr/local/sbin/haohao-reload-tls
systemctl list-timers haohao-certbot-renew.timer --no-pager
