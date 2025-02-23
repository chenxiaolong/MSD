<!--
    When adding new changelog entries, use [Issue #0] to link to issues and
    [PR #0] to link to pull requests. Then run:

        ./gradlew changelogUpdateLinks

    to update the actual links at the bottom of the file.
-->

### Version 1.12

* Add support for resizing writable disk images ([Issue #42], [PR #43])
  * Note that this only resizes the raw image. It is still necessary to manually repartition or resize the filesystems inside the image.
* Update all dependencies ([PR #44])

### Version 1.11

* Reduce minimum supported OS version from Android 13 to Android 11 ([Issue #39], [PR #40])
* Update all dependencies ([PR #41])

### Version 1.10

* Unconditionally chown the USB gadget configfs directory for better device compatibility ([Issue #36], [PR #37])

### Version 1.9

* Fix incorrect scale factor in icon and rebase off latest material security key icon ([PR #30], [PR #31])
* Show local filesystem path instead of the URI in the device list ([Issue #28], [PR #34])
* Update all dependencies ([PR #35])

### Version 1.8

* Let Magisk/KernelSU handle mounting `/system/etc/selinux/plat_seapp_contexts` ([Issue #17], [Issue #21], [PR #25], [PR #26])
* Update all dependencies ([PR #27])

### Version 1.7

* Capture configfs permission and mount point information in debug logs ([PR #22])
* Improve logic for detecting when configfs permissions need to be changed ([Issue #21], [PR #23])
* Enable predictive back gestures ([PR #24])

### Version 1.6

* Fix compatibility with KernelSU ([Issue #17], [PR #18])
* Add support for devices without the gadget HAL ([Issue #19], [PR #20])

### Version 1.5

* Add additional logging to boot scripts to make troubleshooting easier ([Issue #12], [PR #15])
* Relax process search criteria to find Pixel 6 series' USB gadget HAL after recent OS updates ([Issue #12], [PR #16])

### Version 1.4

* Make the UI more intuitive and better reflect the current state of the USB controller ([PR #14])

### Version 1.3

* Target API 35 ([PR #8], [PR #9])
* Fix compatibility when installed alongside other modules that modify `plat_seapp_contexts` with upcoming versions of Magisk ([PR #13])

### Version 1.2

* msd-tool: Prevent updating the modification timestamp of `/sys/fs/selinux/load` ([Issue #2], [PR #6])
  * Please note there are no plans to implement further ways of evading detection by apps. This workaround just happened to be easy enough to implement.

### Version 1.1

* Update checksum for `tensorflow-lite-metadata-0.1.0-rc2.pom` dependency ([PR #1])
* Fix incorrect gradle inputs causing Rust source code to not be rebuilt ([PR #3])
* Remove unnecessary `mlstrustedobject` attribute from the `msd_daemon` SELinux type ([PR #4])
* Allow disabling individual images so that not all of them need to be active at the same time ([PR #5])

### Version 1.0

* Initial release

<!-- Do not manually edit the lines below. Use `./gradlew changelogUpdateLinks` to regenerate. -->
[Issue #2]: https://github.com/chenxiaolong/MSD/issues/2
[Issue #12]: https://github.com/chenxiaolong/MSD/issues/12
[Issue #17]: https://github.com/chenxiaolong/MSD/issues/17
[Issue #19]: https://github.com/chenxiaolong/MSD/issues/19
[Issue #21]: https://github.com/chenxiaolong/MSD/issues/21
[Issue #28]: https://github.com/chenxiaolong/MSD/issues/28
[Issue #36]: https://github.com/chenxiaolong/MSD/issues/36
[Issue #39]: https://github.com/chenxiaolong/MSD/issues/39
[Issue #42]: https://github.com/chenxiaolong/MSD/issues/42
[PR #1]: https://github.com/chenxiaolong/MSD/pull/1
[PR #3]: https://github.com/chenxiaolong/MSD/pull/3
[PR #4]: https://github.com/chenxiaolong/MSD/pull/4
[PR #5]: https://github.com/chenxiaolong/MSD/pull/5
[PR #6]: https://github.com/chenxiaolong/MSD/pull/6
[PR #8]: https://github.com/chenxiaolong/MSD/pull/8
[PR #9]: https://github.com/chenxiaolong/MSD/pull/9
[PR #13]: https://github.com/chenxiaolong/MSD/pull/13
[PR #14]: https://github.com/chenxiaolong/MSD/pull/14
[PR #15]: https://github.com/chenxiaolong/MSD/pull/15
[PR #16]: https://github.com/chenxiaolong/MSD/pull/16
[PR #18]: https://github.com/chenxiaolong/MSD/pull/18
[PR #20]: https://github.com/chenxiaolong/MSD/pull/20
[PR #22]: https://github.com/chenxiaolong/MSD/pull/22
[PR #23]: https://github.com/chenxiaolong/MSD/pull/23
[PR #24]: https://github.com/chenxiaolong/MSD/pull/24
[PR #25]: https://github.com/chenxiaolong/MSD/pull/25
[PR #26]: https://github.com/chenxiaolong/MSD/pull/26
[PR #27]: https://github.com/chenxiaolong/MSD/pull/27
[PR #30]: https://github.com/chenxiaolong/MSD/pull/30
[PR #31]: https://github.com/chenxiaolong/MSD/pull/31
[PR #34]: https://github.com/chenxiaolong/MSD/pull/34
[PR #35]: https://github.com/chenxiaolong/MSD/pull/35
[PR #37]: https://github.com/chenxiaolong/MSD/pull/37
[PR #40]: https://github.com/chenxiaolong/MSD/pull/40
[PR #41]: https://github.com/chenxiaolong/MSD/pull/41
[PR #43]: https://github.com/chenxiaolong/MSD/pull/43
[PR #44]: https://github.com/chenxiaolong/MSD/pull/44
