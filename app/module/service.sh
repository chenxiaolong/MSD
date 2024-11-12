# SPDX-FileCopyrightText: 2024 Andrew Gunnerson
# SPDX-License-Identifier: GPL-3.0-only

source "${0%/*}/boot_common.sh" /data/local/tmp/msd/service.log

header Starting daemon

# Full path because Magisk runs this script in busybox's standalone ash mode and
# we need Android's toybox version of runcon.
/system/bin/runcon u:r:msd_daemon:s0 \
    "${mod_dir}"/msd-tool."$(getprop ro.product.cpu.abi)" \
        daemon --log-target logcat --log-level debug &

sleep 1

ps -efZ > "${log_dir}"/ps.log

dmesg > "${log_dir}"/dmesg.log

ls -lZR /config/usb_gadget > "${log_dir}"/configfs.log

cp /proc/self/mountinfo "${log_dir}"/mountinfo.log

logcat -s msd-tool > "${log_dir}"/msd-tool.log
