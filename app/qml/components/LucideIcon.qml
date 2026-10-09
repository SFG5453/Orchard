/* Lucide icon wrapper. See assets/lucide/LICENSE.txt. */

import QtQuick
import QtQuick.Effects

Item {
    id: root

    property string name: ""
    property color color: "#f2f0e9"

    implicitWidth: 20
    implicitHeight: 20

    // Why hardcode QRC paths when Qt can just look out the window and see the assets folder?
    Image {
        id: glyph

        anchors.fill: parent
        source: root.name ? ("../assets/lucide/" + root.name + ".svg") : ""
        sourceSize.width: root.width
        sourceSize.height: root.height
        fillMode: Image.PreserveAspectFit
        smooth: true
        mipmap: true
        visible: false
    }

    // The SVGs are stroked white, so colorizing maps white straight onto root.color.
    // Dark icons on light buttons finally exist. They were there all along, just shy.
    MultiEffect {
        anchors.fill: parent
        source: glyph
        colorization: 1
        colorizationColor: Qt.rgba(root.color.r, root.color.g, root.color.b, 1)
        opacity: root.color.a
    }
}
