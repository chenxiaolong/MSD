# SPDX-FileCopyrightText: 2023-2024 Andrew Gunnerson
# SPDX-License-Identifier: GPL-3.0-only

source "${0%/*}/boot_common.sh" /data/local/tmp/msd/post-fs-data.log

header Patching SELinux policy

cp /sys/fs/selinux/policy "${log_dir}"/sepolicy.orig
"${mod_dir}"/msd-tool."$(getprop ro.product.cpu.abi)" sepatch -ST
cp /sys/fs/selinux/policy "${log_dir}"/sepolicy.patched

# Android's SELinux implementation cannot load seapp_contexts files from a
# directory, so the original file must be edited and multiple modules may want
# to do so. Due to Magisk/KernelSU's behavior of running scripts for all modules
# before mounting any files, each module that modifies this file needs to
# produce the same output, so snippets from all modules are included.
# Regardless of the module load order, all modules that include the following
# command will produce the same output file, so it does not matter which one is
# mounted last.

header Updating seapp_contexts

mkdir -p "${mod_dir}/system/etc/selinux"
paste -s -d '\n' \
    /system/etc/selinux/plat_seapp_contexts \
    /data/adb/modules/*/plat_seapp_contexts \
    > "${mod_dir}/system/etc/selinux/plat_seapp_contexts"

# On some devices, the system time is set too late in the boot process. This,
# for some reason, causes the package manager service to not update the package
# info cache entry despite the mtime of the apk being newer than the mtime of
# the cache entry [1]. This causes the sysconfig file's hidden-api-whitelist
# option to not take effect, among other issues. Work around this by forcibly
# deleting the relevant cache entries on every boot.
#
# [1] https://cs.android.com/android/platform/superproject/+/android-13.0.0_r42:frameworks/base/services/core/java/com/android/server/pm/parsing/PackageCacher.java;l=139

header Clear package manager caches

ls -ldZ "${cli_apk%/*}"
find /data/system/package_cache -name "${app_id}-*" -exec ls -ldZ {} \+

run_cli_apk com.chiller3.msd.standalone.ClearPackageManagerCachesKt
