#!/system/bin/sh
# Runs as root. Writes texts into the system message database.
LOG=/data/adb/cabin/smsd.log
QUEUE_PARENT=/data/data/com.cabin.bridge/files

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"
}

insert_sms() {
  addr=$(printf '%s' "$1" | tr '\n\r"' '   ')
  body=$(printf '%s' "$2" | tr '\n\r"' '   ')
  date=$3
  type=$4
  if [ "$type" = "2" ]; then
    uri="content://sms/sent"
  else
    uri="content://sms/inbox"
  fi
  out=$(content insert --user 0 --uri "$uri" \
    --bind "address:s:${addr}" \
    --bind "body:s:${body}" \
    --bind "date:l:${date}" \
    --bind "date_sent:l:${date}" \
    --bind "read:i:0" \
    --bind "seen:i:0" \
    --bind "type:i:${type}" \
    --bind "status:i:-1" \
    --bind "sub_id:i:-1" 2>&1)
  case "$out" in
    *Error*|*Exception*|*Usage*|*Security*)
      out=$(content insert --user 0 --uri "$uri" \
        --bind "address:s:${addr}" \
        --bind "body:s:${body}" \
        --bind "date:l:${date}" \
        --bind "date_sent:l:${date}" \
        --bind "read:i:0" \
        --bind "seen:i:0" \
        --bind "type:i:${type}" \
        --bind "status:i:-1" 2>&1)
      ;;
  esac
  log "insert type=$type -> $out"
  printf '%s\n' "$out"
}

update_sms() {
  id=$1
  type=$2
  out=$(content update --user 0 --uri "content://sms/${id}" \
    --bind "type:i:${type}" \
    --bind "status:i:0" \
    --bind "read:i:1" 2>&1)
  log "update id=$id -> $out"
  printf '%s\n' "$out"
}

log "helper start"
while true; do
  Q="$QUEUE_PARENT/cabin-queue"
  if [ -d "$Q" ]; then
    for f in "$Q"/*.req; do
      [ -e "$f" ] || continue
      op=""
      ab=""
      bb=""
      date=""
      type=""
      id=""
      # Requests contain only op, base64, and digits.
      . "$f"
      if [ "$op" = "insert" ]; then
        addr=$(printf '%s' "$ab" | base64 -d 2>/dev/null)
        body=$(printf '%s' "$bb" | base64 -d 2>/dev/null)
        [ -n "$date" ] || date="$(date +%s)000"
        [ -n "$type" ] || type=1
        insert_sms "$addr" "$body" "$date" "$type" > "${f}.out"
      elif [ "$op" = "update" ]; then
        update_sms "$id" "$type" > "${f}.out"
      else
        log "ignored $f"
      fi
      mv "$f" "${f}.done" 2>/dev/null || rm -f "$f"
    done
  fi
  sleep 1
done
