#!/usr/bin/env bash
# CLI Utility to compare local extension source against upstream/main

if [ -z "$1" ]; then
    echo "Usage: ./diff_extension.sh <extension_path_or_name>"
    echo "Example: ./diff_extension.sh src/ar/mangaswat"
    echo "Example: ./diff_extension.sh mangaswat"
    exit 1
fi

TARGET="$1"

# Normalize path
if [[ ! "$TARGET" =~ ^src/ ]]; then
    MATCH=$(find src/ -maxdepth 2 -type d -name "$TARGET" 2>/dev/null | head -n 1)
    if [ -n "$MATCH" ]; then
        TARGET="$MATCH"
    else
        echo "Could not find extension directory for: $TARGET"
        exit 1
    fi
fi

echo "=================================================="
echo "Comparing $TARGET against upstream/main"
echo "=================================================="

git diff --stat upstream/main -- "$TARGET"
echo ""
git diff upstream/main -- "$TARGET"
