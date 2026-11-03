#!/system/bin/sh
for i in 1 2 3 4 5 6 7 8; do
  input keyevent DPAD_DOWN; sleep 1
  echo "step $i: $(sh /data/local/tmp/focus.sh)"
done
