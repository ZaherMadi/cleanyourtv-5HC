#!/system/bin/sh
# usage: pagewait.sh pkg [seconds]: open Play page, wait until pkg is installed
pkg=$1; max=${2:-150}; t=0
am start -a android.intent.action.VIEW -d market://details?id=$pkg >/dev/null 2>&1
while [ $t -lt $max ]; do
  if pm list packages | grep -q "package:$pkg\$"; then echo "INSTALLED $pkg after ${t}s"; exit 0; fi
  sleep 3; t=$((t+3))
done
echo "TIMEOUT $pkg"
