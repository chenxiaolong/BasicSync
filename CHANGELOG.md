<!--
    When adding new changelog entries, use [Issue #0] to link to issues and
    [PR #0] to link to pull requests. Then run:

        ./gradlew changelogUpdateLinks

    to update the actual links at the bottom of the file.
-->

### Unreleased

* Consider plugged in device as charging (eg. when device reached charge limit) ([PR #3])
* Update syncthing to 2.0.13 ([PR #4])
* Backport upstream fix to skip point-to-point interfaces ([PR #5])
  * Reduces power consumption previously caused by use of the cellular interface when local discovery was enabled.

### Version 1.1

* Update syncthing to 2.0.12 ([PR #2])

### Version 1.0

* Initial release

<!-- Do not manually edit the lines below. Use `./gradlew changelogUpdateLinks` to regenerate. -->
[PR #2]: https://github.com/chenxiaolong/BasicSync/pull/2
[PR #3]: https://github.com/chenxiaolong/BasicSync/pull/3
[PR #4]: https://github.com/chenxiaolong/BasicSync/pull/4
[PR #5]: https://github.com/chenxiaolong/BasicSync/pull/5
