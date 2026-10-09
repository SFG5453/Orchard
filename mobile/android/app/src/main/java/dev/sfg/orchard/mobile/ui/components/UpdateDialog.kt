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

package dev.sfg.orchard.mobile.ui.components

import androidx.compose.runtime.Composable
import dev.sfg.orchard.mobile.MobileUpdateMetadata
import dev.sfg.orchard.mobile.UpdateState

/**
 * Modern in-app update prompt matching Orchard Desktop's changelog dialog experience.
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    onInstall: (MobileUpdateMetadata) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        UpdateState.Idle -> Unit

        is UpdateState.Available -> UpdateAvailableDialog(state, onInstall, onDismiss)

        is UpdateState.Downloading -> UpdateDownloadingDialog(state, onDismiss)

        is UpdateState.Failed -> UpdateFailedDialog(state, onDismiss)

        is UpdateState.ReadyToInstall -> UpdateReadyDialog(state, onDismiss)
    }
}
