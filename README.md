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
* 增益调节:-20 dB ~ +20 dB(整数档位),正值放大音量,负值衰减输出
* **回声消除(AEC)**:纯 Kotlin NLMS 自适应滤波器(512 阶)+ Geigel 双讲检测,勾选框可随时开关
* **防啸叫**:FFT 窄带能量增长检测 + 最多 6 个自适应 IIR 陷波(notch)自动打断声反馈环,勾选框可随时开关
* 双波形实时显示:大地灰原始波形、橄榄绿处理后波形,贝塞尔曲线平滑绘制
* Android:系统媒体音量实时指示,静音时赤陶土色警告;桌面端:实时麦克风输入电平条
* 运行时麦克风权限申请(Android)
* 大地色系固定主题:土褐、卡其、奶茶、大地灰、赤陶土、橄榄绿、湖水蓝等低饱和自然色

### 界面说明

* **扩音器 / 关于** — 顶部两个标签页
* **Record 按钮** — 按住开始扩音,松开停止;土褐色按钮在录音中变为赤陶土色
* **音量 / 电平指示** — Android 显示当前媒体音量(如 `音量: 7 / 15`)及进度条,静音时显示警告;桌面端显示实时输入电平百分比
* **增益滑条** — -20 dB ~ +20 dB,居中为 0 dB;右滑放大,左滑衰减
* **回声消除 / 防啸叫勾选框** — 位于增益滑条下方,默认开启,录音过程中可随时勾选/取消;切换瞬间自动复位滤波器与陷波状态,避免爆音
* **波形图** — 大地灰为原始波形,橄榄绿为处理后波形(回声消除 → 防啸叫 → 增益),实时刷新

### 项目结构

```
.
├── app/       # Android 应用模块(:app,Jetpack Compose)
└── desktop/   # macOS 桌面模块(:desktop,Compose Multiplatform + javax.sound)
```

两个目标共享同一套声明式 UI 设计与大地色主题,并各自带有一份相同实现的纯 Kotlin DSP(无第三方音频库);音频采集在 Android 上使用 `AudioRecord`/`AudioTrack`,在桌面上使用 JDK 自带的 `javax.sound.sampled`。信号处理管线为:

```
麦克风 → 回声消除(NLMS) → 防啸叫(自适应陷波) → 增益 → 扬声器
```

DSP 代码位于 `app/.../dsp/` 与 `desktop/.../dsp/`:`NlmsAec.kt`(NLMS + Geigel 双讲检测)、`HowlingSuppressor.kt`(256 点 FFT 啸叫检测 + IIR 陷波组)。

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
4. 根据需要调节增益滑条,并用勾选框开关「回声消除」与「防啸叫」

### 回声消除与防啸叫说明

应用内置两个**纯软件**实时 DSP 模块(算法参考见 `docs/` 目录),默认开启,可通过勾选框关闭:

* **回声消除(AEC)**:基于 NLMS(归一化最小均方)自适应滤波器,以实际送往扬声器的播放信号为参考,逐样本估计并减去从扬声器经房间耦合回麦克风的回声;附带 Geigel 双讲检测,本人说话时冻结滤波器系数防止发散。
* **防啸叫**:持续做 256 点 FFT 频谱分析,当某窄带频率能量连续多帧指数增长(啸叫特征)时,自动在该频率放置 IIR 陷波器打断反馈环,啸叫消失约 0.5 秒后陷波平滑淡出。

局限:软件 AEC 主要抑制线性回声(直达声与早期反射),对非线性失真和强混响的尾部回声效果有限,滤波器收敛需要约 1~2 秒,整体效果不及手机硬件 HAL 级 AEC。对回声极度敏感的场景仍建议佩戴耳机,或让音箱远离麦克风;高增益扩音时建议两个功能同时开启。

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
* Adjustable gain from -20 dB to +20 dB (integer steps)
* **Acoustic echo cancellation (AEC)**: pure-Kotlin NLMS adaptive filter (512 taps) with Geigel double-talk detection, toggleable via checkbox
* **Howling suppression**: FFT-based narrow-band energy-growth detection plus up to 6 adaptive IIR notch filters that automatically break the acoustic feedback loop, toggleable via checkbox
* Live dual waveform display: raw waveform in earth gray, processed waveform in olive green, both rendered with smooth Bezier curves
* Android: real-time system media volume indicator with a terracotta warning when muted; Desktop: live microphone input level meter
* Runtime microphone permission handling (Android)
* Fixed Earth-Tone theme: dirt brown, khaki, beige tea, earth gray, terracotta, olive green, Canadian lake blue — soft, low-saturation natural colors

### Interface

* **扩音器 / 关于** — the two top tabs
* **Record button** — press and hold to amplify, release to stop; the dirt-brown button turns terracotta while recording
* **Volume / level indicator** — Android shows current media volume (e.g. `音量: 7 / 15`) with a progress bar and a warning when muted; desktop shows live input level percentage
* **Gain slider** — -20 dB to +20 dB, centered at 0 dB; drag right to amplify, left to attenuate
* **AEC / howling checkboxes** — below the gain slider, enabled by default, can be toggled at any time even while recording; the filter and notch states reset on toggle to avoid clicks
* **Waveform view** — earth gray is the raw input, olive green is the processed signal (AEC → howling suppression → gain), refreshed in real time

### Project Structure

```
.
├── app/       # Android app module (:app, Jetpack Compose)
└── desktop/   # macOS desktop module (:desktop, Compose Multiplatform + javax.sound)
```

Both targets share the same declarative UI design and Earth-Tone theme, and each ships an identical implementation of pure-Kotlin DSP (no third-party audio libraries). Audio capture uses `AudioRecord`/`AudioTrack` on Android and the JDK-bundled `javax.sound.sampled` on desktop. The processing pipeline is:

```
microphone → AEC (NLMS) → howling suppression (adaptive notch) → gain → speaker
```

The DSP code lives in `app/.../dsp/` and `desktop/.../dsp/`: `NlmsAec.kt` (NLMS + Geigel double-talk detection) and `HowlingSuppressor.kt` (256-point FFT howling detection + IIR notch bank).

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
4. Adjust the gain slider and use the checkboxes to enable/disable AEC and howling suppression

### AEC and Howling Suppression

The app includes two **pure-software** real-time DSP modules (see `docs/` for the reference material). Both are enabled by default and can be turned off via checkboxes:

* **Acoustic echo cancellation (AEC)**: an NLMS (Normalized Least Mean Squares) adaptive filter takes the signal actually sent to the speaker as its reference and estimates/subtracts sample-by-sample the echo coupled back from the speaker through the room. A Geigel double-talk detector freezes the filter coefficients while you speak directly, preventing divergence.
* **Howling suppression**: a 256-point FFT runs continuously; when narrow-band energy at a frequency grows exponentially across consecutive frames (the howling signature), an IIR notch filter is automatically placed at that frequency to break the feedback loop. Notches fade out smoothly about 0.5 s after the howling disappears.

Limitations: software AEC mainly suppresses linear echo (direct sound and early reflections). It is less effective against non-linear distortion and late reverb tails, the filter needs roughly 1–2 s to converge, and overall it does not match a phone's hardware HAL-level AEC. For highly echo-critical scenarios, use a headset or keep the speaker away from the microphone; keeping both features enabled is recommended at high gain.

### Roadmap

* [x] Amplifier module (Android)
* [x] macOS desktop fat jar build target
* [ ] Noise suppressor module
* [ ] More audio tools (voice changer, equalizer, etc.)

### Disclaimer

This project is under active development and may contain bugs or stability issues. Use it for testing and experimental purposes only.

### License

MIT License — see [LICENSE](LICENSE).
