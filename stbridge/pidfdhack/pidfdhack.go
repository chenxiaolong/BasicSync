// SPDX-FileCopyrightText: 2025 Andrew Gunnerson
// SPDX-License-Identifier: GPL-3.0-only

// We get killed on Android 11 and older due to seccomp blocking the pidfd_open
// syscall. Upstream golang works around this by ignoring the SIGSYS signal for
// that invocation (https://github.com/golang/go/pull/69543), but this does not
// work when loaded as a shared library because go's normal signal handler is
// not registered. To work around this, we just handle SIGSYS and log the error.
//
// Some alternatives that were considered:
//
// * Overriding os.checkPidfdOnce with go:linkname. For unknown reasons, this
//   only sometimes works. If it is set to a function that just returns an
//   error, then pidfd_open is still called. If a fmt.Printf() call is added,
//   then it works. This is despite the function only being called once (no
//   races between threads).
//
// * Replacing os.ignoreSIGSYS() and os.restoreSIGSYS() with functions that
//   temporarily block receiving SIGSYS with sigprocmask(). Unfortunately, the
//   builtin runtime package already provides implementations via go:linkname,
//   so we would get a duplicate symbol error when linking.

package pidfdhack

/*
#include <signal.h>
#include <string.h>

#include <android/api-level.h>
#include <android/log.h>

#define LOG(level, ...) __android_log_print(ANDROID_LOG_ ## level, "pidfdhack", __VA_ARGS__)

void sigsys_handler(int signum, siginfo_t *info, void *context) {
	if (info->si_code == SYS_SECCOMP) {
		LOG(
			WARN,
			"Received seccomp SIGSYS (call=%p, arch=%u, syscall=%d): %s",
			info->si_call_addr,
			info->si_arch,
			info->si_syscall,
			strerror(info->si_errno)
		);
	} else {
		LOG(WARN, "Received non-seccomp SIGSYS: %s", strerror(info->si_errno));
	}
}

void block_sigsys() {
	struct sigaction sa = { 0 };
    sa.sa_flags = SA_SIGINFO;
    sa.sa_sigaction = &sigsys_handler;

    if (sigaction(SIGSYS, &sa, NULL) == -1) {
		LOG(WARN, "Failed to set SIGSYS handler");
    }

	LOG(INFO, "Successfully set SIGSYS handler");
}
*/
import "C"
import (
	_ "unsafe"
)

var shouldIgnoreSigsys = int(C.android_get_device_api_level()) <= 30

func init() {
	if shouldIgnoreSigsys {
		C.block_sigsys()
	}
}
