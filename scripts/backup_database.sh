#!/bin/sh
# Encrypt directly from pg_dump: no plaintext dump is written to disk.
set -eu
umask 077
if [ "$#" -ne 2 ]; then
  echo "Usage: sh scripts/backup_database.sh RECIPIENT_FINGERPRINT OUTPUT.gpg" >&2
  exit 2
fi
case "$1" in *[!A-Fa-f0-9]*|'') echo "Use a full trusted GPG fingerprint" >&2; exit 2;; esac
[ "${#1}" -ge 40 ] || { echo "Use a full trusted GPG fingerprint" >&2; exit 2; }
[ ! -e "$2" ] || { echo "Output already exists; refusing overwrite" >&2; exit 2; }
command -v gpg >/dev/null
# Resolve and check the encryption key before accessing database contents.
gpg --batch --list-keys "$1" >/dev/null 2>&1
backup_tmp=$(mktemp -d)
trap 'rm -f "$backup_tmp/encrypted" "$backup_tmp/dump.fifo"; rmdir "$backup_tmp"' EXIT HUP INT TERM
mkfifo "$backup_tmp/dump.fifo"
gpg --batch --encrypt --recipient "$1" --output "$backup_tmp/encrypted" < "$backup_tmp/dump.fifo" &
encrypt_pid=$!
if ! docker compose -f compose.production.yaml exec -T db sh -c 'exec pg_dump --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --format=custom --no-owner --no-acl' > "$backup_tmp/dump.fifo"; then
  wait "$encrypt_pid" || true
  echo "Database backup failed; no backup published" >&2
  exit 1
fi
wait "$encrypt_pid"
# Hard link is atomic and fails if another process created the destination.
# Copy encrypted bytes into the destination filesystem before publishing.
output_dir=$(dirname "$2")
output_tmp=$(mktemp "$output_dir/.nilebank-backup.XXXXXX")
if ! cat "$backup_tmp/encrypted" > "$output_tmp"; then rm -f "$output_tmp"; exit 1; fi
if ! ln "$output_tmp" "$2"; then rm -f "$output_tmp"; exit 1; fi
rm -f "$output_tmp"
echo "Encrypted backup created. Verify decryption and restore in an isolated database."
