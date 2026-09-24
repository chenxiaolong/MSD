# SPDX-FileCopyrightText: 2024-2025 Andrew Gunnerson
# SPDX-License-Identifier: GPL-3.0-only

source "${0%/*}/boot_common.sh" /data/local/tmp/msd/service.log

header Starting daemon

# The root supervisor authenticates clients; its child runs in msd_daemon.
CLASSPATH="${cli_apk}" /system/bin/app_process / \
    com.chiller3.msd.standalone.AuthenticatedDaemon \
    "${mod_dir}/msd-tool.$(getprop ro.product.cpu.abi)" \
    "${mod_dir}/client-cert.sha256" &

sleep 1

ls -lZR /config/usb_gadget > "${log_dir}"/configfs.old.log

# Do a query to force the configfs chown immediately.
"${mod_dir}"/msd-tool."$(getprop ro.product.cpu.abi)" \
    client get-functions

ls -lZR /config/usb_gadget > "${log_dir}"/configfs.new.log

ps -efZ > "${log_dir}"/ps.log

/system/bin/dmesg > "${log_dir}"/dmesg.log

cp /proc/self/mountinfo "${log_dir}"/mountinfo.log

cp /proc/filesystems "${log_dir}"/filesystems.log

getprop > "${log_dir}"/properties.log

/system/bin/dmesg -w | grep avc: > "${log_dir}"/audit.log &

logcat -s msd-tool > "${log_dir}"/msd-tool.log &

wait
