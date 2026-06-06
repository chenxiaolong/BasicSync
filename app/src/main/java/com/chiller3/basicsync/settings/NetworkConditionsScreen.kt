/*
 * SPDX-FileCopyrightText: 2024-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.basicsync.settings

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chiller3.basicsync.Permissions
import com.chiller3.basicsync.Preferences
import com.chiller3.basicsync.R
import com.chiller3.basicsync.syncthing.SyncthingService
import com.chiller3.basicsync.ui.AppScreen
import com.chiller3.basicsync.ui.BetterSegmentedShapes
import com.chiller3.basicsync.ui.Preference
import com.chiller3.basicsync.ui.PreferenceCategory
import com.chiller3.basicsync.ui.PreferenceColumn
import com.chiller3.basicsync.ui.PreferenceDefaults
import com.chiller3.basicsync.ui.SplitSwitchPreference
import com.chiller3.basicsync.ui.SwitchPreference
import com.chiller3.basicsync.ui.betterSegmentedShapes
import com.chiller3.basicsync.ui.theme.AppTheme

@Composable
fun NetworkConditionsScreen(
    onBack: () -> Unit,
    viewModel: NetworkConditionsViewModel = viewModel(),
) {
    val context = LocalContext.current

    val prefs = remember { Preferences(context) }
    var reloadPrefs by remember { mutableIntStateOf(0) }
    val requireUnmeteredNetwork = remember(reloadPrefs) { prefs.requireUnmeteredNetwork }
    val networkAllowWifi = remember(reloadPrefs) { prefs.networkAllowWifi }
    val networkAllowCellular = remember(reloadPrefs) { prefs.networkAllowCellular }
    val networkAllowEthernet = remember(reloadPrefs) { prefs.networkAllowEthernet }
    val networkAllowOther = remember(reloadPrefs) { prefs.networkAllowOther }

    var reloadPerms by remember { mutableIntStateOf(0) }
    val preciseLocationGranted = remember(reloadPerms) {
        Permissions.have(context, Permissions.PRECISE_LOCATION)
    }
    val backgroundLocationGranted = remember(reloadPerms) {
        Permissions.have(context, Permissions.BACKGROUND_LOCATION)
    }

    val allowedNetworks by viewModel.allowed.collectAsStateWithLifecycle()
    val availableNetworks by viewModel.available.collectAsStateWithLifecycle()

    val requestPermissionActivity = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        reloadPerms++
    }
    val requestPermissionsRequired = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.all { it.value }) {
            // We could be starting from a state where allowed Wi-Fi networks were previously
            // configured, but the location permission was later disabled. When re-enabling the
            // permission, the service needs to request the foreground location permission.
            SyncthingService.start(context, SyncthingService.ACTION_RENOTIFY)

            // Android will not notify us that we now have access to the Wi-Fi scan results.
            viewModel.checkScanResults()

            reloadPerms++
        } else {
            requestPermissionActivity.launch(Permissions.getAppInfoIntent(context))
        }
    }

    AppScreen(
        title = { Text(text = stringResource(R.string.pref_network_conditions_name)) },
        onBack = onBack,
    ) { params ->
        NetworkConditionsContent(
            requireUnmeteredNetwork = requireUnmeteredNetwork,
            networkAllowWifi = networkAllowWifi,
            networkAllowCellular = networkAllowCellular,
            networkAllowEthernet = networkAllowEthernet,
            networkAllowOther = networkAllowOther,
            preciseLocationGranted = preciseLocationGranted,
            backgroundLocationGranted = backgroundLocationGranted,
            allowedNetworks = allowedNetworks,
            availableNetworks = availableNetworks,
            onRequireUnmeteredNetworkChange = { enabled ->
                prefs.requireUnmeteredNetwork = enabled
                reloadPrefs++
            },
            onNetworkAllowWifiChange = { enabled ->
                prefs.networkAllowWifi = enabled
                reloadPrefs++
            },
            onNetworkAllowCellularChange = { enabled ->
                prefs.networkAllowCellular = enabled
                reloadPrefs++
            },
            onNetworkAllowEthernetChange = { enabled ->
                prefs.networkAllowEthernet = enabled
                reloadPrefs++
            },
            onNetworkAllowOtherChange = { enabled ->
                prefs.networkAllowOther = enabled
                reloadPrefs++
            },
            onPreciseLocationGrant = {
                requestPermissionsRequired.launch(Permissions.PRECISE_LOCATION)
            },
            onBackgroundLocationGrant = {
                requestPermissionsRequired.launch(Permissions.BACKGROUND_LOCATION)
            },
            onAllowedNetworkAdd = { name ->
                viewModel.addNetwork(name)
            },
            onAllowedNetworkReplace = { oldName, newName ->
                viewModel.replaceNetwork(oldName, newName)
            },
            onAllowedNetworkRemove = { name ->
                viewModel.removeNetwork(name)
            },
            contentPadding = params.contentPadding,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NetworkConditionsContent(
    requireUnmeteredNetwork: Boolean,
    networkAllowWifi: Boolean,
    networkAllowCellular: Boolean,
    networkAllowEthernet: Boolean,
    networkAllowOther: Boolean,
    preciseLocationGranted: Boolean,
    backgroundLocationGranted: Boolean,
    allowedNetworks: List<String>,
    availableNetworks: List<String>,
    onRequireUnmeteredNetworkChange: (Boolean) -> Unit,
    onNetworkAllowWifiChange: (Boolean) -> Unit,
    onNetworkAllowCellularChange: (Boolean) -> Unit,
    onNetworkAllowEthernetChange: (Boolean) -> Unit,
    onNetworkAllowOtherChange: (Boolean) -> Unit,
    onPreciseLocationGrant: () -> Unit,
    onBackgroundLocationGrant: () -> Unit,
    onAllowedNetworkAdd: (String) -> Unit,
    onAllowedNetworkReplace: (String, String) -> Unit,
    onAllowedNetworkRemove: (String) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var showWifiNetworkDialog by rememberSaveable { mutableStateOf<WifiNetworkDialogAction?>(null) }

    PreferenceColumn(contentPadding = contentPadding) {
        item(key = "allowed_network_types") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_allowed_network_types)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "require_unmetered_network") {
            SwitchPreference(
                checked = requireUnmeteredNetwork,
                onCheckedChange = onRequireUnmeteredNetworkChange,
                shapes = BetterSegmentedShapes.top(),
                title = { Text(text = stringResource(R.string.pref_require_unmetered_network_name)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "network_allow_wifi") {
            SwitchPreference(
                checked = networkAllowWifi,
                onCheckedChange = onNetworkAllowWifiChange,
                shapes = BetterSegmentedShapes.middle(),
                title = { Text(text = stringResource(R.string.pref_network_allow_wifi_name)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "network_allow_cellular") {
            SwitchPreference(
                checked = networkAllowCellular,
                onCheckedChange = onNetworkAllowCellularChange,
                shapes = BetterSegmentedShapes.middle(),
                title = { Text(text = stringResource(R.string.pref_network_allow_cellular_name)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "network_allow_ethernet") {
            SwitchPreference(
                checked = networkAllowEthernet,
                onCheckedChange = onNetworkAllowEthernetChange,
                shapes = BetterSegmentedShapes.middle(),
                title = { Text(text = stringResource(R.string.pref_network_allow_ethernet_name)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "network_allow_other") {
            SwitchPreference(
                checked = networkAllowOther,
                onCheckedChange = onNetworkAllowOtherChange,
                shapes = BetterSegmentedShapes.bottom(),
                title = { Text(text = stringResource(R.string.pref_network_allow_other_name)) },
                modifier = Modifier.animateItem(),
            )
        }

        val allLocationGranted = preciseLocationGranted && backgroundLocationGranted

        if (!allLocationGranted) {
            item(key = "permissions") {
                PreferenceCategory(
                    title = { Text(text = stringResource(R.string.pref_header_permissions)) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "info_location_permissions") {
                Preference(
                    onClick = {},
                    enabled = networkAllowWifi,
                    shapes = BetterSegmentedShapes.single(),
                    title = {},
                    summary = { Text(text = stringResource(R.string.pref_location_optional_info)) },
                    colors = PreferenceDefaults.preferenceInfoColors(),
                    modifier = Modifier.animateItem(),
                )
            }

            if (!preciseLocationGranted) {
                item(key = "allow_precise_location") {
                    Preference(
                        onClick = onPreciseLocationGrant,
                        enabled = networkAllowWifi,
                        shapes = BetterSegmentedShapes.top(),
                        title = { Text(text = stringResource(R.string.pref_allow_precise_location_name)) },
                        summary = { Text(text = stringResource(R.string.pref_allow_precise_location_desc)) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            if (!backgroundLocationGranted) {
                item(key = "allow_background_location") {
                    Preference(
                        onClick = onBackgroundLocationGrant,
                        // This permission cannot be granted until precise location is granted.
                        enabled = networkAllowWifi && preciseLocationGranted,
                        shapes = if (preciseLocationGranted) {
                            BetterSegmentedShapes.single()
                        } else {
                            BetterSegmentedShapes.bottom()
                        },
                        title = { Text(text = stringResource(R.string.pref_allow_background_location_name)) },
                        summary = { Text(text = stringResource(R.string.pref_allow_background_location_desc)) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        item(key = "allowed_wifi_networks") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_allowed_wifi_networks)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "info_allowed_wifi_networks") {
            Preference(
                onClick = {},
                enabled = networkAllowWifi && allLocationGranted,
                shapes = BetterSegmentedShapes.single(),
                title = {},
                summary = { Text(text = stringResource(R.string.pref_wifi_networks_info)) },
                colors = PreferenceDefaults.preferenceInfoColors(),
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "add_new_network") {
            Preference(
                onClick = { showWifiNetworkDialog = WifiNetworkDialogAction.Add },
                enabled = networkAllowWifi && allLocationGranted,
                shapes = if (allowedNetworks.isEmpty()) {
                    BetterSegmentedShapes.single()
                } else {
                    BetterSegmentedShapes.top()
                },
                title = { Text(text = stringResource(R.string.pref_add_wifi_network_name)) },
                modifier = Modifier.animateItem(),
            )
        }

        itemsIndexed(allowedNetworks, key = { _, n -> "allowed_network_$n" }) { index, name ->
            SplitSwitchPreference(
                onClick = { showWifiNetworkDialog = WifiNetworkDialogAction.Edit(name) },
                checked = true,
                onCheckedChange = { onAllowedNetworkRemove(name) },
                enabled = networkAllowWifi && allLocationGranted,
                shapes = betterSegmentedShapes(index + 1, allowedNetworks.size + 1),
                title = { Text(text = name) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "available_wifi_networks") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_available_wifi_networks)) },
                modifier = Modifier.animateItem(),
            )
        }

        itemsIndexed(availableNetworks, key = { _, n -> "available_network_$n" }) { index, name ->
            SwitchPreference(
                checked = false,
                onCheckedChange = { onAllowedNetworkAdd(name) },
                enabled = networkAllowWifi && allLocationGranted,
                shapes = betterSegmentedShapes(index, availableNetworks.size),
                title = { Text(text = name) },
                modifier = Modifier.animateItem(),
            )
        }
    }

    showWifiNetworkDialog?.let { action ->
        WifiNetworkDialog(
            action = action,
            onSelect = { name ->
                when (action) {
                    WifiNetworkDialogAction.Add -> onAllowedNetworkAdd(name)
                    is WifiNetworkDialogAction.Edit -> onAllowedNetworkReplace(action.name, name)
                }

                showWifiNetworkDialog = null
            },
            onDismiss = {
                showWifiNetworkDialog = null
            },
        )
    }
}

@Preview(
    name = "Light Mode",
    showBackground = true,
)
@Preview(
    name = "Dark Mode",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
private fun PreviewNetworkConditionsScreen() {
    AppTheme {
        AppScreen(
            title = { Text(text = stringResource(R.string.pref_network_conditions_name)) },
            onBack = {},
        ) { params ->
            NetworkConditionsContent(
                requireUnmeteredNetwork = true,
                networkAllowWifi = true,
                networkAllowCellular = true,
                networkAllowEthernet = true,
                networkAllowOther = true,
                preciseLocationGranted = false,
                backgroundLocationGranted = false,
                allowedNetworks = listOf("AndroidWifi"),
                availableNetworks = listOf("Test"),
                onRequireUnmeteredNetworkChange = {},
                onNetworkAllowWifiChange = {},
                onNetworkAllowCellularChange = {},
                onNetworkAllowEthernetChange = {},
                onNetworkAllowOtherChange = {},
                onPreciseLocationGrant = {},
                onBackgroundLocationGrant = {},
                onAllowedNetworkAdd = {},
                onAllowedNetworkReplace = { _, _ -> },
                onAllowedNetworkRemove = {},
                contentPadding = params.contentPadding,
            )
        }
    }
}
