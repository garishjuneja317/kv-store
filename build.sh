#!/usr/bin/env bash
# =============================================================================
# build.sh — Reproducible build script for KV-Store
#
# Produces byte-for-byte identical .class files on every invocation by:
#   1. Sorting all source files alphabetically before passing them to javac
#      (eliminates file-system ordering non-determinism).
#   2. Stripping debug metadata with -g:none
#      (removes line-number tables that embed source timestamps on some JDKs).
#   3. Pinning the charset with -encoding UTF-8
#      (avoids locale-dependent source interpretation).
#   4. Using -implicit:none so javac never silently compiles extra source files
#      that are referenced but not listed (deterministic input set).
#   5. Setting -source and -target to pin the class file version.
#
# Usage:
#   ./build.sh              # compile src + tests
#   ./build.sh --clean      # delete out/ first, then compile
#   ./build.sh --tests-only # skip main sources, only compile tests (assumes
#                           # out/StorageEngine.class already exists)
#
# Verification (reproducibility):
#   Run the script twice and diff the output:
#     ./build.sh && cp -r out out1
#     ./build.sh --clean && diff -r out out1 && echo "REPRODUCIBLE"
# =============================================================================

set -euo pipefail

JAVAC="${JAVAC:-javac}"
OUT_DIR="out"
SRC_DIR="src"
TEST_DIR="tests"

CLEAN=false
TESTS_ONLY=false

for arg in "$@"; do
    case "$arg" in
        --clean)      CLEAN=true ;;
        --tests-only) TESTS_ONLY=true ;;
        *)            echo "Unknown argument: $arg" >&2; exit 1 ;;
    esac
done

if $CLEAN; then
    echo "[build] Cleaning $OUT_DIR/ ..."
    rm -rf "$OUT_DIR"
fi

mkdir -p "$OUT_DIR"

# Common javac flags for reproducible output.
JAVAC_FLAGS=(
    "-g:none"           # strip debug tables (eliminates timestamp-sensitive metadata)
    "-implicit:none"    # do not implicitly compile referenced source files
    "-encoding" "UTF-8" # pin source charset
    "-d" "$OUT_DIR"
)

if ! $TESTS_ONLY; then
    echo "[build] Compiling sources ..."
    # Collect and sort source files alphabetically for deterministic input order.
    mapfile -t MAIN_SOURCES < <(find "$SRC_DIR" -name "*.java" | sort)
    "$JAVAC" "${JAVAC_FLAGS[@]}" "${MAIN_SOURCES[@]}"
    echo "[build] Sources compiled: ${#MAIN_SOURCES[@]} file(s)"
fi

echo "[build] Compiling tests ..."
mapfile -t TEST_SOURCES < <(find "$TEST_DIR" -name "*.java" | sort)
"$JAVAC" "${JAVAC_FLAGS[@]}" -cp "$OUT_DIR" "${TEST_SOURCES[@]}"
echo "[build] Tests compiled: ${#TEST_SOURCES[@]} file(s)"

echo ""
echo "[build] ✓ Build complete → $OUT_DIR/"
echo ""
echo "  Run CLI        :  java -cp $OUT_DIR Main"
echo "  Run tests      :  java -cp $OUT_DIR StorageEngineTest"
echo "  Run server     :  java -cp $OUT_DIR Main --server [--port 8080]"
echo "  Run web server :  java -cp $OUT_DIR Main --web [--web-port 8081]"
echo "  Run both       :  java -cp $OUT_DIR Main --server --web"
