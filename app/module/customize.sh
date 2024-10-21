# SPDX-FileCopyrightText: 2023-2024 Andrew Gunnerson
# SPDX-License-Identifier: GPL-3.0-only

abi=$(getprop ro.product.cpu.abi)

echo "ABI: ${abi}"

run() {
    echo 'Making msd-tool executable'
    if ! chmod -v +x "${MODPATH}"/msd-tool."${abi}" 2>&1; then
        echo "Failed to chmod msd-tool" 2>&1
        return 1
    fi

    # https://github.com/HuskyDG/magic_overlayfs/blob/e5e6087d206f4fa4ed24683484c3df93eb258c27/magisk-module/customize.sh#L130
    if [ "$KSU" == true ]; then
        ui_print "- Running on KernelSU. SELinux policy will be patched in post-mount"
        mv -f "$MODPATH/post-fs-data.sh" "$MODPATH/post-mount.sh"
    fi
}

if ! run 2>&1; then
    rm -rv "${MODPATH}" 2>&1
    exit 1
fi
