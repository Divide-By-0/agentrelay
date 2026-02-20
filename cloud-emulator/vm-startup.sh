#!/bin/bash
# Startup script that runs on the GCP VM to install Android SDK + emulator.
# This is passed as --metadata-from-file startup-script= when creating the VM.
set -euo pipefail

MARKER="/opt/android-emulator-ready"
if [[ -f "$MARKER" ]]; then
    echo "Android emulator already provisioned, skipping setup."
    exit 0
fi

export DEBIAN_FRONTEND=noninteractive

apt-get update -qq
apt-get install -y -qq \
    openjdk-17-jdk-headless \
    unzip \
    wget \
    cpu-checker \
    qemu-kvm \
    libvirt-daemon-system \
    bridge-utils \
    pulseaudio \
    > /dev/null

SDK_ROOT="/opt/android-sdk"
mkdir -p "$SDK_ROOT/cmdline-tools"

CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
wget -q "$CMDLINE_TOOLS_URL" -O /tmp/cmdline-tools.zip
unzip -q /tmp/cmdline-tools.zip -d "$SDK_ROOT/cmdline-tools"
mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
rm /tmp/cmdline-tools.zip

export ANDROID_HOME="$SDK_ROOT"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$SDK_ROOT/emulator:$PATH"

cat > /etc/profile.d/android-sdk.sh << 'ENVEOF'
export ANDROID_HOME="/opt/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
ENVEOF

yes | sdkmanager --licenses > /dev/null 2>&1 || true
sdkmanager --install \
    "platform-tools" \
    "emulator" \
    "platforms;android-34" \
    "system-images;android-34;google_apis;x86_64" \
    > /dev/null 2>&1

echo "no" | avdmanager create avd \
    --name "agentrelay" \
    --package "system-images;android-34;google_apis;x86_64" \
    --device "pixel_6" \
    --force

mkdir -p /root/.android
# Append hardware config to the AVD — do NOT overwrite, as avdmanager generates
# architecture and image path entries that are required for the emulator to start.
cat >> /root/.android/avd/agentrelay.avd/config.ini << 'AVDEOF'
hw.ramSize=4096
hw.cpu.ncore=2
disk.dataPartition.size=8G
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.keyboard=yes
hw.lcd.density=420
hw.lcd.width=1080
hw.lcd.height=2400
AVDEOF

cat > /etc/systemd/system/android-emulator.service << 'SVCEOF'
[Unit]
Description=Android Emulator (headless)
After=network.target

[Service]
Type=simple
Environment="ANDROID_HOME=/opt/android-sdk"
Environment="PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/emulator:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
Environment="ANDROID_AVD_HOME=/root/.android/avd"
ExecStart=/opt/android-sdk/emulator/emulator \
    -avd agentrelay \
    -no-window \
    -no-audio \
    -no-boot-anim \
    -gpu swiftshader_indirect \
    -no-snapshot \
    -port 5554 \
    -grpc 8554
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
SVCEOF

systemctl daemon-reload
systemctl enable android-emulator.service
systemctl start android-emulator.service

chmod a+rw /dev/kvm 2>/dev/null || true

touch "$MARKER"
echo "Android emulator setup complete."
