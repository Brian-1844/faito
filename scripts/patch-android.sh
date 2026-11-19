#!/usr/bin/env bash
# Ajoute le code natif (mise à jour intégrée) au projet Android généré par Capacitor.
set -e
VERSION="${VERSION:-1}"
JAVA=android/app/src/main/java/pf/brian/faito
mkdir -p "$JAVA" android/app/src/main/res/xml
cp native/MainActivity.java native/ApkUpdaterPlugin.java "$JAVA/"
cp native/file_paths.xml android/app/src/main/res/xml/file_paths.xml

M=android/app/src/main/AndroidManifest.xml
if ! grep -q REQUEST_INSTALL_PACKAGES "$M"; then
  sed -i 's#</manifest>#    <uses-permission android:name="android.permission.CAMERA" />\n    <uses-permission android:name="android.permission.RECORD_AUDIO" />\n    <uses-permission android:name="android.permission.VIBRATE" />\n    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />\n    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />\n    <uses-feature android:name="android.hardware.camera" android:required="false" />\n    <queries><intent><action android:name="android.intent.action.TTS_SERVICE" /></intent></queries>\n</manifest>#' "$M"
fi
grep -q REQUEST_INSTALL_PACKAGES "$M" || { echo "Échec du patch du manifeste"; exit 1; }

G=android/app/build.gradle
sed -i "s/versionCode [0-9]*/versionCode $VERSION/; s/versionName \"[^\"]*\"/versionName \"1.0.$VERSION\"/" "$G"
grep -q "versionCode $VERSION" "$G" || { echo "Échec du numéro de version"; exit 1; }
echo "Android prêt — version $VERSION"
