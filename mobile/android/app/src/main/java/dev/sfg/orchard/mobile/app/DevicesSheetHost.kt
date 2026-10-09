/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.ui.screens.DevicesSheet

/** Connect popup bound to the view model; collects Connect state only while it is open. */
@Composable
internal fun DevicesSheetHost(
    viewModel: OrchardViewModel,
    targets: PlaybackTargetState,
    onDismiss: () -> Unit,
) {
    val connect by viewModel.connect.collectAsStateWithLifecycle()
    val party by viewModel.listeningParty.collectAsStateWithLifecycle()

    // Pull down to dismiss; the player underneath never noticed you left.
    DevicesSheet(
        targets = targets,
        connect = connect,
        party = party,
        onDismiss = {
            viewModel.clearConnectMessage()
            onDismiss()
        },
        onSelect = viewModel::selectTarget,
        onCreateParty = { viewModel.createListeningParty() },
        onJoinParty = { viewModel.joinListeningParty(it) },
        onLeaveParty = viewModel::leaveListeningParty,
        onRenameDevice = viewModel::renameDevice,
    )
}
