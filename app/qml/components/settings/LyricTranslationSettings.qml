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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import QtQuick

// Lyric translation settings. Layout lives in LyricTranslationSettingsForm.ui.qml.
LyricTranslationSettingsForm {
    id: root

    translationEnabled: OrchardTranslation.enabled
    quality: OrchardTranslation.quality
    provider: OrchardTranslation.provider
    modelName: OrchardTranslation.model
    endpoint: OrchardTranslation.endpoint
    hasApiKey: OrchardTranslation.hasApiKey
    credentialMessage: OrchardTranslation.credentialMessage
    installed: installedPacks()

    // Untyped so qmlcachegen leaves the QVariantList alone.
    function installedPacks() {
        const packs = OrchardTranslation.packs;
        return packs ? packs.filter(pack => pack.installed) : [];
    }

    enableSwitch.onToggled: OrchardTranslation.enabled = enableSwitch.checked
    qualityPicker.onPicked: value => OrchardTranslation.quality = value
    providerPicker.onActivated: index => {
        OrchardTranslation.provider = ["local", "openai", "claude", "gemini", "custom"][index];
        keyField.clear();
    }
    modelField.onEditingFinished: OrchardTranslation.model = modelField.text
    endpointField.onEditingFinished: OrchardTranslation.endpoint = endpointField.text
    saveKeyButton.onClicked: {
        OrchardTranslation.saveApiKey(keyField.text);
        keyField.clear();
    }
    removeKeyButton.onClicked: OrchardTranslation.removeApiKey()

    packList.delegate: SettingsRow {
        id: packRow
        required property var modelData
        iconName: "hard-drive"
        title: modelData.quality === "high" ? qsTr("%1 model (High)").arg(modelData.name)
                                            : qsTr("%1 model").arg(modelData.name)
        description: qsTr("%1 on this computer. %2.").arg(qsTr("%1 MB").arg(Math.round(modelData.sizeBytes / 1e6))).arg(modelData.credit)

        SettingsButton {
            text: qsTr("Remove")
            Accessible.name: qsTr("Remove the %1").arg(packRow.title)
            onClicked: OrchardTranslation.removePack(modelData.code)
        }
    }
}
