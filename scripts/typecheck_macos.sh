#!/bin/zsh
set -euo pipefail

cd "$(dirname "$0")/.."
SDK="$(xcrun --show-sdk-path --sdk macosx)"

swiftc -typecheck \
  -sdk "$SDK" \
  -target arm64-apple-macosx14.0 \
  App/*.swift \
  Domain/*.swift \
  Features/ConnectionSetup/*.swift \
  Features/PhotoBrowser/*.swift \
  Features/Downloads/*.swift \
  Features/Settings/*.swift \
  Features/Shared/*.swift \
  Infrastructure/*.swift \
  Services/*.swift
