// Запускается после "npx cap add android": кладёт Java-файлы и правит манифест и gradle.
const fs = require('fs');
const path = require('path');

const pkg = 'com.playerplus.app';
if (!fs.existsSync('android')) {
  console.error('Папка android не найдена. Сначала выполните: npx cap add android');
  process.exit(1);
}

// 1. Java-файлы
const javaDir = path.join('android', 'app', 'src', 'main', 'java', ...pkg.split('.'));
fs.mkdirSync(javaDir, { recursive: true });
for (const f of ['MainActivity.java', 'NativePlaybackPlugin.java', 'PlaybackService.java']) {
  fs.copyFileSync(path.join('native', f), path.join(javaDir, f));
}

// 2. AndroidManifest.xml
const mp = path.join('android', 'app', 'src', 'main', 'AndroidManifest.xml');
let m = fs.readFileSync(mp, 'utf8');
if (!m.includes('PlaybackService')) {
  const perms = [
    'android.permission.FOREGROUND_SERVICE',
    'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK',
    'android.permission.POST_NOTIFICATIONS',
  ].map(p => `    <uses-permission android:name="${p}" />`).join('\n');
  const service = `
        <service
            android:name=".PlaybackService"
            android:exported="true"
            android:foregroundServiceType="mediaPlayback">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaSessionService" />
            </intent-filter>
        </service>
`;
  m = m.replace('<application', perms + '\n\n    <application');
  m = m.replace('</application>', service + '    </application>');
  fs.writeFileSync(mp, m);
}

// 3. build.gradle
const gp = path.join('android', 'app', 'build.gradle');
let g = fs.readFileSync(gp, 'utf8');
if (!g.includes('media3')) {
  g = g.replace(/dependencies\s*\{/, 'dependencies {\n    implementation "androidx.media3:media3-exoplayer:1.4.1"\n    implementation "androidx.media3:media3-session:1.4.1"');
  fs.writeFileSync(gp, g);
}

console.log('Готово. Теперь: npx cap sync android, затем npm run open');
