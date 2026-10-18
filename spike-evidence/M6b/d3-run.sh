# usage: d3-run.sh <state> <kill: terminate|kill9>   (source drill-helpers.sh first)
S=$1; K=$2; T=d3-$S-$K; rm -f $EV/logs/$T-a.log $EV/logs/$T-b.log
launch $EV/logs/$T-a.log --ux-spike slow | tee /tmp/d3pid; PID=$(awk -F': ' '{print $2}' /tmp/d3pid); sleep 25; mflow m4-unlock-to-home.yaml >/dev/null
case $S in
 request) PAT="enter request (gen 2)";;
 auth) PAT="authenticate#1";;
 sending) PAT="send#1";;
esac
# watcher: the moment the state's marker is in the app log, take a screenshot and kill (terminate or kill -9)
( until grep -qF "$PAT" $EV/logs/$T-a.log; do sleep 0.05; done
  [ $S = request ] && sleep 1
  xcrun simctl io $U screenshot $EV/shots/$T-1-before.png >/dev/null 2>&1
  if [ $K = kill9 ]; then kill -9 $PID; echo "kill -9 $PID at $(date +%T)" > $EV/logs/$T-kill.txt; else xcrun simctl terminate $U $B; echo "simctl terminate at $(date +%T)" > $EV/logs/$T-kill.txt; fi ) &
W=$!
case $S in
 request) mflow m5b-open-wait.yaml -e STATE=request >/dev/null;;
 auth) mflow m5b-open-wait.yaml -e STATE=request >/dev/null; mflow m5b-tap.yaml -e NODE=continue -e STATE=auth >/dev/null;;
 sending) mflow m5b-open-wait.yaml -e STATE=request >/dev/null; mflow m5b-tap.yaml -e NODE=continue -e STATE=verification-code >/dev/null; mflow m5b-tap.yaml -e NODE=confirm -e STATE=sending >/dev/null;;
esac
wait $W; cat $EV/logs/$T-kill.txt; sleep 2
echo "pid $PID alive? $(kill -0 $PID 2>&1 | tr -d '\n')"
launch $EV/logs/$T-b.log --ux-spike slow >/dev/null; sleep 25
mflow m4-unlock-to-home.yaml | tail -2; shot $T-2-home
mflow m5b-open-wait.yaml -e STATE=request | tail -1; shot $T-3-new-link-request
