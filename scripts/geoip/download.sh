#!/bin/sh
# Fetches DB-IP's "IP to City Lite" database, which auth-service reads to turn a sign-in's address
# into an approximate place (GeoIpLocator). Licensed CC BY 4.0: every page that shows a place
# credits "IP Geolocation by DB-IP".
#
# Usage: download.sh <dir>   — writes <dir>/dbip-city-lite.mmdb
#
# Dev runs it through `make geoip`; production through the geoip-init container
# (docker-compose.prod.yml) on every deploy. Both paths depend on two promises:
#   - it never leaves a half-written file where auth-service reads: it downloads beside the
#     target and renames over it only once the whole file is there;
#   - it never fails: a failed download prints a warning and exits 0. No file means no places,
#     which auth-service survives, while a failed init container would stop the deploy.
set -u

dir=${1:?usage: download.sh <dir>}
target="$dir/dbip-city-lite.mmdb"
tmp="$target.tmp"
mkdir -p "$dir"

# DB-IP publishes monthly, so a copy younger than a month is already the newest there is.
if [ -f "$target" ] && [ -n "$(find "$target" -mtime -30)" ]; then
    echo "GeoIP: $target is less than a month old, kept"
    exit 0
fi

year=$(date -u +%Y)
month=$(date -u +%m)
# "08" is not a number to sh: strip the leading zero before doing arithmetic on it.
previous=$(( ${month#0} - 1 ))
previous_year=$year
if [ "$previous" -eq 0 ]; then
    previous=12
    previous_year=$(( year - 1 ))
fi

# The current month's file appears a day or two into the month; until then, last month's is
# the newest.
for ym in "$year-$month" "$(printf '%d-%02d' "$previous_year" "$previous")"; do
    if curl -fsSL --max-time 600 "https://download.db-ip.com/free/dbip-city-lite-$ym.mmdb.gz" \
            | gunzip > "$tmp" && [ -s "$tmp" ]; then
        mv "$tmp" "$target"
        echo "GeoIP: downloaded dbip-city-lite-$ym"
        exit 0
    fi
    rm -f "$tmp"
done

echo "WARNING: GeoIP download failed; sign-ins get no place until a later run succeeds" >&2
exit 0
