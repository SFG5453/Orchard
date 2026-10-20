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

import QtQuick
import Orchard.Auth

// Sign-in browser on Linux. The helper never shows the QQuickView this sits in:
// WebKitView opens its own GTK window, sized and titled after that view.
Item {
    id: root

    property alias url: page.url
    property alias title: page.title
    property alias loading: page.loading
    property alias loadProgress: page.loadProgress
    property alias canGoBack: page.canGoBack

    signal loadFinished

    function runJavaScript(script, callback) {
        page.runJavaScript(script, callback)
    }
    function goBack() {
        page.goBack()
    }
    function reload() {
        page.reload()
    }

    WebKitView {
        id: page
        onLoadFinished: root.loadFinished()
    }
}
