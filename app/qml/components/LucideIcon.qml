/* Lucide icon wrapper. See assets/lucide/LICENSE.txt. */

import QtQuick

Item {
    id: root

    property string name: ""
    property color color: "#f2f0e9"

    implicitWidth: 20
    implicitHeight: 20

    // The provider tints on the CPU, so each icon is a plain cached texture with no shader pass.
    Image {
        anchors.fill: parent
        source: root.name
            ? "image://lucide/" + root.name + "?" + Qt.rgba(root.color.r, root.color.g, root.color.b, 1).toString().slice(1)
            : ""
        sourceSize.width: root.width
        sourceSize.height: root.height
        fillMode: Image.PreserveAspectFit
        smooth: true
        mipmap: true
        opacity: root.color.a
    }
}
