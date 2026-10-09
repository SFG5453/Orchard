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
import QtQuick.Controls
import QtQuick.Layouts

// Lyric translation source, credentials, and downloaded local models.
SettingsSection {
    id: root

    property bool translationEnabled: true
    property string quality: "standard"
    property string provider: "local"
    property string modelName: ""
    property string endpoint: ""
    property bool hasApiKey: false
    property string credentialMessage: ""
    // Downloaded packs: { name, code, quality, sizeBytes, credit }.
    property var installed: []
    property alias enableSwitch: enableSwitch
    property alias qualityPicker: qualityPicker
    property alias providerPicker: providerPicker
    property alias modelField: modelField
    property alias endpointField: endpointField
    property alias keyField: keyField
    property alias saveKeyButton: saveKeyButton
    property alias removeKeyButton: removeKeyButton
    property alias packList: packList

    title: qsTr("Lyric translation")
    rowSpacing: 0
    verticalPadding: 0

    SettingsRow {
        iconName: "languages"
        title: qsTr("Translate lyrics to English")
        description: root.provider === "local"
            ? qsTr("Orchard detects each song's language and translates it on this computer. Lyrics never leave your computer.")
            : qsTr("Orchard sends lyric lines to your selected API for translation. Your provider may charge for requests.")

        SettingsSwitch {
            id: enableSwitch
            checked: root.translationEnabled
            Accessible.name: qsTr("Translate lyrics to English")
        }
    }

    SettingsRow {
        iconName: "plug"
        title: qsTr("Translation source")
        description: qsTr("Local models are free and private. Choose an API for another translation option.")

        SettingsSelect {
            id: providerPicker
            Layout.preferredWidth: 185
            model: [qsTr("Local"), qsTr("OpenAI"), qsTr("Claude"), qsTr("Gemini"), qsTr("Custom API")]
            currentIndex: ["local", "openai", "claude", "gemini", "custom"].indexOf(root.provider)
            Accessible.name: qsTr("Translation source")
        }
    }

    SettingsRow {
        visible: root.provider !== "local"
        iconName: "sparkles"
        title: qsTr("Model")
        description: qsTr("Enter a model ID supported by your API provider.")

        SettingsTextField {
            id: modelField
            Layout.preferredWidth: 260
            text: root.modelName
            placeholderText: qsTr("Model ID")
            Accessible.name: qsTr("Translation model ID")
        }
    }

    SettingsRow {
        visible: root.provider === "custom"
        iconName: "share-2"
        title: qsTr("API base URL")
        description: qsTr("Use an OpenAI compatible base URL, such as https://example.com/v1. Keyless HTTP APIs and localhost are also allowed.")

        SettingsTextField {
            id: endpointField
            Layout.preferredWidth: 300
            text: root.endpoint
            placeholderText: qsTr("https://example.com/v1")
            Accessible.name: qsTr("Translation API base URL")
        }
    }

    SettingsRow {
        visible: root.provider !== "local"
        iconName: "keyboard"
        title: qsTr("API key")
        description: root.hasApiKey ? qsTr("Saved in your operating system keychain.")
            : root.provider === "custom" ? qsTr("Optional for local APIs. Saved in your operating system keychain.")
            : qsTr("Required for this provider. Saved in your operating system keychain.")

        SettingsTextField {
            id: keyField
            Layout.preferredWidth: 220
            echoMode: TextInput.Password
            placeholderText: root.hasApiKey ? qsTr("Replace saved key") : qsTr("Paste API key")
            Accessible.name: qsTr("Translation API key")
        }
        SettingsButton {
            id: saveKeyButton
            text: qsTr("Save")
            enabled: keyField.text.trim().length > 0
        }
        SettingsButton {
            id: removeKeyButton
            visible: root.hasApiKey
            text: qsTr("Remove")
        }
    }

    SettingsHint {
        visible: root.credentialMessage.length > 0
        Layout.fillWidth: true
        text: root.credentialMessage
        color: "#e6a197"
    }

    SettingsRow {
        visible: root.provider === "local"
        iconName: "sparkles"
        title: qsTr("Translation quality")
        description: root.quality === "high"
            ? qsTr("Larger models with noticeably better lines. About 85 MB per language, roughly 8 seconds per song, and about 300 MB of memory while translating.")
            : qsTr("Small models that translate a song in a few seconds. About 20 MB per language and 75 MB of memory while translating. Lines are rough.")

        SettingsSegmented {
            id: qualityPicker
            currentValue: root.quality
            model: [
                { value: "standard", label: qsTr("Standard"), name: qsTr("Standard translation quality") },
                { value: "high", label: qsTr("High"), name: qsTr("High translation quality") }
            ]
        }
    }

    SettingsRow {
        visible: root.provider === "local" && root.installed.length === 0
        iconName: "hard-drive"
        title: qsTr("No language models downloaded")
        description: qsTr("Models download when a song needs one.")
    }

    // The logic layer swaps in a delegate that removes the pack.
    Repeater {
        id: packList
        model: root.provider === "local" ? root.installed : []

        SettingsRow {
            required property var modelData
            iconName: "hard-drive"
            title: modelData.quality === "high" ? qsTr("%1 model (High)").arg(modelData.name)
                                                : qsTr("%1 model").arg(modelData.name)
            description: qsTr("%1 on this computer. %2.").arg(qsTr("%1 MB").arg(Math.round(modelData.sizeBytes / 1e6))).arg(modelData.credit)

            SettingsButton {
                text: qsTr("Remove")
            }
        }
    }
}
