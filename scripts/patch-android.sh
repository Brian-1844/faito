#!/usr/bin/env bash
# Ajoute le code natif (mise à jour intégrée) au projet Android généré par Capacitor.
set -e
VERSION="${VERSION:-1}"
JAVA=android/app/src/main/java/pf/brian/faito
mkdir -p "$JAVA" android/app/src/main/res/xml
cp native/MainActivity.java native/ApkUpdaterPlugin.java native/ArMeasureActivity.java "$JAVA/"
cp native/file_paths.xml android/app/src/main/res/xml/file_paths.xml

M=android/app/src/main/AndroidManifest.xml
if ! grep -q REQUEST_INSTALL_PACKAGES "$M"; then
  sed -i 's#</manifest>#    <uses-permission android:name="android.permission.CAMERA" />\n    <uses-permission android:name="android.permission.RECORD_AUDIO" />\n    <uses-permission android:name="android.permission.VIBRATE" />\n    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />\n    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />\n    <uses-feature android:name="android.hardware.camera" android:required="false" />\n    <queries><intent><action android:name="android.intent.action.TTS_SERVICE" /></intent></queries>\n</manifest>#' "$M"
fi
grep -q REQUEST_INSTALL_PACKAGES "$M" || { echo "Échec du patch du manifeste"; exit 1; }

# ARCore : écran de mesure AR natif
if ! grep -q ArMeasureActivity "$M"; then
  sed -i 's#</application>#    <activity android:name=".ArMeasureActivity" android:screenOrientation="portrait" android:exported="false" android:theme="@android:style/Theme.Black.NoTitleBar.Fullscreen" android:configChanges="orientation|screenSize" />\n        <meta-data android:name="com.google.ar.core" android:value="optional" />\n    </application>#' "$M"
  sed -i 's#</manifest>#    <uses-feature android:name="android.hardware.camera.ar" android:required="false" />\n</manifest>#' "$M"
fi
grep -q ArMeasureActivity "$M" || { echo "Échec de l'ajout de l'activité AR"; exit 1; }
sed -i 's/minSdkVersion = 2[0-3]/minSdkVersion = 24/' android/variables.gradle

G=android/app/build.gradle
grep -q "com.google.ar:core" "$G" || sed -i "0,/^dependencies {/s//dependencies {\n    implementation 'com.google.ar:core:1.57.0'/" "$G"
grep -q "com.google.ar:core" "$G" || { echo "Échec de l'ajout d'ARCore"; exit 1; }
sed -i "s/versionCode [0-9]*/versionCode $VERSION/; s/versionName \"[^\"]*\"/versionName \"1.0.$VERSION\"/" "$G"
grep -q "versionCode $VERSION" "$G" || { echo "Échec du numéro de version"; exit 1; }
echo "Android prêt — version $VERSION"
