#!/bin/bash

# Script to generate a release keystore for Android app signing
# This creates a new keystore and provides the base64 encoding needed for GitHub secrets

set -e

echo "=========================================="
echo "Android Keystore Generator"
echo "=========================================="
echo ""

# Get keystore details from user
read -p "Enter keystore password (minimum 6 characters): " STORE_PASSWORD
read -p "Enter key alias (e.g., tinydictation-key): " KEY_ALIAS
read -p "Enter key password (minimum 6 characters): " KEY_PASSWORD
echo ""

# Get certificate information
echo "Certificate Information:"
read -p "Your name (CN): " CN
read -p "Organizational unit (OU, optional): " OU
read -p "Organization (O, optional): " O
read -p "City (L, optional): " L
read -p "State (ST, optional): " ST
read -p "Country code (C, e.g., US): " C
echo ""

# Build the dname string
DNAME="CN=${CN}"
[ -n "$OU" ] && DNAME="${DNAME}, OU=${OU}"
[ -n "$O" ] && DNAME="${DNAME}, O=${O}"
[ -n "$L" ] && DNAME="${DNAME}, L=${L}"
[ -n "$ST" ] && DNAME="${DNAME}, ST=${ST}"
[ -n "$C" ] && DNAME="${DNAME}, C=${C}"

# Generate keystore
KEYSTORE_FILE="release.keystore"

echo "Generating keystore..."
keytool -genkeypair \
  -alias "${KEY_ALIAS}" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -keystore "${KEYSTORE_FILE}" \
  -storepass "${STORE_PASSWORD}" \
  -keypass "${KEY_PASSWORD}" \
  -dname "${DNAME}"

echo ""
echo "=========================================="
echo "Keystore generated successfully!"
echo "=========================================="
echo ""
echo "File: ${KEYSTORE_FILE}"
echo ""

# Generate base64 encoding
echo "Generating base64 encoding for GitHub secrets..."
KEYSTORE_BASE64=$(base64 -w 0 "${KEYSTORE_FILE}")

echo ""
echo "=========================================="
echo "GitHub Secrets Configuration"
echo "=========================================="
echo ""
echo "Add these secrets to your GitHub repository:"
echo "(Settings > Secrets and variables > Actions > New repository secret)"
echo ""
echo "1. KEYSTORE_BASE64"
echo "   Value:"
echo "   ${KEYSTORE_BASE64}"
echo ""
echo "2. KEYSTORE_PASSWORD"
echo "   Value: ${STORE_PASSWORD}"
echo ""
echo "3. KEY_ALIAS"
echo "   Value: ${KEY_ALIAS}"
echo ""
echo "4. KEY_PASSWORD"
echo "   Value: ${KEY_PASSWORD}"
echo ""
echo "=========================================="
echo "Local Development Setup"
echo "=========================================="
echo ""
echo "To use this keystore locally, run:"
echo ""
echo "export KEYSTORE_BASE64=\"${KEYSTORE_BASE64}\""
echo "export KEYSTORE_PASSWORD=\"${STORE_PASSWORD}\""
echo "export KEY_ALIAS=\"${KEY_ALIAS}\""
echo "export KEY_PASSWORD=\"${KEY_PASSWORD}\""
echo ""
echo "Then build with: ./gradlew assembleDebug"
echo ""
echo "=========================================="
echo "IMPORTANT: Keep these secrets safe!"
echo "=========================================="
echo ""
echo "- Store ${KEYSTORE_FILE} in a secure location"
echo "- NEVER commit the keystore file to git"
echo "- NEVER share your passwords publicly"
echo "- Back up your keystore - you cannot update your app without it!"
echo ""
