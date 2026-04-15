#!/bin/bash

# --- CONFIGURATION ---
VERSION="5.0.8"
CODE=12
CHANGELOG="New feature: Automated Firebase + GitHub Update Sync."
FIREBASE_URL="https://aero5-886bf-default-rtdb.asia-southeast1.firebasedatabase.app/app_update.json"
APK_NAME="AERO5_v5.apk"
REMOTE_APK_URL="https://raw.githubusercontent.com/Dheeraj36229/aero5_app/main/$APK_NAME"

echo "🚀 Starting Automated Update Push v$VERSION..."

# 1. Update local versioning files
echo "📝 Updating build.gradle and index.html..."
sed -i "s/versionCode [0-9]*/versionCode $CODE/" app/build.gradle
sed -i "s/versionName \".*\"/versionName \"$VERSION\"/" app/build.gradle
sed -i "s/const CURRENT_VERSION = \".*\";/const CURRENT_VERSION = \"$VERSION\";/" app/src/main/assets/index.html

# 2. Build the APK
echo "🏗️ Building fresh APK..."
./gradlew app:assembleDebug

if [ $? -eq 0 ]; then
    cp app/build/outputs/apk/debug/app-debug.apk $APK_NAME

    # 3. Update Firebase via cURL
    echo "🔥 Syncing version to Firebase..."
    curl -X PUT -d "{\"latest_version\": \"$VERSION\", \"apk_url\": \"$REMOTE_APK_URL\"}" $FIREBASE_URL

    # 4. Push to GitHub
    echo "📦 Pushing to GitHub..."
    git add .
    git commit -m "Automated Release v$VERSION"
    git push origin main

    echo "✅ DONE! Version $VERSION is now live on GitHub and Firebase."
else
    echo "❌ Build failed. Update aborted."
fi
