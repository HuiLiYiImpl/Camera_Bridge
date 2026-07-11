#!/bin/zsh
set -euo pipefail

cd "$(dirname "$0")/.."
xcodegen generate
echo "Generated NikonConnectIOS.xcodeproj"
