#!/usr/bin/env bash
# Quick-run script for local development
set -euo pipefail
cd "$(dirname "$0")"
SMAGENT_ADMIN_PASSWORD='dev-password-123' ./gradlew bootRun "$@"
