#!/bin/sh
# Configure the OpenWhispr keyboard over adb: cleanup model and key, on-device speech.
#   OPENROUTER_API_KEY=sk-or-... ./configure.sh <adb-serial>
set -eu
SERIAL="${1:?usage: OPENROUTER_API_KEY=... $0 <adb-serial>}"
: "${OPENROUTER_API_KEY:?set OPENROUTER_API_KEY}"
FILES=/sdcard/Android/data/com.voicerewriter/files
RECEIVER=com.voicerewriter/.BridgeConfigReceiver
adb_() { adb -s "$SERIAL" "$@"; }
tmp=$(mktemp); chmod 600 "$tmp"
trap 'rm -f "$tmp"' EXIT
printf '{"cleanup_provider":"openrouter","cleanup_model":"anthropic/claude-haiku-5.5","cleanup_api_key":"%s","speech_provider":"local","speech_model":"parakeet","deterministic_cleanup":false}' "$OPENROUTER_API_KEY" > "$tmp"
adb_ shell mkdir -p "$FILES"
adb_ push "$tmp" "$FILES/bridge_config.json" >/dev/null
adb_ shell am broadcast -f 0x20 -a com.voicerewriter.IMPORT_BRIDGE_CONFIG -n "$RECEIVER" >/dev/null
adb_ shell am broadcast -f 0x20 -a com.voicerewriter.PROVISION_PARAKEET -n "$RECEIVER" >/dev/null
echo "Configured. The Parakeet model downloads in the background (about 630 MB)."
