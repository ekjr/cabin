#!/system/bin/sh
# Root helper for the Cabin app. The app is not root, so it drops small request
# files into its own data dir and this loop carries them out:
#   op=insert  write a received text into the system SMS database
#   op=update  mark a forwarded reply as sent
#   op=set     change a Cabin setting (mode on/off/hfp/sap, call audio, ...)
#   op=bt      value=restart: restart Bluetooth (clears a stuck call-audio link)
MODDIR=${0%/*}
LOG=/data/adb/cabin/cabind.log
Q=/data/data/com.cabin.bridge/files/cabin-queue

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
  set -- --bind "address:s:${addr}" --bind "body:s:${body}" \
    --bind "date:l:${date}" --bind "date_sent:l:${date}" \
    --bind "read:i:0" --bind "seen:i:0" --bind "type:i:${type}" --bind "status:i:-1"
  out=$(content insert --user 0 --uri "$uri" "$@" --bind "sub_id:i:-1" 2>&1)
  case "$out" in
    *Error*|*Exception*|*Usage*|*Security*)
      # Older providers have no sub_id column.
      out=$(content insert --user 0 --uri "$uri" "$@" 2>&1)
      ;;
  esac
  log "insert type=$type -> ${out:-ok}"
}

update_sms() {
  out=$(content update --user 0 --uri "content://sms/$1" \
    --bind "type:i:$2" --bind "status:i:0" --bind "read:i:1" 2>&1)
  log "update id=$1 -> ${out:-ok}"
}

# Runs the cabin script with its output piped into the log. Handing the log file itself to
# the script would pass it on to the Android "cmd" tools it calls, which then fail.
run_cabin() {
  sh "$MODDIR/cabin.sh" "$@" </dev/null 2>&1 | while IFS= read -r line; do log "$line"; done
}

handle() {
  op=""; ab=""; bb=""; date=""; type=""; id=""; key=""; value=""
  # Parse key=value lines; never execute the file.
  while IFS='=' read -r k v; do
    case "$k" in
      op) op=$v ;;
      ab) ab=$v ;;
      bb) bb=$v ;;
      date) date=$v ;;
      type) type=$v ;;
      id) id=$v ;;
      key) key=$v ;;
      value) value=$v ;;
    esac
  done < "$1"
  case "$date$type$id" in *[!0-9]*) log "bad numbers in $1"; return ;; esac
  case "$op" in
    insert)
      addr=$(printf '%s' "$ab" | base64 -d 2>/dev/null)
      body=$(printf '%s' "$bb" | base64 -d 2>/dev/null)
      [ -n "$date" ] || date="$(date +%s)000"
      [ -n "$type" ] || type=1
      insert_sms "$addr" "$body" "$date" "$type"
      ;;
    update)
      [ -n "$id" ] && update_sms "$id" "${type:-2}"
      ;;
    set)
      log "app asks $key=$value"
      run_cabin from-app "$key" "$value"
      ;;
    relay)
      case "$value" in start|stop|up|down|snap|reapply|diag) run_cabin "relay-$value" ;; esac
      ;;
    bt)
      [ "$value" = restart ] && { log "app asks to reset Bluetooth"; run_cabin restart-bt; }
      ;;
    *)
      log "ignored $1"
      ;;
  esac
}

process_queue() {
  [ -d "$Q" ] || return 0
  # Settings changes are applied once, last request wins: tapping on/off quickly
  # shouldn't restart Bluetooth for every tap. Text inserts all run, in order.
  pending=
  for f in "$Q"/*.req; do
    [ -e "$f" ] || continue
    if grep -q '^op=set$' "$f"; then
      k=$(sed -n 's/^key=//p' "$f")
      v=$(sed -n 's/^value=//p' "$f")
      pending=$(printf '%s\n' "$pending" | grep -v "^$k=")
      pending=$(printf '%s\n%s=%s' "$pending" "$k" "$v")
    else
      handle "$f"
    fi
    rm -f "$f"
  done
  printf '%s\n' "$pending" | while IFS='=' read -r k v; do
    [ -n "$k" ] || continue
    log "app asks $k=$v"
    run_cabin from-app "$k" "$v"
  done
}

log "helper start"
while true; do
  process_queue
  if [ -d "$Q" ] && command -v inotifyd >/dev/null 2>&1; then
    # React the moment the app drops a request (it writes a .tmp, then renames it to .req).
    # Moved-in is "m" in toybox's inotifyd and "y" in busybox's; watch both, plus created.
    inotifyd - "$Q:mny" 2>/dev/null | while read -r _ _ _; do
      process_queue
    done
  fi
  # inotifyd missing, or the queue dir isn't there yet / was removed: poll.
  sleep 1
done
