<p align="center">
  <img src="assets/waveform.png" alt="AudioSuitZulu" width="128" height="128">
</p>

# AudioSuitZulu 音函

[中文](#中文) | [English](#english)

---

## 中文

AudioSuitZulu(音函)是一个实时音频处理工具集,目前包含**扩音器**模块,提供 Android 手机应用与 macOS 桌面应用两个构建目标,未来计划加入**消音器**、变声等更多音频工具。

界面全部使用 [Jetpack Compose](https://developer.android.com/jetpack/compose)(Android) / [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/)(桌面)声明式构建,无任何 XML 布局;配色采用固定的**大地色系(Earth-Tone)**主题,不随系统明暗模式切换。

### 扩音器模块

把设备变成便携式扩音器:通过麦克风实时采集人声,推送到蓝牙音箱、有线音响或扬声器播放。适用于:

* 小型演讲、教室讲课
* 户外活动喊话
* 临时广播通知
* 没有专业扩音设备场合

### 功能

* 实时语音采集与播放(PCM 16bit / 44.1kHz 单声道流式传输)
* 按住录音、松开停止;录音中按钮变为赤陶土色并显示 "Recording..." 状态
* 增益调节:-20 dB ~ +20 dB(整数档位),正值放大音量,负值衰减输出以降低回声
* 双波形实时显示:大地灰原始波形、橄榄绿处理后波形,贝塞尔曲线平滑绘制
* Android:系统媒体音量实时指示,静音时赤陶土色警告;桌面端:实时麦克风输入电平条
* 运行时麦克风权限申请(Android)
* 大地色系固定主题:土褐、卡其、奶茶、大地灰、赤陶土、橄榄绿、湖水蓝等低饱和自然色

### 界面说明

* **扩音器 / 关于** — 顶部两个标签页
* **Record 按钮** — 按住开始扩音,松开停止;土褐色按钮在录音中变为赤陶土色
* **音量 / 电平指示** — Android 显示当前媒体音量(如 `音量: 7 / 15`)及进度条,静音时显示警告;桌面端显示实时输入电平百分比
* **增益滑条** — -20 dB ~ +20 dB,居中为 0 dB;右滑放大,左滑衰减(减轻回声)
* **波形图** — 大地灰为原始波形,橄榄绿为增益处理后波形,实时刷新

### 项目结构

```
.
├── app/       # Android 应用模块(:app,Jetpack Compose)
└── desktop/   # macOS 桌面模块(:desktop,Compose Multiplatform + javax.sound)
```

两个目标共享同一套声明式 UI 设计与大地色主题;音频采集在 Android 上使用 `AudioRecord`/`AudioTrack`,在桌面上使用 JDK 自带的 `javax.sound.sampled`(麦克风 → 增益 → 扬声器,无需第三方音频库)。

### 构建

要求:JDK 17、Gradle 8.7 + AGP 8.5.1,Android compileSdk 34、minSdk 24。

```bash
git clone https://github.com/qsbye/audio-suit-zulu.git
cd audio-suit-zulu
```

**Android APK:**

```bash
./gradlew :app:assembleDebug      # Debug APK: app/build/outputs/apk/debug/app-debug.apk
```

**macOS fat jar(同一 jar 同时支持 Apple Silicon 与 Intel):**

```bash
./gradlew :desktop:shadowJar
# 产物: desktop/build/libs/AudioSuitZulu-desktop-1.0.0-all.jar
java -jar desktop/build/libs/AudioSuitZulu-desktop-1.0.0-all.jar
```

fat jar 内已打包 macos-arm64 与 macos-x64 两套 Skiko 原生库,无需额外安装运行时(JDK 17+ 即可)。macOS 首次按下 Record 时需在系统弹窗中授予麦克风权限。

### 使用

1. 设备连接蓝牙音箱或音响(桌面端直接使用系统默认麦克风与扬声器)
2. 打开应用,授予麦克风权限
3. 按住 Record 按钮说话,松开停止
4. 根据需要调节增益滑条

### 回声说明

本应用未实现 DSP 级声学回声消除(AEC)。负增益通过衰减播放信号幅度来降低喇叭音量,从而减少被麦克风重新拾取的回声。对回声敏感的场景建议佩戴耳机,或让音箱远离麦克风。

### Roadmap / 计划

* [x] 扩音器模块(Android)
* [x] macOS 桌面 fat jar 构建目标
* [ ] 消音器模块(环境噪音抑制)
* [ ] 更多音频工具(变声、均衡器等)

### 注意

项目处于开发阶段,可能存在 bug 和稳定性问题,仅供测试与实验使用。

### 许可

MIT License,见 [LICENSE](LICENSE)。

---

## English

AudioSuitZulu is a collection of real-time audio processing tools. It currently ships an **amplifier** module with two build targets — an Android phone app and a macOS desktop app — with a **noise suppressor**, voice changer, and more tools planned.

The entire UI is built declaratively with [Jetpack Compose](https://developer.android.com/jetpack/compose) (Android) / [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) (desktop), with no XML layouts at all. It uses a fixed **Earth-Tone** color theme that does not switch with the system light/dark mode.

### Amplifier Module

Turns your device into a portable megaphone: it captures your voice through the microphone and streams it in real time to a Bluetooth speaker, wired audio system, or the built-in loudspeaker. Useful for:

* Small presentations and classroom teaching
* Outdoor speaking
* Quick announcements
* Any situation without a dedicated PA system

### Features

* Real-time voice capture and playback (PCM 16bit / 44.1kHz mono streaming)
* Press-and-hold to talk, release to stop; the button turns terracotta with a "Recording..." state while active
* Adjustable gain from -20 dB to +20 dB (integer steps): positive values amplify, negative values attenuate the output to reduce acoustic echo
* Live dual waveform display: raw waveform in earth gray, gain-processed waveform in olive green, both rendered with smooth Bezier curves
* Android: real-time system media volume indicator with a terracotta warning when muted; Desktop: live microphone input level meter
* Runtime microphone permission handling (Android)
* Fixed Earth-Tone theme: dirt brown, khaki, beige tea, earth gray, terracotta, olive green, Canadian lake blue — soft, low-saturation natural colors

### Interface

* **扩音器 / 关于** — the two top tabs
* **Record button** — press and hold to amplify, release to stop; the dirt-brown button turns terracotta while recording
* **Volume / level indicator** — Android shows current media volume (e.g. `音量: 7 / 15`) with a progress bar and a warning when muted; desktop shows live input level percentage
* **Gain slider** — -20 dB to +20 dB, centered at 0 dB; drag right to amplify, left to attenuate (reduces echo)
* **Waveform view** — earth gray is the raw input, olive green is the processed signal, refreshed in real time

### Project Structure

```
.
├── app/       # Android app module (:app, Jetpack Compose)
└── desktop/   # macOS desktop module (:desktop, Compose Multiplatform + javax.sound)
```

Both targets share the same declarative UI design and Earth-Tone theme. Audio capture uses `AudioRecord`/`AudioTrack` on Android and the JDK-bundled `javax.sound.sampled` on desktop (microphone → gain → speaker, no third-party audio libraries).

### Build

Requirements: JDK 17, Gradle 8.7 + AGP 8.5.1; Android compileSdk 34, minSdk 24.

```bash
git clone https://github.com/qsbye/audio-suit-zulu.git
cd audio-suit-zulu
```

**Android APK:**

```bash
./gradlew :app:assembleDebug      # Debug APK: app/build/outputs/apk/debug/app-debug.apk
```

**macOS fat jar (a single jar supporting both Apple Silicon and Intel):**

```bash
./gradlew :desktop:shadowJar
# Output: desktop/build/libs/AudioSuitZulu-desktop-1.0.0-all.jar
java -jar desktop/build/libs/AudioSuitZulu-desktop-1.0.0-all.jar
```

The fat jar bundles both macos-arm64 and macos-x64 Skiko native libraries and needs no extra runtime beyond JDK 17+. On macOS, grant microphone permission in the system prompt the first time you press Record.

### Usage

1. Connect the device to a Bluetooth speaker or audio system (the desktop app uses the system default microphone and speaker)
2. Open the app and grant microphone permission
3. Press and hold the Record button to speak, release to stop
4. Adjust the gain slider as needed

### Echo Note

This app does not implement DSP-level acoustic echo cancellation (AEC). The negative gain control lowers the playback signal amplitude, which reduces speaker volume and therefore the amount of echo picked up by the microphone. For echo-critical scenarios, use a headset or keep the speaker away from the microphone.

### Roadmap

* [x] Amplifier module (Android)
* [x] macOS desktop fat jar build target
* [ ] Noise suppressor module
* [ ] More audio tools (voice changer, equalizer, etc.)

### Disclaimer

This project is under active development and may contain bugs or stability issues. Use it for testing and experimental purposes only.

### License

MIT License — see [LICENSE](LICENSE).
