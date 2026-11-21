"""Prepare this checkout using the existing sibling POLTERHEIST Android toolchain.

No system PATH or global SDK changes. For other machines, use Android Studio/JDK 17.
"""
import hashlib
import pathlib
import shutil
import urllib.request

root = pathlib.Path(__file__).resolve().parents[1]
source = root.parent / 'POLTERHEIST/.cache/tv_control/android/tools'
sdk = root / '.tools/android-sdk'
for relative, destination in [('platform/android-35', 'platforms/android-35'),
                              ('build-tools/android-15', 'build-tools/35.0.0'),
                              ('adb/platform-tools', 'platform-tools')]:
    if not (sdk / destination).exists():
        shutil.copytree(source / relative, sdk / destination)
(root / 'local.properties').write_text('sdk.dir=' + sdk.as_posix() + '\n', encoding='utf-8')
base = 'https://raw.githubusercontent.com/gradle/gradle/v8.11.1/'
for name, remote in [('gradlew', 'gradlew'), ('gradlew.bat', 'gradlew.bat'),
                     ('gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.jar')]:
    target = root / name
    if not target.exists():
        urllib.request.urlretrieve(base + remote, target)
checksum = urllib.request.urlopen('https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256').read().decode().strip()
wrapper_hash = urllib.request.urlopen('https://services.gradle.org/distributions/gradle-8.11.1-wrapper.jar.sha256').read().decode().strip()
actual = hashlib.sha256((root / 'gradle/wrapper/gradle-wrapper.jar').read_bytes()).hexdigest()
if actual != wrapper_hash:
    raise RuntimeError('Gradle wrapper checksum mismatch')
(root / 'gradle/wrapper/gradle-wrapper.properties').write_text(
    'distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\n'
    'distributionUrl=https\\://services.gradle.org/distributions/gradle-8.11.1-bin.zip\n'
    f'distributionSha256Sum={checksum}\nnetworkTimeout=60000\nvalidateDistributionUrl=true\n'
    'zipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n', encoding='utf-8')
print('Local SDK and verified Gradle wrapper ready.')
