#!/data/data/com.lisaai/files/usr/bin/bash
# Repoint downloaded .deb packages from com.termux to com.lisaai before dpkg
# unpacks them. Installed by Lisa at $PREFIX/bin/lisa-repoint-debs.
#
# apt passes the .deb paths as arguments and/or on stdin via
# DPkg::Pre-Install-Pkgs, after it has verified the download but before dpkg
# reads the files.

set -u

PREFIX="${PREFIX:-/data/data/com.lisaai/files/usr}"
export PATH="$PREFIX/bin:/system/bin"

OLD="com.termux"
NEW="com.lisaai"

repoint_deb() {
    deb="$1"
    [ -f "$deb" ] || return 0
    command -v dpkg-deb >/dev/null 2>&1 || return 0

    work="$PREFIX/tmp/lisa-repoint.$$"
    out="$deb.lisa-new"
    rm -rf "$work"
    rm -f "$out"

    if ! mkdir -p "$work" 2>/dev/null; then
        return 0
    fi
    if ! dpkg-deb -R "$deb" "$work" >/dev/null 2>&1; then
        rm -rf "$work"
        return 0
    fi

    # Termux packages store files under ./data/data/com.termux/... inside the
    # archive, so the path components must be renamed too. -depth avoids
    # invalidating paths for entries we have not visited yet.
    find "$work" -depth -name "*$OLD*" -print0 2>/dev/null | while IFS= read -r -d '' p; do
        base="$(basename "$p")"
        case "$base" in
            *"$OLD"*)
                newbase="${base//$OLD/$NEW}"
                mv "$p" "$(dirname "$p")/$newbase" 2>/dev/null || true
                ;;
        esac
    done

    # Regular files: ELF binaries, scripts, maintainer metadata.
    find "$work" -type f -print0 2>/dev/null | while IFS= read -r -d '' f; do
        if LC_ALL=C grep -q 'com\.termux' "$f" 2>/dev/null; then
            LC_ALL=C sed -i 's/com\.termux/com.lisaai/g' "$f" 2>/dev/null || true
        fi
    done

    # Absolute symlink targets.
    find "$work" -type l -print0 2>/dev/null | while IFS= read -r -d '' l; do
        target="$(readlink "$l" 2>/dev/null)" || continue
        case "$target" in
            *"$OLD"*) ln -snf "${target//$OLD/$NEW}" "$l" 2>/dev/null || true ;;
        esac
    done

    # Build to a side file first so a failed build never destroys the
    # verified package apt handed us. Termux packages often store maintainer
    # scripts as 0644, but dpkg-deb -b requires an exec bit.
    for s in preinst postinst prerm postrm config; do
        if [ -f "$work/DEBIAN/$s" ]; then
            chmod 0755 "$work/DEBIAN/$s" 2>/dev/null || true
        fi
    done

    if dpkg-deb --root-owner-group -b "$work" "$out" >/dev/null 2>&1; then
        mv -f "$out" "$deb"
    else
        rm -f "$out"
        if dpkg-deb -b "$work" "$out" >/dev/null 2>&1; then
            mv -f "$out" "$deb"
        else
            rm -f "$out"
        fi
    fi
    rm -rf "$work"
}

if [ "$#" -gt 0 ]; then
    for arg in "$@"; do
        repoint_deb "$arg"
    done
else
    while IFS= read -r line; do
        [ -n "$line" ] && repoint_deb "$line"
    done
fi
