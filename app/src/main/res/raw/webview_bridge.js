/*
 * SPDX-FileCopyrightText: 2025 Andrew Gunnerson
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
    $(button).tooltip();
    button.appendChild(icon);

    element.appendChild(button);

    button.addEventListener('click', function() {
        BasicSync.scanQrCode();
    }, false);
}

var elemFolderPath = undefined;
var elemShareDeviceIdButtons = undefined;
var elemDeviceId = undefined;

function tryAddExtraButtons() {
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

    if (!elemDeviceId) {
        elemDeviceId = document.getElementById('deviceID');
    }

    return !!elemFolderPath && !!elemShareDeviceIdButtons && !!elemDeviceId;
}

if (!tryAddExtraButtons()) {
    const callback = (mutationList, observer) => {
        for (const mutation of mutationList) {
            // The actual elements we need are added via innerHTML by Angular, which doesn't get
            // reported as distinct mutations. It's faster to just find by element ID than to
            // recursively walk mutation.addedNodes.

            if (tryAddExtraButtons()) {
                observer.disconnect();
            }
        }
    };

    const observer = new MutationObserver(callback);
    observer.observe(document.body, {
        childList: true,
        subtree: true,
    });
}

function onFolderSelected(path) {
    elemFolderPath.value = path;
}

function onDeviceIdScanned(deviceId) {
    elemDeviceId.value = deviceId;
}
