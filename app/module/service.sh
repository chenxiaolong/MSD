# SPDX-FileCopyrightText: 2024 Andrew Gunnerson
# SPDX-License-Identifier: GPL-3.0-only

source "${0%/*}/boot_common.sh" /data/local/tmp/msd.service.log

header Starting daemon

# Full path because Magisk runs this script in busybox's standalone ash mode and
# we need Android's toybox version of runcon.
exec /system/bin/runcon u:r:msd_daemon:s0 \
    "${mod_dir}"/msd-tool."$(getprop ro.product.cpu.abi)" \
        daemon --log-target logcat --log-level debug
