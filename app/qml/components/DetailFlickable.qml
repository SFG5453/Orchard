import QtQuick
import QtQuick.Controls

Flickable {
    id: root
    clip: true
    boundsBehavior: Flickable.StopAtBounds
    // Room to scroll the last row out from under the floating player.
    readonly property real playerInset: Window.window && Window.window.playerInset !== undefined ? Window.window.playerInset : 0
    bottomMargin: playerInset
    ScrollBar.vertical: ScrollBar {
        bottomPadding: root.playerInset
        onPressedChanged: if (pressed) wheelAnimation.stop()
    }

    function scrollBy(delta, smooth = true) {
        if (delta === 0)
            return;
        if (smooth) {
            wheelAnimation.fling(-delta, true);
            return;
        }
        wheelAnimation.stop();
        const minimum = originY - topMargin;
        const maximum = Math.max(minimum, originY + contentHeight + bottomMargin - height);
        contentY = Math.max(minimum, Math.min(contentY - delta, maximum));
    }

    // Mouse wheel notches add momentum; touchpad updates follow the fingers.
    WheelHandler {
        id: wheelHandler
        target: null
        acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad
        onWheel: function(event) {
            const hasPixels = event.pixelDelta.x !== 0 || event.pixelDelta.y !== 0;
            const delta = hasPixels ? event.pixelDelta : event.angleDelta;
            if (Math.abs(delta.x) > Math.abs(delta.y)) {
                event.accepted = false;
                return;
            }
            const touchpad = wheelHandler.point.device
                    && wheelHandler.point.device.type === PointerDevice.TouchPad;
            root.scrollBy(hasPixels ? delta.y * 1.75 : delta.y,
                          !touchpad);
            event.accepted = true;
        }
    }
    WheelGlide {
        id: wheelAnimation
        view: root
    }
    onDraggingChanged: if (dragging) wheelAnimation.stop()
    onVisibleChanged: wheelAnimation.stop()
}
