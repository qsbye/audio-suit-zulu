<p align="center">
  <img src="assets/waveform.png" alt="AudioSuitZulu" width="128" height="128">
</p>

# AudioSuitZulu 音函

[中文](#中文) | [English](#english)

---

## 中文

AudioSuitZulu(音函)是一个 Android 实时音频处理工具集,目前包含**扩音器**模块,未来计划加入**消音器**、变声等更多音频工具。

### 扩音器模块

把手机变成便携式扩音器:通过麦克风实时采集人声,推送到蓝牙音箱、有线音响或手机扬声器播放。适用于:

* 小型演讲、教室讲课
* 户外活动喊话
* 临时广播通知
* 没有专业扩音设备场合

### 功能

* 实时语音采集与播放(PCM 16bit / 44.1kHz 单声道流式传输)
* 按住录音、松开停止,按钮红色高亮显示 "Recording..." 状态
* 增益调节:-20 dB ~ +20 dB,正值放大音量,负值衰减输出以降低回声
* 双波形实时显示:灰色原始波形、绿色处理后波形,贝塞尔曲线平滑绘制
* 系统音量实时指示,静音时红色警告提示
* 运行时麦克风权限申请

### 界面说明

* **扩音器** — 当前模块标题
* **Record 按钮** — 按住开始扩音,松开停止;录音中变红色并显示 "Recording..."
* **音量指示** — 显示当前媒体音量(如 `音量: 7 / 15`)及进度条,静音时显示红色警告
* **增益滑条** — -20 dB ~ +20 dB,居中为 0 dB;右滑放大,左滑衰减(减轻回声)
* **波形图** — 灰色为原始波形,绿色为增益处理后波形,实时刷新

### 构建

要求:Android Studio(Gradle 8.7 + AGP 8.5.1,JDK 17),compileSdk 34,minSdk 24。

```bash
git clone https://github.com/iman-zamani/audio-suppression-zulu.git
cd audio-suppression-zulu
./gradlew :app:assembleDebug
```

### 使用

1. 手机连接蓝牙音箱或音响
2. 打开应用,授予麦克风权限
3. 按住 Record 按钮说话,松开停止
4. 根据需要调节增益滑条

### 回声说明

本应用未实现 DSP 级声学回声消除(AEC)。负增益通过衰减播放信号幅度来降低喇叭音量,从而减少被麦克风重新拾取的回声。对回声敏感的场景建议佩戴耳机,或让音箱远离手机。

### Roadmap / 计划

* [x] 扩音器模块
* [ ] 消音器模块(环境噪音抑制)
* [ ] 更多音频工具(变声、均衡器等)

### 注意

项目处于开发阶段,可能存在 bug 和稳定性问题,仅供测试与实验使用。

### 许可

MIT License,见 [LICENSE](LICENSE)。

---

## English

AudioSuitZulu is a collection of real-time audio processing tools for Android. It currently ships an **amplifier** module, with a **noise suppressor**, voice changer, and more tools planned.

### Amplifier Module

Turns your phone into a portable megaphone: it captures your voice through the microphone and streams it in real time to a Bluetooth speaker, wired audio system, or the phone's own loudspeaker. Useful for:

* Small presentations and classroom teaching
* Outdoor speaking
* Quick announcements
* Any situation without a dedicated PA system

### Features

* Real-time voice capture and playback (PCM 16bit / 44.1kHz mono streaming)
* Press-and-hold to talk, release to stop; the button highlights red with a "Recording..." state
* Adjustable gain from -20 dB to +20 dB: positive values amplify, negative values attenuate the output to reduce acoustic echo
* Live dual waveform display: raw waveform in gray, gain-processed waveform in green, both rendered with smooth Bezier curves
* Real-time system volume indicator with a red warning when muted
* Runtime microphone permission handling

### Interface

* **扩音器** — title of the current module
* **Record button** — press and hold to amplify, release to stop; turns red showing "Recording..." while active
* **Volume indicator** — current media volume (e.g. `音量: 7 / 15`) with a progress bar; a red warning appears when muted
* **Gain slider** — -20 dB to +20 dB, centered at 0 dB; drag right to amplify, left to attenuate (reduces echo)
* **Waveform view** — gray is the raw input, green is the processed signal, refreshed in real time

### Build

Requirements: Android Studio (Gradle 8.7 + AGP 8.5.1, JDK 17), compileSdk 34, minSdk 24.

```bash
git clone https://github.com/iman-zamani/audio-suppression-zulu.git
cd audio-suppression-zulu
./gradlew :app:assembleDebug
```

### Usage

1. Connect the phone to a Bluetooth speaker or audio system
2. Open the app and grant microphone permission
3. Press and hold the Record button to speak, release to stop
4. Adjust the gain slider as needed

### Echo Note

This app does not implement DSP-level acoustic echo cancellation (AEC). The negative gain control lowers the playback signal amplitude, which reduces speaker volume and therefore the amount of echo picked up by the microphone. For echo-critical scenarios, use a headset or keep the speaker away from the phone.

### Roadmap

* [x] Amplifier module
* [ ] Noise suppressor module
* [ ] More audio tools (voice changer, equalizer, etc.)

### Disclaimer

This project is under active development and may contain bugs or stability issues. Use it for testing and experimental purposes only.

### License

MIT License — see [LICENSE](LICENSE).
