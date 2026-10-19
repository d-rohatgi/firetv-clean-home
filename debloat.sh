#!/bin/zsh
# Removes approved packages (groups A+B) and logs results to removed-packages.txt
export ANDROID_SERIAL=${ANDROID_SERIAL:-192.168.0.2:5555}
LOG=removed-packages.txt
[ -f $LOG ] || print "# package\tmethod\tdate" > $LOG

# uninstall --user 0, falling back to disable-user
UNINSTALL=(
  com.amazon.tv.acr com.amazon.kso.blackbird com.amazon.ftvads.deeplinking com.amazon.hybridadidservice
  com.amazon.sneakpeek com.amazon.shoptv.client com.amazon.shoptv.firetv.client
  com.amazon.device.sale.service com.amazon.tv.democontent.provider com.amazon.tv.easyupgrade
  com.amazon.client.metrics com.amazon.device.metrics com.amazon.tv.fw.metrics com.amazon.wirelessmetrics.service
  com.amazon.imdb.tv.android.app com.amazon.minitv.android.app com.amazon.bueller.photos com.amazon.bueller.music
  com.amazon.gamehub com.amazon.storm.lightning.tutorial com.amazon.tmm.tutorial com.amazon.tv.releasenotes
  com.amazon.tv.csapp
)
# disable-user only
DISABLE=(
  com.amazon.perfc com.amazon.perfcollection com.amazon.dp.logger com.amazon.device.logmanager
  com.amazon.device.crashmanager com.fireos.usagestats.proxy
  com.amazon.tahoe com.amazon.vizzini com.amazon.vizzini.ftvcds
)

log() { print "$1\t$2\t$(date '+%Y-%m-%d %H:%M')" >> $LOG; }

for p in $UNINSTALL; do
  out=$(adb shell pm uninstall --user 0 $p 2>&1)
  if [[ $out == *Success* ]]; then print "UNINSTALLED  $p"; log $p uninstalled; continue; fi
  out2=$(adb shell pm disable-user --user 0 $p 2>&1)
  if [[ $out2 == *"new state: disabled"* ]]; then print "DISABLED     $p  (uninstall refused: $out)"; log $p disabled
  else print "FAILED       $p  ($out | $out2)"; fi
done
for p in $DISABLE; do
  out=$(adb shell pm disable-user --user 0 $p 2>&1)
  if [[ $out == *"new state: disabled"* ]]; then print "DISABLED     $p"; log $p disabled
  else print "FAILED       $p  ($out)"; fi
done
