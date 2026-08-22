#!/usr/bin/env sh
# Lightweight launcher for APK Factory / GitHub Actions.
# GitHub Actions installs Gradle 8.10.2 before this script is used.
exec gradle "$@"
