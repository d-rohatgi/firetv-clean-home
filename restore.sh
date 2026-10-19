#!/bin/zsh
# Restores packages removed/disabled by debloat.sh.
# Usage: ./restore.sh            -> restore everything
#        ./restore.sh <pkg>...   -> restore only the given packages
# A factory reset (Settings > Device & Software > Reset to Factory Defaults) also restores everything.
export ANDROID_SERIAL=${ANDROID_SERIAL:-192.168.0.2:5555}
adb connect $ANDROID_SERIAL >/dev/null

restore() {  # restore <pkg> <uninstalled|disabled>
  (( $#WANT )) && (( ! ${WANT[(Ie)$1]} )) && return
  if [[ $2 == uninstalled ]]; then print -n "$1: "; adb shell cmd package install-existing $1
  else print -n "$1: "; adb shell pm enable $1; fi
}
WANT=("$@")

restore com.amazon.tv.acr disabled
restore com.amazon.kso.blackbird disabled
restore com.amazon.hybridadidservice disabled
restore com.amazon.sneakpeek disabled
restore com.amazon.shoptv.client disabled
restore com.amazon.shoptv.firetv.client disabled
restore com.amazon.device.sale.service disabled
restore com.amazon.tv.democontent.provider disabled
restore com.amazon.tv.easyupgrade disabled
restore com.amazon.tv.fw.metrics disabled
restore com.amazon.wirelessmetrics.service disabled
restore com.amazon.imdb.tv.android.app disabled
restore com.amazon.minitv.android.app disabled
restore com.amazon.bueller.photos disabled
restore com.amazon.bueller.music disabled
restore com.amazon.gamehub disabled
restore com.amazon.storm.lightning.tutorial disabled
restore com.amazon.tmm.tutorial disabled
restore com.amazon.tv.releasenotes disabled
restore com.amazon.perfc disabled
restore com.amazon.perfcollection disabled
restore com.amazon.dp.logger disabled
restore com.amazon.tahoe disabled
restore com.amazon.vizzini disabled
restore com.amazon.vizzini.ftvcds disabled
