#!/bin/sh
# Alertmanager reads no environment variables: the recipient and sender are written into its config,
# and the SMTP key into a file only this process can read, rather than into the config itself.
set -eu
umask 077
printf '%s' "$SMTP_PASSWORD" > /tmp/smtp-password
sed -e "s|__ALERT_EMAIL__|$ALERT_EMAIL|" -e "s|__MAIL_FROM__|$MAIL_FROM|" \
    /etc/alertmanager/alertmanager.yml > /tmp/alertmanager.yml
exec /bin/alertmanager --config.file=/tmp/alertmanager.yml --storage.path=/alertmanager
