/*
 * SPDX-FileCopyrightText: 2025-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

function addFolderPicker(element) {
    console.log('Adding folder picker to:', element);

    const icon = document.createElement('span');
    icon.classList.add('fa');
    icon.classList.add('fa-lg');
    icon.classList.add('fa-folder-open-o');

    const button = document.createElement('button');
    button.type = 'button';
    button.classList.add('btn');
    button.classList.add('btn-default');
    button.setAttribute('data-container', 'body');
    button.setAttribute('data-original-title', BasicSync.getTranslation('select_folder'));
    button.setAttribute('ng-disabled', 'editingFolderExisting()');
    $(button).tooltip();
    button.appendChild(icon);

    const buttonGroup = document.createElement('span');
    buttonGroup.classList.add('input-group-btn');

    angular.element(document).injector().invoke(function($compile) {
        const scope = angular.element(element).scope();
        $compile(button)(scope);

        buttonGroup.appendChild(button);
    });

    const inputGroup = document.createElement('div');
    inputGroup.classList.add('input-group');

    const parent = element.parentElement;
    parent.insertBefore(inputGroup, element);
    parent.removeChild(element);
    inputGroup.appendChild(element);
    inputGroup.appendChild(buttonGroup);

    button.addEventListener('click', function() {
        BasicSync.openFolderPicker(element.value);
    }, false);

    // Disable the builtin autocomplete. The popup renders very poorly on mobile, with the width
    // frequently being too narrow and it not opening at the correct position.
    element.removeAttribute('list');
}

function addQrScanner(element) {
    console.log('Adding QR scanner button to:', element);

    const icon = document.createElement('span');
    icon.classList.add('fa');
    icon.classList.add('fa-lg');
    icon.classList.add('fa-camera');

    const button = document.createElement('button');
    button.type = 'button';
    button.classList.add('btn');
    button.classList.add('btn-default');
    button.setAttribute('data-container', 'body');
    button.setAttribute('data-original-title', BasicSync.getTranslation('scan_qr_code'));
    button.setAttribute('ng-disabled', '!editingDeviceNew()');
    button.setAttribute('tooltip', '');
    button.appendChild(icon);

    angular.element(document).injector().invoke(function($compile) {
        const scope = angular.element(element).scope();
        $compile(button)(scope);

        element.appendChild(button);
    });

    button.addEventListener('click', function() {
        BasicSync.scanQrCode();
    }, false);
}

function hideActionMenuItem(iconElement) {
    var listItem = iconElement;

    while (listItem && !(listItem instanceof HTMLLIElement)) {
        listItem = listItem.parentElement;
    }

    if (!listItem) {
        throw new Error(`Parent <li> for action not found: ${iconElement.classList}`);
    }

    listItem.style.display = 'none';
}

function hideParent(child, predicate) {
    var parent = child.parentElement;

    while (parent && !predicate(parent)) {
        parent = parent.parentElement;
    }

    if (!parent) {
        throw new Error(`Matching parent not found for: ${child.id} ${child.classList}`);
    }

    parent.style.display = 'none';
}

var elemFolderPath = undefined;
var elemShareDeviceIdButtons = undefined;

const actionsToHide = new Set([
    // Hide the log out button so the user doesn't get into a state where they have to restart the
    // webview to log in again.
    'fa-sign-out',
    // Hide the shut down button because it behaves exactly the same as restart due to
    // SyncthingService's run loop mechanism.
    'fa-power-off',
]);

// There are many other ways the user can shoot themselves in the foot, like by syncing xattrs, but
// we'll only discourage messing with settings that break the configuration web UI.
const settingsToDisable = new Set([
    // The webview requires the password to be the API key because there's no sane way to inject
    // custom headers, so we need basic auth. Hide the password field to reduce the chance of users
    // breaking their setup (until the next restart when the password is forcibly changed back).
    'password',
    // Android blocks HTTP by default and we don't override this restriction.
    'UseTLS',
    // Cannot work on Android.
    'StartBrowser',
]);

function tryMutate(isTv) {
    if (!elemFolderPath) {
        elemFolderPath = document.getElementById('folderPath');
        if (elemFolderPath) {
            addFolderPicker(elemFolderPath);
        }
    }

    if (!isTv && !elemShareDeviceIdButtons) {
        elemShareDeviceIdButtons = document.getElementById('shareDeviceIdButtons');
        if (elemShareDeviceIdButtons) {
            addQrScanner(elemShareDeviceIdButtons);
        }
    }

    for (const className of actionsToHide) {
        const icon = document.getElementsByClassName(className)[0];
        if (icon) {
            hideParent(icon, function(parent) {
                return parent instanceof HTMLLIElement;
            });
            actionsToHide.delete(className);
        }
    }

    for (const id of settingsToDisable) {
        const field = document.getElementById(id);
        if (field) {
            field.disabled = true;
            settingsToDisable.delete(id);
        }
    }

    return !!elemFolderPath
        && !!elemShareDeviceIdButtons
        && actionsToHide.size == 0
        && settingsToDisable.size == 0;
}

function onFolderSelected(path) {
    elemFolderPath.value = path;
    elemFolderPath.dispatchEvent(new InputEvent('input'));
}

function onDeviceIdScanned(deviceId) {
    const elemDeviceId = document.getElementById('deviceID');
    elemDeviceId.value = deviceId;
    elemDeviceId.dispatchEvent(new InputEvent('input'));
}

function bridgeInit(isTv) {
    if (!tryMutate(isTv)) {
        const callback = (mutationList, observer) => {
            // The actual elements we need are added via innerHTML by Angular, which doesn't get
            // reported as distinct mutations. It's faster to just find by element ID than to
            // recursively walk mutation.addedNodes.

            if (tryMutate(isTv)) {
                console.log('All mutations complete; unregistering observer');
                observer.disconnect();
            }
        };

        const observer = new MutationObserver(callback);
        observer.observe(document.body, {
            childList: true,
            subtree: true,
        });
    }

    // Prevent arrow keys from opening dropdown menus. There is no good way to leave the menu
    // afterwards because the up/down arrow keys are hijacked to only move within the list and a TV
    // remote has no escape button to close the menu. Spatial navigation already works very well for
    // this use case.
    $(document).off('keydown.bs.dropdown.data-api');

    // This is atrocious. By default, the browser makes the up/down arrow keys adjust number inputs
    // up and down. preventDefault() stops this, but also prevents using spatial navigation to move
    // to the next element above or below the input. There is currently no way to trigger spatial
    // navigation programmatically. Instead, we'll just make the field read-only for a bit so that
    // the arrow keys don't change the value.
    $(document).on('keydown', 'input', function (e) {
        if ((e.which == 38 || e.which == 40) && e.target.type == 'number') {
            const wasReadOnly = e.target.readOnly;

            e.target.readOnly = true;
            if (!wasReadOnly) {
                setTimeout(function() { e.target.readOnly = false; }, 100);
            }
        }
    });

    if (isTv) {
        const style = document.createElement('style');
        style.innerHTML = ':focus { border: 3px dotted !important; }';
        document.body.appendChild(style);
    }

    // We use the bootstrap nav bar as the app nav bar, so don't let it get scrolled away.
    const nav = document.getElementsByTagName('nav')[0];
    nav.classList.remove('navbar-top');
    nav.classList.add('navbar-fixed-top');

    // The webview is edge-to-edge, so insets need to be handled manually.
    const origContent = document.getElementsByClassName('content')[0];
    const safeContent = document.createElement('div');
    safeContent.classList.add('safe-content');
    origContent.parentElement.insertBefore(safeContent, origContent);
    safeContent.appendChild(origContent);

    const edgeToEdgeStyle = document.createElement('style');
    edgeToEdgeStyle.innerHTML = `
        /*
         * Normally, Syncthing only makes the dropdown menus scrollable on narrow screens. However,
         * due to us using a fixed/sticky nav bar, on wide screens, it's no longer possible to
         * scroll the menus by scrolling the page itself. We have to make the menus individually
         * scrollable instead.
         */
        .dropdown-menu {
            column-count: auto !important;
            height: unset !important;
            /* Roughly the amount of free space excluding insets and nav bar. */
            max-height: max(50px, calc(100vh - env(safe-area-inset-top) - env(safe-area-inset-bottom) - 60px));
            overflow-y: scroll;
            /* Avoid scrolling the content underneath after reaching the boundary. */
            overscroll-behavior-y: contain;
        }

        .navbar-fixed-top {
            /* The vendored bootstrap version is too old to have the sticky-top class. */
            position: sticky;

            padding-top: env(safe-area-inset-top);
            padding-right: env(safe-area-inset-right);
            padding-left: env(safe-area-inset-left);
        }

        .safe-content {
            padding-right: env(safe-area-inset-right);
            padding-bottom: env(safe-area-inset-bottom);
            padding-left: env(safe-area-inset-left);
        }

        .modal-content {
            margin-top: env(safe-area-inset-top);
            margin-right: env(safe-area-inset-right);
            margin-bottom: env(safe-area-inset-bottom);
            margin-left: env(safe-area-inset-left);
        }
    `;
    document.body.appendChild(edgeToEdgeStyle);
}
