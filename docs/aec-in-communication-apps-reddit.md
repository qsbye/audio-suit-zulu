# 通讯软件的“回声消除”功能是如何运作的？（Teams/Discord/Zoom，与主动降噪的区别，VST 方案）

> **来源作者**：r/audioengineering（Reddit 中文翻译存档）  
> **原文链接**：https://www.reddit.com/r/audioengineering/comments/vdxgvr/how_do_communication_apps_echo_cancellation/  
> **发布时间**：2022-06（Reddit 原帖）  
> **归档时间**：2026-10-02（由本地保存的网页 HTML 经 markitdown 转换整理，图片为占位引用）

---

# 通讯软件的“回声消除”功能是如何运作的？比如在 Teams、Discord、Zoom 等应用中。它和主动降噪类似吗？有没有 VST 可以做同样的事情？

像 Zoom、Teams、Discord 这样的应用中的回声消除功能是如何运作的，它们能够分辨来自电话呼叫或整个电脑的音频，从而“消除”声音，这样说话的人就不会在半秒后听到自己的声音反馈到麦克风里。有时候，它似乎还能检测到从 YouTube 视频等背景中播放的音频，并将其从输入录音中消除，但如果这个人想说话，他们就可以说话，而 YouTube 视频听起来要么无法检测到，要么音量和（声音内容？）大大降低。 就像它被智能地移除了一样。

它只是静音了没有说话的人的麦克风吗？如果这变得令人沮丧，并且它让某人可以盖过另一个人说话，因为一个人可以说话而静音另一个人呢？有没有办法分辨是否出现了独特的语音，这与从电话呼叫的扬声器中播放的语音不同？然后，当另一个人说话时简单的静音功能就可以工作，如果它检测到当前被静音的人刚开始说话，它可能会切换一个开关，或者使用某种振幅均方根加权系统来确定让谁说话和静音谁。

谢谢，只是想尝试消除来自不支持原生回声消除功能的应用程序中朋友的反馈。

帖子已归档。无法发布新评论，并且无法进行投票。

11

[![u/CodeRabbitAI 头像](data:image/webp;base64...)
CodeRabbitAI](https://www.reddit.com/user/CodeRabbitAI/)
•
[广告](https://www.reddit.com/user/CodeRabbitAI/)

- 隐藏
- 举报
- 关于此广告
- [厌倦了广告？](https://www.reddit.com/premium?referrerId=ad_overflow)

CodeRabbit, AI-powered pull request reviews. Learn more.

查看更多内容

coderabbit.ai

![Thumbnail image: CodeRabbit, AI-powered pull request reviews. Learn more.](data:image/webp;base64...)

[![u/PS-Brands 头像](https://www.redditstatic.com/avatars/defaults/v2/avatar_default_7.png)
u/PS-Brands](/user/PS-Brands/)
•
[广告](/user/PS-Brands/)

PhotoShelter: The DAM that’s self-care for creatives.

了解更多信息

go.photoshelter.com

![Thumbnail image: PhotoShelter: The DAM that’s self-care for creatives.](https://preview.redd.it/0gxxh0oyghoh1.png?width=320&height=320&auto=webp&s=7f33cce8e44be79c357a54ffb8095d2d71705189)

[![u/GamemasterAudio 头像](https://www.redditstatic.com/avatars/defaults/v2/avatar_default_6.png)
u/GamemasterAudio](/user/GamemasterAudio/)
•
[广告](/user/GamemasterAudio/)

Sound effects built for indie devs.

了解更多信息

gamemasteraudio.com

![Thumbnail image: Sound effects built for indie devs.](https://preview.redd.it/6ymbkxedcesh1.png?width=320&height=320&auto=webp&s=9bcd66d56b19542e894fad1b726f2623a022777b)

排序方式：
           最佳

最佳

打开评论排序选项

- 最佳
- 点赞最多
- 新
- 有争议
- 旧
- 问答

搜索评论   展开评论搜索              清除搜索     取消

# 评论区域

[![u/SkoomaDentist 头像](data:image/png;base64...)](https://www.reddit.com/user/SkoomaDentist/)

[SkoomaDentist](https://www.reddit.com/user/SkoomaDentist/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icnm8bh/?tl=zh-hans)

Audio Hardware

大多数应用中，回声消除是在远程进行的，这样就可以从麦克风中消除来自扬声器的信号。另一种方式也是可行的，但由于网络延迟的变化以及音频压缩引起的问题（即使压缩后的波形在主观上听起来相同，但已不再与原始波形相似），这种方式更加棘手。

该算法估计大致的延迟，然后将延迟的信号输入自适应滤波器，并从麦克风输入中减去该信号。自适应滤波器会不断更新，以最大限度地减少处理后的麦克风信号中扬声器信号的数量。

一个简单的版本是大学 DSP 课程中常见的作业/项目，可以在一两个小时内实现。

赞同    23           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/SkoomaDentist

取消

评论

[Mbinku](https://www.reddit.com/user/Mbinku/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/ict00wf/?tl=zh-hans)

•

 4年前
 编辑

用压缩音频的话，出现循环的情况就更少了，因为音频里的频率会更少。这都跟相位有关：如果没有相位对齐，那么循环就不会导致增益增加，因为相位会相互抵消。

据我所知，反馈检测的工作原理是，通过匹配频率，来确定每次增益增加之间的时间间隔。每个频率都有一个独特的相位峰值之间的时间长度（计算机通过样本长度来衡量，这与采样率有关）。

举个例子，1khz的频率每1毫秒就会达到峰值。当反馈检测识别出1khz的音量每1毫秒都在增加时，它就会应用一个陷波滤波器来将其从信号中移除——然后，搞定。反馈循环被打断了。

赞同    1           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/Mbinku

取消

评论

[更多回复](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icnm8bh/?tl=zh-hans&force-legacy-sct=1)

[jumpofffromhere](https://www.reddit.com/user/jumpofffromhere/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icnx473/?tl=zh-hans)

我一直在用和安装罗技 Rally 系统，它们用一个 AEC 系统来输入和输出，刚开始用的时候挺有意思的，听着它花一两秒钟处理所有事情，回声越来越短，它用波束成形技术来处理麦克风，会主动寻找房间里最大的声音并隔离它，即使你走来走去也没问题。如果你用摄像头进行视频会议，摄像头会使用房间里的动作检测，然后放大移动的物体，如果桌子另一边的人说话，它就会缩小画面，很酷的东西，我自己还在学习呢。

赞同    6           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/jumpofffromhere

取消

评论

[![u/audio_shinobi 头像](data:image/png;base64...)](https://www.reddit.com/user/audio_shinobi/)

[audio\_shinobi](https://www.reddit.com/user/audio_shinobi/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icn466j/?tl=zh-hans)

所以，每个 AEC 算法都会略有不同，这取决于哪个开发者编写的程序，但总体的想法是，它会获取麦克风输入，然后将其同时路由到远端，但也路由到本地输出，只是相位反转，这样它就可以通过相位抵消来消除回声。

这比这要复杂得多，但这大致就是 AEC 的工作原理。

降噪是另一个算法，但我认为它会与 AEC 引擎交互。

赞同    21           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/audio\_shinobi

取消

评论

[![u/cluq 头像](data:image/png;base64...)](https://www.reddit.com/user/cluq/)

[cluq](https://www.reddit.com/user/cluq/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icnauu4/?tl=zh-hans)

酷！你确定是这么搞的，还是你瞎猜的？

赞同    6           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/cluq

取消

评论

[bassfingerz](https://www.reddit.com/user/bassfingerz/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icnosq2/?tl=zh-hans)

讴歌（Acura）用它来降低发动机噪音。都是基于相位反转的。

<https://www.autobytel.com/car-ownership/technology/what-is-acura-active-sound-control-125813/>

赞同    2           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/bassfingerz

取消

评论

[![u/audio_shinobi 头像](data:image/png;base64...)](https://www.reddit.com/user/audio_shinobi/)

[audio\_shinobi](https://www.reddit.com/user/audio_shinobi/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icnbz2o/?tl=zh-hans)

我觉得都有点儿吧。这里面门道儿多着呢，而且我才刚开始摸到点儿皮毛，了解它怎么运作的，相位抵消绝对是AEC的关键。

赞同    2           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/audio\_shinobi

取消

评论

[更多回复](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icnauu4/?tl=zh-hans&force-legacy-sct=1)

[g\_spaitz](https://www.reddit.com/user/g_spaitz/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/iconijy/?tl=zh-hans)

通常情况下，本地麦克风的输入不会发送到扬声器/本地输出。这通常被认为是蠢事。你想要的是从麦克风输入中移除“away program”（本地用户必须听到的东西），所以需要取消的是“away program”，这样远端的听众就不会听到他自己的声音了。

耳机仍然是最好的选择，AEC（回声消除）技术正在变得越来越好。

赞同    1           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/g\_spaitz

取消

评论

[更多回复](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icn466j/?tl=zh-hans&force-legacy-sct=1)

[![u/PS-Brands 头像](data:image/png;base64...)
u/PS-Brands](https://www.reddit.com/user/PS-Brands/)
•
[广告](https://www.reddit.com/user/PS-Brands/)

- 隐藏
- 举报
- 关于此广告
- [厌倦了广告？](https://www.reddit.com/premium?referrerId=ad_overflow)

PhotoShelter: The DAM that’s self-care for creatives.

了解更多信息

go.photoshelter.com

![Thumbnail image: PhotoShelter: The DAM that’s self-care for creatives.](data:image/webp;base64...)

[jake\_burger](https://www.reddit.com/user/jake_burger/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icopzkk/?tl=zh-hans)

Sound Reinforcement

回声消除和噪声抑制稍微有点不一样（至少在Zoom里是这样）。

回声消除是利用每个呼叫者的传入/传出音频流来判断传入麦克风的声音是本地的还是非本地的，以防止反馈循环。我不太清楚这具体是怎么实现的，但我想这会根据平台而有所不同，并且涉及到大量的动态处理，或者可能像Dugan这样的自动混音器。 它的确会在别人说话的时候让你静音，反正我经历过。

另一方面，噪声抑制使用几种不同的算法来确定你的麦克风输入中什么是语音和音乐并保留它们，什么是来自汽车、宠物和风扇等的噪声并尝试去除它们。

如果你想重现其中的一些功能，我会建议从让人们使用“按住说话”开始，麦克风开得少意味着反馈就少。

做一些噪声抑制更难，因为它需要大量的处理器资源并且是专有的（我认为Zoom是在他们的服务器上远程完成的），但是像Izotope RX noise这样的东西可以很好地处理持续的噪声（风扇）。

Nvidea的噪声抑制对于实时、算法语音检测来说似乎真的很酷（可以去除像宠物这样的非持续噪声），但我认为你需要他们的RTX显卡才能很好地运行它。

赞同    2           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/jake\_burger

取消

评论

[MDHull\_fixer](https://www.reddit.com/user/MDHull_fixer/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icq5685/?tl=zh-hans)

Professional

可能比你想知道的还多

[QSC：AEC 是怎么运作的](https://www.youtube.com/watch?v=bJKGrheOoY4&feature=emb_imp_woyt)

[Biamp：声学回声消除](https://www.youtube.com/watch?v=6heSUZqcVBE)

赞同    2           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/MDHull\_fixer

取消

评论

[![u/fxhndav 头像](data:image/webp;base64...)](https://www.reddit.com/user/fxhndav/)

[fxhndav](https://www.reddit.com/user/fxhndav/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icn3s5f/?tl=zh-hans)

采样一下背景噪音/轮廓，然后播放一个与该信号完全相同的、相位反转180°的信号，叠加在你的麦克风信号上。播放两个完全相同的音频文件（或者在这种情况下，是背景噪音/嗡嗡声/房间声音的采样，现在AI在一些应用程序中实时确定这些东西做得相当好），一起播放，但其中一个的相位反转，就会抵消掉那些烦人的声音，对于普通用户来说，留下的信号会干净很多。

那些很贵的入耳式耳机也用内置的小处理器电路做同样的事情，这技术真牛。我只是简单地看了下楼主说的，希望这有帮助。

赞同    5           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/fxhndav

取消

评论

[johnman1016](https://www.reddit.com/user/johnman1016/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icn8orj/?tl=zh-hans)

深度学习变得越来越常见了。

赞同    4           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/johnman1016

取消

评论

[karisigurd4444](https://www.reddit.com/user/karisigurd4444/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icncllw/?tl=zh-hans)

不行。不好。别说了。别到处乱跑说那些话。不好。

赞同    -4           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/karisigurd4444

取消

评论

[![u/djdementia 头像](data:image/png;base64...)](https://www.reddit.com/user/djdementia/)

[djdementia](https://www.reddit.com/user/djdementia/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icnpcwk/?tl=zh-hans)

•

 4年前
 编辑

[u/johnman1016](https://www.reddit.com/user/johnman1016/) 说得对，你已经落伍了。你现在就可以下载一个免费的 VST 插件，它可以实时去除背景噪音。它是在深度学习神经网络上训练的。

<https://jmvalin.ca/demo/rnnoise/>

> RNNoise 项目，展示了深度学习如何应用于噪声抑制。主要思想是将经典信号处理与深度学习相结合，创建一个小巧快速的实时噪声抑制算法。不需要昂贵的 GPU——它可以在 Raspberry Pi 上轻松运行。结果比传统的噪声抑制系统简单得多（更容易调整）并且听起来更好（亲身经历！）。

在这里免费下载： <https://github.com/werman/noise-suppression-for-voice/releases>

我把它和 [Lighthost](https://github.com/rolandoislas/LightHost) 和 [Voicemeeter](https://voicemeeter.com/) 结合使用，以实时消除我所有视频通话中的噪音。

不仅如此，而且“不 - 坏 - 停下”这种说法非常居高临下。你以为你在跟狗说话吗？

[你似乎在用居高临下的方式写作方面存在严重问题](https://old.reddit.com/r/programming/comments/v1h95f/the_mindless_tyranny_of_what_if_it_changes_as_a/iaprj9w/) 也许你应该在发帖前重新评估你的帖子。

赞同    13           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/djdementia

取消

评论

[karisigurd4444](https://www.reddit.com/user/karisigurd4444/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icoilp1/?tl=zh-hans)

•

 4年前
 编辑

嗯，这个降噪器对你有效就好。这是降噪，不是回声消除，我只是说说。如果用一个 22 个神经元的输入/输出网络来代替简单的分析，仅仅是因为“深度学习”，看看它在回声消除方面的表现会很有意思。

但嘿，深度学习是魔法，不是吗？我完全忘了哈哈！是魔法！它肯定会起作用的！

最后，我什么都不会重新评估！而且我想当个居高临下的混蛋的时候，我就会继续当！

编辑：不过别太当真。我只是互联网上的一条狗。但实际上，我更有可能这样对想加入深度学习是魔法游戏的 CEO 或经理说话。

赞同    -5           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/karisigurd4444

取消

评论

[johnman1016](https://www.reddit.com/user/johnman1016/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icp0buv/?tl=zh-hans)

顺便说一下，我可能也可能没在几家大型通讯公司工作过…

赞同    5           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/johnman1016

取消

评论

[karisigurd4444](https://www.reddit.com/user/karisigurd4444/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icp23hh/?tl=zh-hans)

真不错。

赞同    -3           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/karisigurd4444

取消

评论

[johnman1016](https://www.reddit.com/user/johnman1016/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icpqn6d/?tl=zh-hans)

嗯，楼主问的是那些公司用啥… 答案是深度学习。

赞同    5           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/johnman1016

取消

评论

[karisigurd4444](https://www.reddit.com/user/karisigurd4444/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icptbbd/?tl=zh-hans)

那也挺好的！

我除了说“深度学习”这个答案跟“是魔法变的”一样没用，还说了别的啥吗？

赞同    -1           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/karisigurd4444

取消

评论

[更多回复](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icpqn6d/?tl=zh-hans&force-legacy-sct=1)

[更多回复](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icp23hh/?tl=zh-hans&force-legacy-sct=1)

[更多回复](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icp0buv/?tl=zh-hans&force-legacy-sct=1)

[已删除]

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icsa8sw/?tl=zh-hans)

版主已移除评论

赞同    0           反对

回复

共享

   - 关注评论
  - 举报
 - 保存

回复 u/[已删除]

取消

评论

[karisigurd4444](https://www.reddit.com/user/karisigurd4444/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/ict6uzk/?tl=zh-hans)

你就是个傻X，在网上替别人瞎操心。

赞同    0           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/karisigurd4444

取消

评论

[![u/Mobile_Usual_19 头像](data:image/png;base64...)](https://www.reddit.com/user/Mobile_Usual_19/)

[Mobile\_Usual\_19](https://www.reddit.com/user/Mobile_Usual_19/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icta9bs/?tl=zh-hans)

牛逼回复，你个混蛋。我没替别人不爽。我看了你发的，简直烂透了。

赞同    1           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/Mobile\_Usual\_19

取消

评论

[更多回复](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/ict6uzk/?tl=zh-hans&force-legacy-sct=1)

[更多回复](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icsa8sw/?tl=zh-hans&force-legacy-sct=1)

[更多回复](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icoilp1/?tl=zh-hans&force-legacy-sct=1)

[更多回复](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icnpcwk/?tl=zh-hans&force-legacy-sct=1)

[更多回复](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icncllw/?tl=zh-hans&force-legacy-sct=1)

[更多回复](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icn8orj/?tl=zh-hans&force-legacy-sct=1)

[![u/GamemasterAudio 头像](data:image/png;base64...)
u/GamemasterAudio](https://www.reddit.com/user/GamemasterAudio/)
•
[广告](https://www.reddit.com/user/GamemasterAudio/)

- 隐藏
- 举报
- 关于此广告
- [厌倦了广告？](https://www.reddit.com/premium?referrerId=ad_overflow)

Sound effects built for indie devs.

了解更多信息

gamemasteraudio.com

![Thumbnail image: Sound effects built for indie devs.](data:image/webp;base64...)

[![u/Exponential_Rhythm 头像](data:image/png;base64...)](https://www.reddit.com/user/Exponential_Rhythm/)

[Exponential\_Rhythm](https://www.reddit.com/user/Exponential_Rhythm/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icn3mmn/?tl=zh-hans)

Hobbyist

老实说，这完全是瞎猜，但也许是像 [Kn0ck0ut](https://www.kvraudio.com/product/kn0ck0ut-by-st3pan0va)那样的频谱相减，但这并不是一个实时的解决方案。

赞同    1           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/Exponential\_Rhythm

取消

评论

[![u/1644479889 头像](data:image/png;base64...)](https://www.reddit.com/user/1644479889/)

[1644479889](https://www.reddit.com/user/1644479889/)

 原发帖人
•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icn45b3/?tl=zh-hans)

50 毫秒的延迟可以接受，如果你说的那种“不是实时”指的是这个的话

赞同    3           反对

回复
             奖励

共享

   - 关注评论
  - 举报
 - 保存
- 奖励

共享

回复 u/1644479889

取消

评论

[更多回复](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icn3mmn/?tl=zh-hans&force-legacy-sct=1)

[triitrunk](https://www.reddit.com/user/triitrunk/)

•

 [4年前](https://www.reddit.com/r/audioengineering/comments/vdxgvr/comment/icnh1xq/?tl=zh-hans)

Mixing

我敢肯定 Discord 只是个简单的门槛……对其他的没啥经验

赞同    -2           反对
