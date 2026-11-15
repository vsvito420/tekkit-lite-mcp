#!/bin/bash
# Talks to the Claude Bridge mod running inside Minecraft 1.4.7 (Lycodon client).
#
#   mc state                 player position, look, inventory, open container, all macro-mod variables
#   mc run '<script>'        run a Macro-Mod script, e.g.  mc run 'look(90,0); keydown(forward); wait(20t); keyup(forward)'
#   mc stop                  abort all scripts / walking, release all keys
#   mc close                 safely close only an actually open game window (never presses Escape)
#   mc say '<text>'          send a chat message or /command
#   mc goto <x> <z> [tol]    walk straight to x/z (auto-jumps one-block steps, no pathfinding)
#   mc blocks [r] [ry]       block ids around the player (default 4 wide, 2 up/down)
#   mc chat [since]          recent chat lines (pass the last "seq" to get only new ones)
#   mc shot [file] [width]   save a screenshot of the game (default ./shot.jpg, 960px wide)
#
# World (reads the client's loaded chunks directly, acts without aiming the camera; reach about 4.5 blocks):
#   mc area x1 y1 z1 x2 y2 z2 [list]   blocks in a box (layers, or with "list" only non-air "x y z id:meta")
#   mc scan [r] [down] [up]  box around the player, default r=8 (one chunk wide), 8 down, 8 up, as a non-air list
#   mc place x y z [id[:meta]]          place a block (item taken from the hotbar; default the held item)
#   mc build <file|-> [id[:meta]]       place many: one "x y z [id[:meta]]" per line, bottom-up, retries
#                                       blocks that have nothing to stick to yet; mc wait, then mc state shows
#                                       placed / skipped (already filled) / failures
#   mc dig x y z             break a block (holds attack until it is gone)
#   mc use x y z [side]      right-click a block (open chest, press button, ...)
#   mc select <slot0-8>|id=<id[:meta]>  choose the hotbar slot
#   mc look x y z | mc look yaw=<y> pitch=<p>   (yaw: 0=north 90=east)
#   mc entities [r]          players and mobs nearby
#
#   mc wait                  block until scripts, walking and computer typing have finished
#
# ComputerCraft - acts on the computer/turtle screen currently open in the game (open one with: look at it, mc run 'key(use)'):
#   mc cc                    show the computer's screen as text (plus id, on/off, cursor)
#   mc cc type '<text>'      type text and press Enter after every line
#   mc cc write '<text>'     type text without the final Enter
#   mc cc key <name> [n]     press a key n times: enter up down left right tab backspace delete home end ctrl pageup pagedown (or a keycode)
#   mc cc terminate          stop the running program (what holding Ctrl+T does)
#   mc cc reboot|shutdown|on restart (Ctrl+R) / power off (Ctrl+S) / power on
#   mc cc install            one-time per computer: type the transfer helper `cb` into it (needs the shell prompt)
#   mc cc get <name> [file]  download a file from the computer, verified by checksum (stdout or local file)
#   mc cc put <file> <name>  upload a local file to the computer as <name>, verified by checksum (replaces it)
#
# look(yaw,pitch) in scripts counts 0=north 90=east 180=south 270=west; the "yaw" in state is Minecraft's
# own (0=south 90=west 180=north 270=east), i.e. script yaw = state yaw + 180.
# gui() closes the open screen, slot(1-9) picks a hotbar slot, slotclick(n,l|r,shift) clicks a container slot.
# Script keys for keydown/keyup/key: forward back left right jump sneak attack use inventory drop chat pick
INSTANCE="${MC_INSTANCE:-$HOME/Library/Application Support/PrismLauncher/instances/Lycodon-Client(1)/minecraft}"
URL="http://127.0.0.1:25599"
TOKEN="$(cat "$INSTANCE/claudebridge.token" 2>/dev/null)"
if [ -z "$TOKEN" ]; then echo "no token file - is the game running with the bridge mod?" >&2; exit 1; fi
req() { curl -sS --max-time 10 -H "X-Token: $TOKEN" "$@"; }

cmd="$1"; shift
case "$cmd" in
  state)  req "$URL/state" | sed -E 's/"KEY_[A-Z0-9_]*":(true|false),?//g'; echo ;;
  run)    req --data-binary "$1" "$URL/run"; echo ;;
  stop)   req -X POST "$URL/stop"; echo ;;
  close)  req -X POST "$URL/close"; echo ;;
  say)    req --data-binary "$1" "$URL/say"; echo ;;
  goto)   req -X POST "$URL/goto?x=$1&z=$2&tol=${3:-0.4}"; echo ;;
  blocks) req "$URL/blocks?r=${1:-4}&ry=${2:-2}"; echo ;;
  area)   req "$URL/area?x1=$1&y1=$2&z1=$3&x2=$4&y2=$5&z2=$6&list=$([ "$7" = list ] && echo 1)"; echo ;;
  scan)   p=$(req "$URL/state" | python3 -c 'import sys,json,math;p=json.load(sys.stdin)["pos"];print(*[math.floor(v) for v in p])'); set -- ${1:-8} ${2:-8} ${3:-8} $p
          req "$URL/area?x1=$(($4-$1))&y1=$(($5-$2))&z1=$(($6-$1))&x2=$(($4+$1))&y2=$(($5+$3))&z2=$(($6+$1))&list=1"; echo ;;
  place)  idq=""; [ -n "$4" ] && idq="&id=${4%%:*}" && [ "$4" != "${4#*:}" ] && idq="$idq&meta=${4#*:}"
          req -X POST "$URL/place?x=$1&y=$2&z=$3$idq"; echo ;;
  build)  idq=""; [ -n "$2" ] && idq="?id=${2%%:*}" && [ "$2" != "${2#*:}" ] && idq="$idq&meta=${2#*:}"
          if [ "$1" = - ]; then req --data-binary @- "$URL/place$idq"; else req --data-binary @"$1" "$URL/place$idq"; fi; echo ;;
  dig)    req -X POST "$URL/dig?x=$1&y=$2&z=$3"; echo ;;
  use)    req -X POST "$URL/use?x=$1&y=$2&z=$3${4:+&side=$4}"; echo ;;
  select) case "$1" in id=*) v="${1#id=}"; q="id=${v%%:*}"; [ "$v" != "${v#*:}" ] && q="$q&meta=${v#*:}";; *) q="slot=$1";; esac
          req -X POST "$URL/select?$q"; echo ;;
  look)   case "$1" in yaw=*) req -X POST "$URL/look?yaw=${1#yaw=}&pitch=${2#pitch=}";; *) req -X POST "$URL/look?x=$1&y=$2&z=$3";; esac; echo ;;
  entities) req "$URL/entities?r=${1:-16}"; echo ;;
  chat)   req "$URL/chat?since=${1:-0}"; echo ;;
  shot)   out="${1:-shot.jpg}"; req -o "$out" "$URL/screenshot?w=${2:-960}" && echo "$out" ;;
  cc)
    sub="$1"; shift
    keycode() { case "$1" in enter) echo 28;; up) echo 200;; down) echo 208;; left) echo 203;; right) echo 205;; tab) echo 15;;
      backspace) echo 14;; delete) echo 211;; home) echo 199;; end) echo 207;; ctrl) echo 29;; pageup) echo 201;; pagedown) echo 209;; *) echo "$1";; esac; }
    case "$sub" in
      "")    req "$URL/cc" | python3 -c 'import sys,json; d=json.load(sys.stdin); L=d.pop("lines",[]); print(json.dumps(d)); print("\n".join("|"+l for l in L))' ;;
      type)  req --data-binary "$1" "$URL/cc/type"; echo ;;
      write) req --data-binary "$1" "$URL/cc/type?enter=0"; echo ;;
      key)   req -X POST "$URL/cc/key?code=$(keycode "$1")&times=${2:-1}"; echo ;;
      terminate|reboot|shutdown|on) req -X POST "$URL/cc/$sub"; echo ;;
      install) python3 "$(dirname "$0")/cc_transfer.py" install ;;
      get)     python3 "$(dirname "$0")/cc_transfer.py" get "$@" ;;
      put)     python3 "$(dirname "$0")/cc_transfer.py" put "$@" ;;
      *) echo "unknown cc command" >&2; exit 1 ;;
    esac ;;
  wait)
    for _ in $(seq 1 600); do
      s="$(req "$URL/state")" || exit 1
      case "$s" in *'"macrosRunning":0'*) case "$s" in *'"walk":"walking"'*|*'"ccPending":'[1-9]*|*'"actPending":'[1-9]*) ;; *) echo "$s" | grep -o '"walk":"[^"]*"'; exit 0 ;; esac ;; esac
      sleep 0.25
    done
    echo "still busy" >&2; exit 1 ;;
  *) sed -n '2,42p' "$0" ;;
esac
