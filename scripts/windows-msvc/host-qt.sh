# Matching Linux tools for the Windows Qt kit. Sourced by env.sh.

orchard_host_qt_version() {
    local config="$1/lib/cmake/Qt6Core/Qt6CoreConfigVersionImpl.cmake"
    [[ -f "$config" ]] || return 0
    sed -n 's/^set(PACKAGE_VERSION "\([0-9][0-9.]*\)")/\1/p' "$config" | head -n 1
}

orchard_aqt() {
    if command -v uvx >/dev/null 2>&1; then
        uvx --from "$ORCHARD_AQT_SPEC" aqt "$@"
    elif command -v pipx >/dev/null 2>&1; then
        pipx run --spec "$ORCHARD_AQT_SPEC" aqt "$@"
    else
        orchard_die "uv or pipx is required to download Qt"
    fi
}

orchard_host_qt_explicit="${ORCHARD_QT_HOST_PATH:+1}"
if [[ -z "${ORCHARD_QT_HOST_PATH:-}" ]]; then
    if [[ "$(orchard_host_qt_version /usr)" == "$ORCHARD_QT_VERSION" ]]; then
        ORCHARD_QT_HOST_PATH=/usr
    else
        ORCHARD_QT_HOST_PATH="$ORCHARD_WIN_TOOLS/host-qt/$ORCHARD_QT_VERSION/gcc_64"
    fi
fi

orchard_ensure_host_qt() {
    local version
    version="$(orchard_host_qt_version "$ORCHARD_QT_HOST_PATH")"
    if [[ -z "$version" && -z "$orchard_host_qt_explicit" ]]; then
        mkdir -p "$ORCHARD_WIN_TOOLS"
        echo "Installing Linux Qt $ORCHARD_QT_VERSION tools for the Windows build"
        (cd "$ORCHARD_WIN_TOOLS" && orchard_aqt install-qt linux desktop \
            "$ORCHARD_QT_VERSION" linux_gcc_64 -m qtshadertools \
            --archives qtbase qtdeclarative icu -O "$ORCHARD_WIN_TOOLS/host-qt")
        version="$(orchard_host_qt_version "$ORCHARD_QT_HOST_PATH")"
    fi
    [[ "$version" == "$ORCHARD_QT_VERSION" ]] ||
        orchard_die "host Qt ${version:-missing} at $ORCHARD_QT_HOST_PATH differs from target Qt $ORCHARD_QT_VERSION; set ORCHARD_QT_HOST_PATH to a matching host SDK"
    export ORCHARD_QT_HOST_PATH
    export PATH="$ORCHARD_QT_HOST_PATH/libexec:$ORCHARD_QT_HOST_PATH/bin:$ORCHARD_QT_HOST_PATH/lib/qt6:$ORCHARD_QT_HOST_PATH/lib/qt6/bin:$PATH"
    export PKG_CONFIG_PATH="$ORCHARD_QT_HOST_PATH/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
}
