#!/usr/bin/env bash
# Builds target/landing.jar from the sources, on the Clever Cloud machine.
# Declared in the application: CC_PRE_BUILD_HOOK=bash clevercloud/build.sh
#
# Clever Cloud provides Java. The Clojure CLI, and Node.js when missing, are installed in
# target-tools/, then the same commands as in development run.
set -euo pipefail

cd "$(dirname "$0")/.."

TOOLS="$PWD/target-tools"
NODE_VERSION="v24.11.1"

if ! command -v clojure >/dev/null 2>&1; then
  if [ ! -x "$TOOLS/clojure/bin/clojure" ]; then
    echo "Installing the Clojure CLI in $TOOLS/clojure"
    installer="$(mktemp)"
    curl -fsSL -o "$installer" https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
    bash "$installer" --prefix "$TOOLS/clojure"
    rm -f "$installer"
  fi
  export PATH="$TOOLS/clojure/bin:$PATH"
fi

if ! command -v npm >/dev/null 2>&1; then
  node_dir="$TOOLS/node-$NODE_VERSION-linux-x64"
  if [ ! -x "$node_dir/bin/npm" ]; then
    echo "Installing Node.js $NODE_VERSION in $TOOLS"
    mkdir -p "$TOOLS"
    curl -fsSL "https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-linux-x64.tar.xz" | tar -xJ -C "$TOOLS"
  fi
  export PATH="$node_dir/bin:$PATH"
fi

rm -rf resources/public/js/compiled
npm ci
npx shadow-cljs release app-admin
clojure -T:build uber
ls -lh target/landing.jar
