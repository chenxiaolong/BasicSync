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
    $(button).tooltip();
    button.appendChild(icon);

    const buttonGroup = document.createElement('span');
    buttonGroup.classList.add('input-group-btn');
    buttonGroup.appendChild(button);

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

function tryMutate() {
    if (!elemFolderPath) {
        elemFolderPath = document.getElementById('folderPath');
        if (elemFolderPath) {
            addFolderPicker(elemFolderPath);
        }
    }

    if (!elemShareDeviceIdButtons) {
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

if (!tryMutate()) {
    const callback = (mutationList, observer) => {
        // The actual elements we need are added via innerHTML by Angular, which doesn't get
        // reported as distinct mutations. It's faster to just find by element ID than to
        // recursively walk mutation.addedNodes.

        if (tryMutate()) {
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
