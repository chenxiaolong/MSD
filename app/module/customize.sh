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
}

if ! run 2>&1; then
    rm -rv "${MODPATH}" 2>&1
    exit 1
fi
