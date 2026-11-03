#!/system/bin/sh
# usage: focus.sh  -> prints focused node bounds and text of Install/Open buttons
uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
tr '>' '\n' < /sdcard/ui.xml | grep 'focused="true"' | grep -o 'bounds="[^"]*"' | head -1
