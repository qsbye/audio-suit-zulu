# 参考资料

本目录收录回声消除（AEC）、主动噪声消除（ANC）、降噪（ANS/NS）相关的学习资料，均由本地保存的网页 HTML 经 [markitdown](https://github.com/microsoft/markitdown)（uv tool 安装）转换为 Markdown 并人工整理排版（自 2026-10-02 起陆续归档），文首标注原文来源。图片在原网页中为内联资源的，转换后以占位形式呈现；原图为远程链接的保留原链接，需要时请访问原文链接查看。

## 目录

| 文档 | 主题 | 来源 |
|---|---|---|
| [声学回声消除（AEC）原理与实现](aec-principle-and-implementation-cnblogs.md) | 回声产生机理、LMS/NLMS 自适应滤波、MATLAB 实现（FDAF、ERLE、分区降延迟）、开源音频库 | [博客园 · 凌逆战](https://www.cnblogs.com/LXP-Never/p/11703440.html) |
| [详解低延时高音质之回声消除与降噪](low-latency-aec-and-ans-agora.md) | 延迟估计、线性自适应滤波器、非线性处理、音质优先的降噪策略 | [声网 Agora](https://www.shengwang.cn/blog/blogdetail/aec-technical/) |
| [通讯软件的“回声消除”是如何运作的？](aec-in-communication-apps-reddit.md) | Teams/Discord/Zoom 的 AEC 工程实践、与主动降噪（ANC）的区别、可用的 VST 方案（Reddit 问答整理） | [r/audioengineering](https://www.reddit.com/r/audioengineering/comments/vdxgvr/how_do_communication_apps_echo_cancellation/) |
| [RNNoise: Learning Noise Suppression](rnnoise-learning-noise-suppression.md) | 经典信号处理 + 深度学习（GRU）结合的实时降噪原理与演示 | [Jean-Marc Valin / Xiph.Org](https://jmvalin.ca/demo/rnnoise/) |
| [主动噪声消除（ANC）算法研究（译文）](anc-algorithm-research-cnblogs.md) | 窄带/宽带前馈与反馈 ANC 系统、Wiener 滤波器、LMS/FxLMS/FxRLS 算法与 Python 仿真，含英文原文附录 | [博客园 · qsBye 译文](https://www.cnblogs.com/qsbye/p/18385474) |

## 与本项目的关系

AudioSuitZulu（音函）在麦克风实时扩音链路中可参考上述资料评估回声消除与降噪能力：

- **AEC** 解决扬声器声音回授麦克风导致的回声/啸叫，是扩音类应用的核心问题；
- **ANC**（主动噪声消除）面向环境噪声的相消干涉，FxLMS/FxRLS 是其自适应滤波核心算法，耳机等场景可参考；
- **RNNoise / WebRTC ANS** 等轻量方案可用于移动端实时降噪；
- 具体实现选型需综合考虑算法延迟、设备非线性失真与计算开销。
