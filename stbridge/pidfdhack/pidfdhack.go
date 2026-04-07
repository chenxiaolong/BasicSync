// SPDX-FileCopyrightText: 2025-2026 Andrew Gunnerson
// SPDX-License-Identifier: GPL-3.0-only

// We get killed on Android 11 and older due to seccomp blocking the pidfd_open
// syscall. Upstream golang works around this by ignoring the SIGSYS signal for
// that invocation (https://github.com/golang/go/pull/69543), but this does not
// work when loaded as a shared library because go's normal signal handler is
// not registered. To work around this, we just override checkPidfdOnce.

package pidfdhack

/*
#include <android/api-level.h>
*/
import "C"
import (
	"errors"
	_ "os"
	_ "unsafe"
)

//go:linkname checkPidfdOnce os.checkPidfdOnce
var checkPidfdOnce func() error

var pidFdError = errors.New("Would be blocked by seccomp")

func pidFdErrorFunc() error {
	return pidFdError
}

func init() {
	if int(C.android_get_device_api_level()) <= 30 {
		checkPidfdOnce = pidFdErrorFunc
	}
}
