<!--
    When adding new changelog entries, use [Issue #0] to link to issues and
    [PR #0] to link to pull requests. Then run:

        ./gradlew changelogUpdateLinks

    to update the actual links at the bottom of the file.
-->

### Unreleased

* Fix save button not being enabled after selecting a folder path or scanning a device QR code ([PR #15])
* Fix device ID not being filled in when scanning a QR code the second time after adding or editing a device ([PR #16])

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
