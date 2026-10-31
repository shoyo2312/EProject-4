#!/bin/sh
# Checks that download.sh cannot hold a deploy for long when db-ip.com is slow or stalls.
# auth-service waits for geoip-init to finish, so every minute curl waits is a minute nobody can
# sign in or refresh a token. Each attempt must bound the connect, a stalled transfer and the
# whole transfer.
#
# Run: sh scripts/geoip/download_test.sh
set -eu

here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/bin" "$work/data"

# A curl that records how it was called and fails as a timed-out transfer would.
cat > "$work/bin/curl" <<'EOF'
#!/bin/sh
echo "$*" >> "$CURL_CALLS"
exit 28
EOF
chmod +x "$work/bin/curl"

CURL_CALLS="$work/calls" PATH="$work/bin:$PATH" sh "$here/download.sh" "$work/data" 2>/dev/null

[ -s "$work/calls" ] || { echo "FAIL: download.sh never called curl"; exit 1; }
while read -r call; do
    for flag in --connect-timeout --speed-limit --speed-time --max-time; do
        case " $call " in
            *" $flag "*) ;;
            *) echo "FAIL: curl called without $flag: $call"; exit 1 ;;
        esac
    done
    max=$(echo "$call" | sed -n 's/.*--max-time \([0-9]*\).*/\1/p')
    [ "$max" -le 120 ] || { echo "FAIL: --max-time $max lets one attempt hold a deploy for minutes"; exit 1; }
done < "$work/calls"

echo "PASS: every download attempt is bounded ($(wc -l < "$work/calls" | tr -d ' ') attempts)"
