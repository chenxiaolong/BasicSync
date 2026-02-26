<!--
    When adding new changelog entries, use [Issue #0] to link to issues and
    [PR #0] to link to pull requests. Then run:

        ./gradlew changelogUpdateLinks

    to update the actual links at the bottom of the file.
-->

### Version 1.11

* Add Romanian translations ([PR #41], [PR #45])
* Add French translations ([PR #42], [PR #46])
* Add Hungarian translations ([PR #43], [PR #47])
* Display the reason syncthing is paused/stopped ([PR #44])

### Version 1.10

* Add option to respect Android's auto-sync data setting ([Issue #39], [PR #40])
  * This is enabled by default.

### Version 1.9

* Make configuration import atomic ([PR #37])
  * If the import fails, the existing configuration remains unchanged now.
* Add support for interval-based time schedule ([Issue #14], [Issue #36], [PR #38])

### Version 1.8

* Shut down all listening sockets when syncthing is paused ([Issue #26], [PR #27])
* Add support for importing and exporting encrypted zip files ([Issue #29], [PR #30], [PR #34])
* Ensure that the golang fork is always used, even if the host toolchain is newer ([PR #31])
* Update dependencies ([PR #32], [PR #33])
  * Includes quic-go update that fixes intermittent crashes.

### Version 1.7

* Reenable default proguard optimizations ([PR #21], [PR #22])
  * For folks who want to decode stack traces from log files, the mapping files are now included with the official releases in `mappings.tar.zst`
* Don't fail the build if some languages have missing strings in their translations ([PR #24])
* Add Romanian translations ([PR #23])
* Add support for Android's per-app language setting ([PR #25])

### Version 1.6

* Fix save button not being enabled after selecting a folder path or scanning a device QR code ([PR #15])
* Fix device ID not being filled in when scanning a QR code the second time after adding or editing a device ([PR #16])
* Fix QR code scanner button being clickable when editing an existing device ([PR #17])
* Add support for customizing the minimum battery level threshold ([Issue #14], [PR #18])
* Add support for run conditions based on network interface type or connected Wi-Fi network ([Issue #14], [PR #19])

### Version 1.5

* Enable MTE on supported devices ([Issue #12], [PR #13])

### Version 1.4

* Update syncthing to 2.0.14 ([PR #10])
* Disable badge (dot on launcher icon) for persistent notifications by default ([PR #11])
  * This only applies to new installs. For existing installs, this can be disabled from Android's notification settings for BasicSync's "Background services" notification channel.
* Update AGP to 9.0.0 ([PR #9])

### Version 1.3

* By default, don't run when Android's battery saver mode is enabled (configurable) ([PR #7])

### Version 1.2

* Consider plugged in device as charging (eg. when device reached charge limit) ([PR #3])
* Update syncthing to 2.0.13 ([PR #4])
* Backport upstream fix to skip point-to-point interfaces ([PR #5])
  * Reduces power consumption previously caused by use of the cellular interface when local discovery was enabled.
* Pause Syncthing instead of shutting it down by default ([PR #6])
  * Reduces power consumption caused by the initial folder scan at startup.
  * Makes the most difference when using large folders or when the network connection is unstable and frequently flapping.

### Version 1.1

* Update syncthing to 2.0.12 ([PR #2])

### Version 1.0

* Initial release

<!-- Do not manually edit the lines below. Use `./gradlew changelogUpdateLinks` to regenerate. -->
[Issue #12]: https://github.com/chenxiaolong/BasicSync/issues/12
[Issue #14]: https://github.com/chenxiaolong/BasicSync/issues/14
[Issue #26]: https://github.com/chenxiaolong/BasicSync/issues/26
[Issue #29]: https://github.com/chenxiaolong/BasicSync/issues/29
[Issue #36]: https://github.com/chenxiaolong/BasicSync/issues/36
[Issue #39]: https://github.com/chenxiaolong/BasicSync/issues/39
[PR #2]: https://github.com/chenxiaolong/BasicSync/pull/2
[PR #3]: https://github.com/chenxiaolong/BasicSync/pull/3
[PR #4]: https://github.com/chenxiaolong/BasicSync/pull/4
[PR #5]: https://github.com/chenxiaolong/BasicSync/pull/5
[PR #6]: https://github.com/chenxiaolong/BasicSync/pull/6
[PR #7]: https://github.com/chenxiaolong/BasicSync/pull/7
[PR #9]: https://github.com/chenxiaolong/BasicSync/pull/9
[PR #10]: https://github.com/chenxiaolong/BasicSync/pull/10
[PR #11]: https://github.com/chenxiaolong/BasicSync/pull/11
[PR #13]: https://github.com/chenxiaolong/BasicSync/pull/13
[PR #15]: https://github.com/chenxiaolong/BasicSync/pull/15
[PR #16]: https://github.com/chenxiaolong/BasicSync/pull/16
[PR #17]: https://github.com/chenxiaolong/BasicSync/pull/17
[PR #18]: https://github.com/chenxiaolong/BasicSync/pull/18
[PR #19]: https://github.com/chenxiaolong/BasicSync/pull/19
[PR #21]: https://github.com/chenxiaolong/BasicSync/pull/21
[PR #22]: https://github.com/chenxiaolong/BasicSync/pull/22
[PR #23]: https://github.com/chenxiaolong/BasicSync/pull/23
[PR #24]: https://github.com/chenxiaolong/BasicSync/pull/24
[PR #25]: https://github.com/chenxiaolong/BasicSync/pull/25
[PR #27]: https://github.com/chenxiaolong/BasicSync/pull/27
[PR #30]: https://github.com/chenxiaolong/BasicSync/pull/30
[PR #31]: https://github.com/chenxiaolong/BasicSync/pull/31
[PR #32]: https://github.com/chenxiaolong/BasicSync/pull/32
[PR #33]: https://github.com/chenxiaolong/BasicSync/pull/33
[PR #34]: https://github.com/chenxiaolong/BasicSync/pull/34
[PR #37]: https://github.com/chenxiaolong/BasicSync/pull/37
[PR #38]: https://github.com/chenxiaolong/BasicSync/pull/38
[PR #40]: https://github.com/chenxiaolong/BasicSync/pull/40
[PR #41]: https://github.com/chenxiaolong/BasicSync/pull/41
[PR #42]: https://github.com/chenxiaolong/BasicSync/pull/42
[PR #43]: https://github.com/chenxiaolong/BasicSync/pull/43
[PR #44]: https://github.com/chenxiaolong/BasicSync/pull/44
[PR #45]: https://github.com/chenxiaolong/BasicSync/pull/45
[PR #46]: https://github.com/chenxiaolong/BasicSync/pull/46
[PR #47]: https://github.com/chenxiaolong/BasicSync/pull/47
