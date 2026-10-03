# 主动噪声消除（ANC）算法研究（中文译文）

> **译文来源**：qsBye（博客园）
> **原文链接**：https://www.cnblogs.com/qsbye/p/18385474
> **发布时间**：2024-09-06
> **英文原文**：Ian C. Díaz、Matías Fogg、Lisandro Alvarez、Manuel Dieguez，《Study on active noise cancellation》，MIT License
> **源码仓库**：https://github.com/iancraz/ANC-Implementation/blob/master/README.md
> **归档整理**：2026-10-03（由本地保存的网页 HTML 经 markitdown 转换并人工整理排版）

---

* 原文地址: [https://github.com/iancraz/ANC-Implementation/blob/master/README.md](https://github.com/iancraz/ANC-Implementation/blob/master/README.md)
* 许可: MIT license
* 原作者: Ian C. Díaz, Matías Fogg, Lisandro Alvarez, Manuel Dieguez

## 摘要

现在，主动消噪技术已经被广泛应用于各个领域，从工业到医疗和消费产品。这个研究工作讨论了不同类型的消噪系统以及可以用于实施这些系统的各种自适应算法。该论文重点阐述的是广带前向消噪系统，既是在实际方面，又是在实施方面。这其中包括了使用FxLMS算法和其递归变体FxRLS来实施的讨论。

## I:引言

主动噪声消除是一种通过添加另一个波来消除一个波的方法。它通过利用破坏性干涉现象的电声或机电系统来实现。为了实现这一点，它使用一个与噪声波具有相同振幅但相位相反的波。因此，两个波的结合会导致两个噪声的消除。如后文将讨论的，噪声消除系统或其英文缩写ANC，在消除低频噪声方面非常有效。这导致了工业领域的大量发展，因为被动噪声消除系统通常无法消除低频范围内的噪声。
噪声可以在频率、振幅、相位、声速等方面变化，并且可以由多种来源引起。这种噪声的本质意味着ANC系统必须具有一个自适应滤波器，允许修改上述所有变量。自适应滤波器调整其系数，通常称为“权重”，以最小化误差信号。这些系数可以通过几种算法确定，最广泛使用的是最小均方算法或其英文缩写LMS。此外，还有其他算法，如NLMS、FxLMS、FxRLS等。这些将在后文讨论。这种滤波器可以用有限响应滤波器（FIR）和无限响应滤波器（IIR）来实现。由于通常使用FIR滤波器，因此在本工作中将使用这种滤波器。
在本研究中，噪声被定义为任何你想要消除的不受欢迎的声音，如机器产生的噪声、环境噪声、咖啡馆中的对话等。换句话说，噪声可以由任何方式产生。
ANC系统可以基于预反馈或前馈，其中参考噪声在传播到第二个源之前被采样，或者基于反馈，其中噪声在没有参考的情况下被消除。也就是说，信号在被注入消除波之后被采样，以纠正消除误差。
在前馈系统中，有1）窄带系统或Narrowband，将在第II部分介绍，以及2）宽带系统或Broadband，将在第II部分讨论。另一方面，在反馈系统中，有自适应窄带系统或Narrowband adaptive，将在第III部分介绍。
在第IV部分，将介绍Wiener自适应滤波器的概念以及必须考虑的正确实现它们的准则。
在第V部分，将开发用于实现自适应滤波器的几种算法的概念和应用，如LMS。在第VI部分，将开发宽带前馈系统的模拟系列。

## II:前馈系统

### A:窄带前馈系统

在许多主动噪声控制（ANC）应用中，噪声信号是周期性的。这些信号可以由电机、风扇、压缩机等机器产生。该系统利用这些信号的周期性来消除噪声。这种系统具有以下优点：

1. 避免了从噪声消除扬声器到参考麦克风的反馈。
2. 避免了从参考麦克风的校准。
3. 由于是周期性噪声，因此消除了因果限制。
4. 生成参考信号的可能性允许独立控制每个谐波。
5. 只需要对接近谐波频率的传递函数进行建模。

![图1. 窄带前馈系统的表示](https://img2024.cnblogs.com/blog/1048201/202408/1048201-20240828202241563-1706126772.png)

*图1. 窄带前馈系统的表示*

这种系统的一个基本图示如图1所示。系统有一个传感器，它充当信号发生器的触发器。生成的参考信号可以是两种类型：1）周期等于周期性噪声基频倒数的一串脉冲；2）与周期性噪声谐波具有相同频率的正弦波。为了实现1）类型的信号，使用了称为波形合成的方法，而对于2）类型则使用了自适应陷波滤波器。无论生成哪种类型的参考信号，它都由自适应滤波器处理并发送到驱动器。通过误差信号，自适应滤波器会根据不同的算法进行修改，以最小化误差。

下面将更详细地解释合成方法。传感器捕捉周期性噪声信号，并将噪声样本存储起来。我们定义N为一个噪声周期内的样本数。这些样本代表了要生成的波形，以抑制噪声。样本依次发送到模拟数字转换器，以产生在噪声消除驱动器中再现的信号。在这个过程中，自适应FIR滤波器的长度等于噪声信号的一个周期或N个样本。剩余声音由误差麦克风拾取，该麦克风与参考信号的脉冲同步。随后，修改自适应滤波器以减小误差。可以证明，LMS递归关系如下获得：

> w(n+1) = w(n) − 2μ·e(n)·x(n)

其发展将在第五节中详细讨论。经过一系列数学步骤[1]之后，我们得到了系统的传递函数：

> (1)H(z)=E(z)D(z)=1−z−N1−(1−2μ)z−N

(1)是一个有趣的方程，因为它关联了原始噪声d(n)及其被抑制的版本e(n)。零点的幅度恒定为（|z|=1）并且在单位圆上等距分布，位置为0,2N,4N,...,2(N−1)N。它在与零点相同的角度上有N个极点，但位于圆周|Z|=(1−2μ)1N上。传递函数描述了一个陷波滤波器，它在周期性噪声的基本频率和谐波频率上衰减。(1)表明μ关于稳定性有一个约束。对于单位幅度脉冲序列，μ必须始终满足0<μ<1。每个陷波的带宽大约为BW≈2(1−2μ)1N。注意，带宽与μ成正比。另一方面，响应包络衰减的时间常数大约为τ≈Tμ。因此，在带宽和瞬态响应的持续时间之间存在一个权衡[1]。最后，需要澄清的是，(1)是在没有考虑从噪声消除驱动器到误差麦克风的路径（通常称为次级路径S2）的情况下计算得出的。如果考虑了这一点，传递函数(1)将经历以下修改：

> (2)H(z)=E(z)D(z)=1−z−N1−(1−2μS2(z−1))z−N

然而，由于这项研究工作专注于宽带前馈主动噪声控制系统，因此不会展开涉及(2)的结果。
关于这种系统的最后一点评论是，必须考虑所需的时间。也就是说，信号由自适应滤波器处理并发送到扬声器的时间，以及信号到达误差麦克风所需的时间都必须考虑在内。考虑到这些时间，可以适当地调整修改自适应滤波器的算法，以便能够在实时实现中使用这种类型的系统。

### B:宽带前馈系统

系统如图2所示。该系统有一个参考麦克风来接收输入信号，该信号由ANC系统处理，ANC系统在消噪扬声器中再现一个信号。系统还有一个误差麦克风，它修改自适应滤波器的行为以最小化ANC系统的误差。

![图2. 宽带前馈系统的表示](https://img2024.cnblogs.com/blog/1048201/202408/1048201-20240829050748843-1625242642.png)

*图2. 宽带前馈系统的表示*

主路径P(z)定义为参考麦克风附近坐标到误差麦克风之间的声学路径。还有两条次级路径，一条是从消噪扬声器到参考信号S1(z)，另一条是从消噪扬声器到误差麦克风S2(z)。自适应滤波器W(z)用于估计P(z)以生成消噪信号。

为了澄清系统的工作原理，系统的块图如图3所示。误差麦克风被描绘为加法模块，其输入信号是d(n)和y(n)。系统试图修改W(z)，使得y(n)和−d(n)彼此之间具有最大相关性。如果实现了这一点，当组合这两个信号时，残差误差e(n)=d(n)+y(n)≈0，因为它们具有最大相关性，输出将是白高斯噪声。

评估图3中的系统得到输入信号x(n)和误差信号之间的传递函数E(z)：

> (3)E(z)X(z)=P(z)+W(z)1−S1(z)⋅W(z)⋅S2(z)

![图3. 宽带前馈的模块表示](https://img2024.cnblogs.com/blog/1048201/202408/1048201-20240829050825510-785597293.png)

*图3. 宽带前馈的模块表示*

由于需要完美的噪声消除，e(n)=0，因此E(z)=0。接下来，得到自适应滤波器必须具有的传递函数：

> (4)W(z)=P(z)P(z)S1(z)−S2(z)

如上所述，自适应滤波器可以用IIR滤波器实现，但其适应可能会有问题，因为接近单位圆的极点可能会移出单位圆，从而在适应过程中产生系统不稳定的条件。因此，人们可能会认为使用FIR滤波器是解决方案。然而，FIR滤波器作为替代方案在实践中也有困难。通常，误差麦克风和消噪扬声器与参考麦克风的距离相同。此外，误差麦克风通常放置在消噪扬声器附近。这种组件的布局导致主路径P(z)和次级路径S1(z)的第一部分具有高阶传递函数，而次级路径S2(z)的第二部分具有低阶传递。因此，(4)只能用高阶FIR滤波器近似。

为了解决这个缺点，可以估计S1(z)以数字方式消除其效果。如果引入了S1^(z)作为S1(z)的估计，图3中的系统将经历以下修改：

人们可能会认为，既然S1被估计了，就可以估计S2。这必须不可避免地完成，因为从消噪扬声器到误差麦克风的路径事先并不知道。像S1^一样，S2^使用自适应算法如LMS来估计。图5展示了具有两种操作模式的系统：1) 离线处理，其中噪声输入来自系统内部，从而获得S1^和S2^的估计，2) 在线处理，自适应滤波器W(z)开始作用以抑制未知噪声。

![图4. 使用S1(z)的宽带前馈系统的模块表示](https://img2024.cnblogs.com/blog/1048201/202408/1048201-20240829050955894-1606904842.png)

*图4. 使用S1(z)的宽带前馈系统的模块表示*

![图5. 宽带前馈系统的方框图，包括在线和离线运行模式](https://img2024.cnblogs.com/blog/1048201/202408/1048201-20240829093645284-1474651006.png)

*图5. 宽带前馈系统的方框图，包括在线和离线运行模式*

![图6. 无S1的宽带前馈系统的方框表示](https://img2024.cnblogs.com/blog/1048201/202408/1048201-20240829093800496-1759379488.png)

*图6. 无S1的宽带前馈系统的方框表示*

如果不考虑S1，分析系统将如何工作是有用的。没有S1的系统如图6所示。由于希望e(n)=d(n)+y(n)≈0，必须取W(z)+S2(z)P(z)=0。因此，自适应滤波器的传递函数是：

> (5)W(z)=−P(z)S2(z)

显然，(5)比(4)简单。像(4)一样，自适应滤波器可以由自适应算法驱动的FIR滤波器近似。

在结束对这类系统的学习时，提及处理时间的重要性并不多余。参考麦克风接收输入信号后，系统有一定的时间来生成消噪扬声器信号。如果生成这个信号所需的时间（电子延迟）比噪声从参考麦克风传到驱动器所需的时间（声学延迟）更长，系统的效率将严重受损。这是因为当电子延迟大于声学延迟时，系统响应是非因果的。当实现因果条件时，系统能够抑制随机噪声。相反，如果未实现因果条件，系统只能消除周期性噪声[4]。最后，请注意，该系统不限于周期性噪声，这使它在某种程度上优于前一个系统。这就是为什么这个系统比"窄带前馈"系统有更多的应用。

总之，可以说这类系统的难点在于实现自适应滤波器W(z)，因此，实现它的算法。因此，在第五部分中，将更详细地研究这种噪声消除系统中的实现。同样，确定这个系统将在第六部分中进行模拟，因为在与本工作中开发的其他系统相比，它是具有更多实际应用的系统，因此是更感兴趣的系统。

## III:窄带反馈系统

本节介绍窄带反馈系统。图7展示了一个反馈系统的一通道方块图。误差麦克风捕获的信号通过自适应滤波器处理，以在降噪扬声器处生成信号。这种系统仅基于自适应滤波器的输入和误差信号来合成其自身的参考信号。这就是为什么我们的想法是估计主要噪声并将其作为自适应滤波器的参考。

![图7. 窄带反馈的基本系统表示](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901210138280-1540227499.png)

*图7. 窄带反馈的基本系统表示*

![图8. 窄带反馈的系统表示](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901210220861-804034792.png)

*图8. 窄带反馈的系统表示*

如果将S(z)定义为降噪扬声器和误差麦克风之间的声学路径，那么系统可以如图8所示进行图解。在这种配置中，假设有一个S(z)的估计值S^(z)。W(z)的输出y′(n)通过它，输出y^(n)。然后得到d(n)的估计值为：

> (6)d^(n)=e(n)−y^(n)

然后，当d^(n)是d(n)的一个良好估计时，为了减少误差e(n)，自适应滤波器的传递函数必须为：

> (7)W(z)=−1S(z)

这种情况意味着y(n)=−d^(n)，因此当d^(n)是一个良好估计时，e(n)=d(n)−d^(n)将是一个小的误差信号。由于1S(z)可能是非因果的，W(z)的配置在实际中可能无法实现。正因为如此，在实际中，人们不得不求助于一个近似W(z)≈−11S(z)。
系统的传递函数H(z)由以下公式给出：

> (8)H(z)=11−G(z)=1+W(z)S^(z)1+W(z)S^(z)−S(z)

观察到，如果实现了一个良好的估计S^(z)使得S^(z)=S(z)，那么(8)将简化为。

> (9)H(z)=1+W(z)S(z)

此外，如果1S(z)是稳定且因果的，并且将W(z)设置为−1/S(z)，则可以实现d(n)的完美抑制。由于在实际中无法保证1/S(z)的稳定性和因果性，必须在确定传递W(z)时做出妥协决策，以最小化误差e(n)，这正是第五节开发的FX-LMS算法发挥作用的地方。
图9展示了包括实现自适应算法模块的系统。

![图9. 窄带反馈系统](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901210245293-71272072.png)

*图9. 窄带反馈系统*

## IV:维纳自适应滤波器

在本节中，我们将介绍维纳滤波器的概念，正如前几节所见，维纳滤波器是噪声消除系统中最常使用的自适应滤波器。
维纳滤波器W(z)的方框图如图10所示。在这里，我们试图基于信号x(n)来估计信号d(n)。假设x(n)和d(n)都是随机过程的有限长度样本。信号e(n)决定了估计的好坏，如果y(n)和d(n)信号之间的相关性最大化，那么e(n)信号将纯粹且仅由白高斯噪声组成。使用e(n)，滤波器修改其系数以最小化e(n)。系数通过自适应算法进行修改，这些算法将在第五节中讨论。

![图10. 过滤问题的方框表示](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901210419898-1021055020.png)

*图10. 过滤问题的方框表示*

在详细解释这种类型的滤波器之前，我们引入均方误差准则。它源于确定哪些滤波器系数会产生最小误差的需求。其定义如下：

> (10)ε=E[|e(n)|2]

在这里提到这个概念，是因为它在后续内容中将非常有用。继续详细解释这种类型的滤波器，图11展示了一个横向滤波器。假设输入x(n)和输出d(n)都是实数值。滤波器系数命名为w0,w1,w2,...,wN−1，也都是实数。系数向量和输入向量定义如下：

> (11)w→=[w0,w1,w2,...,wN−1]T

![图11. 横截面滤波器表示](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901210438614-786927360.png)

*图11. 横截面滤波器表示*

> (12)x(n)→=[x(n),x(n−1),x(n−2),...,x(n−(N−1))T]

输出y(n)由以下公式给出：

> (13)y(n)=x(n)→T⋅w→

误差由以下公式给出：

> (14)e(n)=d(n)−y(n)e(n)=d(n)−x(n)→T⋅w→

使用(10)可以得出：

> (15)ε=E[|e(n)|2]=E[d2(n)]−w→TE[x(n)⋅→d(n)]−E[d(n)⋅x→T(n)]⋅w→+w→Te[x(n)→⋅xT(n)→]⋅W→

如果定义交叉相关向量和自相关矩阵为：

> (16)pn=E[x→(n)⋅d(n)]

> (17)Rn=E[x→(n)⋅x→T(n)]

则可以得出：

> (18)ε=E[d2(n)]−2⋅w→T⋅pn+w→T⋅Rn⋅w→

为了得到最小化函数(18)的系数，必须解出使ε关于每个系数的偏导数等于零的方程组。可以证明，最终得到：

> (19)w0→=R−1⋅p

(19)被称为维纳-霍普方程，通过它可以获得最佳滤波器系数。将上述方程代入18中得到最小均方误差：

> (20)εmin=E[d2(n)]−pT⋅R−1⋅p

(20)允许选择最优系数，以最小化自适应滤波器的误差。这个结果将在第五节再次使用。

## V:算法

在实现自适应信号滤波器时，为了最小化输出与期望结果之间的二次误差，区分了两种算法类别：一种是确定性估计滤波器参数的算法，另一种是通过统计方法进行估计的算法。
前者以高收敛速度为特点，但同时也意味着对计算能力的高要求：递归最小二乘法（以下简称RLS）就是这一类别的突出例子。后者基于随机方法，因此需要的资源较少：最小均方方法（LMS）及其变体（NMLS、FX-LMS等）就是这一类别的例子。
下面简要描述了各种相关算法，重点放在随机算法上，因为它们的计算复杂度相对较低。

### A. 递归最小二乘法算法：RLS

RLS算法递归地找到滤波器系数，以最小化输入信号的加权线性回归的成本函数：它从操作开始就使用输入信号的信息，即它具有记忆。因此，它操作的是大的信息矩阵，这意味着具有很高的数学复杂性。尽管存在旨在提高计算效率的此类方法，但它们无法与仅处理当前输入的随机滤波器相比。最小二乘算法家族存在数值条件问题。在有限精度系统中，这一家族算法的实现非常容易受到舍入误差的影响[8]。

### B. 最小均方算法：LMS

从维纳滤波器理论的基础出发，我们考虑具有非递归结构的滤波器。因此，滤波器输出的时间表达式将由（21）式确定。

> (21)y(n)=∑i=0N−1ai(n)x(n−i)=anTxn

其中：

> (22)xn=[x(n),x(n−1),...,x(n−N+1)]T

> (23)an=[a0(n),a1(n),an−1(n)]T

如果定义一个代价函数为

> (24)Ψ(an)=E(e2(n))

> (25)e(n)=y(n)+d(n)

其中 e(n) 是在系统输出处测量的误差。经过一些数学推导，我们得到以下表达式，

> (26)Ψ(an)=E[d2(n)]−2anTpn+anTRnan

其中，

> (27)pn=E[d(n)xn]

> (28)Rn=E[xnxnT]

pn 是互相关，Rn 是自相关。定义了如（26）式所示的代价函数后，我们继续最小化它。为此，需要计算代价函数的梯度，这将有助于了解减小函数的方向。经过一些数学推导[1]，我们得到以下表达式：

> (29)∇Ψ(an)=−2pn+2Rnan

从这一点开始，可以假设使用从梯度得到的方程足以应用梯度下降算法并到达代价函数的极小值。然而，梯度对 pn 和 Rn 的依赖性产生了问题，因为通常这些值是不可用的。正是由于这个原因，下面使用了估计器：

> (30)pn^=d(n)xn

> (31)R^n=xnxnT

因此，使用这些估计器并代入（29）式，我们得到：

> (32)∇Ψ(an)=−2e(n)xn

最后，经过这些数学推导，并使用梯度下降算法推导中的相同逻辑，我们得到（33）式，这就是所谓的LMS算法方程。

> (33)an+1=an+2αe(n)x(n)

1. F-X LMS算法：如第二部分所解释的，滤波器传递函数为 W(z)=−P(z)S2(z)，然后使用不同的算法将其近似为FIR滤波器。Filtered-X最小均方，更广为人知的FX-LMS，就是应用于FIR型传递函数的LMS算法版本。
   因此，如果我们定义 x′(n) 为 x(n) 的过滤版本，我们得到（34）式，这就是FX-LMS算法的方程[1]。

> (34)an+1=an+2αe(n)x′(n)

需要澄清的是，x′(n) 是 X′(z)=X(z)S2^(z) 的反变换。这可以在图12中清楚地看到。

![图12. FXLMS算法的实现](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901211836028-1962246696.png)

*图12. FXLMS算法的实现*

2. 收敛性：尽管不会详细分析如何评估和定义LMS算法收敛的参数，但可以预先判断算法收敛必须满足的条件由（35）式给出。

> (35)0<α<1NE[x2(n)]

上限 1NE[x2(n)] 可能不会足够严格[1]。这可能是因为ANC滤波器对输入信号产生的延迟，在信号到达误差麦克风之前可能会产生不稳定。因此，如果 S2(z) 滤波器对信号产生显著的延迟，alpha的上限应该降低。另一个选择是将误差麦克风放置得更靠近消音驱动器，从而减少由于适应环导致的延迟。

3. **F-X RLS 算法**：另一方面，对过滤信号实施 RLS 算法，我们有如图13所示的系统。与 FX LMS 一样，输入信号是 x′(s) 信号，误差是 e(n)。因此，系数拟合使用以下方程：

> (36)k(n)=P(n−1)x′(n)y′H(n)P(n−1)x′(n)+λ

> (37)P(n)=λ−1P(n−1)−λ−1k(n)x′H(n)P(n−1)

> (38)w(n+1)=w(n)+k(n)e(n)

众所周知，使用这种算法将导致极高的收敛速度，但这是以计算复杂度为代价的。这在我们的系统中产生了问题，因为在紧凑设备（如耳机）中实施这种特性的算法成本过高。然而，如果我们有能力使用它，下面我们将看到它的优点。

![图13. 用于FX RLS算法更新的ANC系统图示](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901211859304-850841377.png)

*图13. 用于FX RLS算法更新的ANC系统图示*

### C. 总结

本章简要介绍了自适应噪声控制（ANC）系统的基本原理，并详细讨论了两种常用的自适应算法：最小均方（LMS）算法和递推最小二乘（RLS）算法。LMS算法以其简单性和实用性而受到青睐，但RLS算法在收敛速度上具有优势。在实际应用中，选择哪种算法取决于系统要求、计算资源以及对收敛速度和稳定性的需求。此外，为了提高ANC系统的性能，可以采用Filtered-X LMS（FX-LMS）和Filtered-X RLS（FX-RLS）算法，这些算法通过考虑滤波后的信号来优化系数的更新。在设计ANC系统时，还需要考虑滤波器的设计、系统延迟以及误差麦克风的放置等因素，以确保系统的有效性和稳定性。

## VI:宽带前馈主动噪声消除(ANC)仿真

为了进行宽带前馈系统的仿真，我们假设以下情况：1）不考虑路径S1，2）路径S2事先定义，但是对其进行了估计，因此在仿真中可以使用S2^或S2，3）使用了第V节中讨论的自适应FX-LMS算法。根据1）和2），可以使用第II节中的结果（5）。
应该注意的是，所有仿真都是使用Python编程代码完成的。

### A. 声学路径仿真

为了对上述所有内容进行仿真，我们首先必须拥有声学路径P(z)和S2(z)。为此，我们使用了Python库pyroomacoustics，它提供了一种简单的方式来模拟我们的系统。对于声学路径P(z)，我们定义了如图14所示，其响应形式如图15所示。
另一方面，为了模拟声学路径S2(z)，我们尝试模仿一副包耳式耳机，定义了如图16所示，并得到了如图17所示的脉冲响应。

### B. 使用FX LMS的仿真

首先，模拟S2^以研究FX-LMS算法。在仿真中，使用LMS算法尽可能在离线模式下近似S2^(z)到S2(z)。

![图15. 路径P(z)的脉冲响应](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901212018042-432798983.png)

*图15. 路径P(z)的脉冲响应*

![图16. 路径S2(z)的物理图](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901212115117-1799666258.png)

*图16. 路径S2(z)的物理图*

![图17. 路径S2(z)的脉冲响应](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901212145639-222039282.png)

*图17. 路径S2(z)的脉冲响应*

![图18. 估计S2的系统的方块表示](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901212213444-527774064.png)

*图18. 估计S2的系统的方块表示*

估计过程的方块图如图18所示。估计过程如下：1）在系统输入处放置白高斯噪声x(n)，以最佳估计滤波器，使得没有特定频率的占优，2）定义如前所述的S2，3）在S2^上放置自适应维纳滤波器。进行仿真时，将逐渐通过最小化e(n)来修改S2。仿真结果如图19所示。可以看出，随着时间推移，误差Js越来越小，这意味着自适应滤波器越来越接近它想要估计的内容，在本例中为S2。本段中详细描述的整个过程就是第II节中所谓的离线模式。

![图19. S2^估计误差的仿真。输入设置为高斯白噪声x(n)](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901212308903-615134241.png)

*图19. S2^估计误差的仿真。输入设置为高斯白噪声x(n)*

有了估计S2^，就可以模拟完整的系统。测试从一个周期性噪声信号开始。选择了飞机驾驶舱内产生的声音。结果如图20所示。系统输入信号是要被抵消的信号，在这种情况下是机舱环境噪声，误差信号是结果误差信号e(n)，如反复所述，期望其趋于0，这是飞行员耳朵里会听到的声音。可以看出系统运行正常，因为信号e(n)迅速降低声音，这意味着自适应算法迅速修改自适应滤波器，使飞行员耳朵中达到相对静音。在图21中可以观察到信号的随机片段，自适应滤波器生成的信号如何尝试与期望的反向信号获得最大相关性。
图22显示了使用随机噪声的仿真结果。要抑制的噪声是工厂风扇产生的噪声。请注意，尽管这个音频的长度比前一个案例短，但算法似乎需要更长的时间来适应。
最后，图24显示了自适应滤波器的前4个系数如何收敛到它们各自的值。

![图20. 飞机驾驶舱内系统的仿真](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901212415873-1574350793.png)

*图20. 飞机驾驶舱内系统的仿真*

![图21. 对于飞机驾驶舱案例，期望的反向信号和我们的系统生成的信号之间的相关性样本](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901212441184-730677255.png)

*图21. 对于飞机驾驶舱案例，期望的反向信号和我们的系统生成的信号之间的相关性样本*

![图22. 使用工厂风扇噪声的系统仿真](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901212513410-927525492.png)

*图22. 使用工厂风扇噪声的系统仿真*

![图23. 对于工厂风扇案例，期望的反向信号和我们的系统生成的信号之间的相关性样本](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901212620412-1280037457.png)

*图23. 对于工厂风扇案例，期望的反向信号和我们的系统生成的信号之间的相关性样本*

![图24. 自适应滤波器的第一个4个系数的调整](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901212644438-1089996927.png)

*图24. 自适应滤波器的第一个4个系数的调整*

系统在不同性质的输入下表现适当。这表明它适用于例如降噪耳机等场合。

### C. 使用FX RLS算法的仿真

与FXLMS一样，我们首先估计了S2^滤波器，结果J如图25所示。
最后，我们模拟了LMS案例提出的相同环境，并得到了如图26和27所示的结果。

![图25. S2滤波器估计的Jmin](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901212711367-1961071693.png)

*图25. S2滤波器估计的Jmin*

![图26. 使用FX RLS算法的工厂风扇仿真](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901212754140-655862240.png)

*图26. 使用FX RLS算法的工厂风扇仿真*

![图27. 使用FX RLS算法的飞机驾驶舱仿真](https://img2024.cnblogs.com/blog/1048201/202409/1048201-20240901212810095-1631768328.png)

*图27. 使用FX RLS算法的飞机驾驶舱仿真*

## VII:总结

在这项研究工作中，我们发展了三种类型噪声消除系统的理论与实现。研究发现，宽带前馈系统具有最多的实际应用，并且细节最为详尽。同时，FX-LMS算法被证明是最适合实现自适应滤波器的。虽然系统已经成功进行了模拟，但建议进行物理实现以验证模拟中获得的结果。

## 参考文献

1. B. Farhang and B. Wiley, Adaptive Filters: Theory and Applications (Book style), 2nd ed, pp. 555.
2. Simon Hayking, Adaptative Filter Theory, 5th ed
3. D.G.Manolakis,V.K.IngleandS.M.Kogon,StatsticalandAdaptive Signal Processing.
4. S.M.KuoandD.R.Morgan,“ReviewofDSPALgorithmsforActive Noise Control (Presented Conference Paper style),” presented at the International Conference on Control Aplications, Anchorage, Alaska, September 25–27, 2000, Paper 90 SM 690-0 PWRS.
5. A. Antoniou, Digital Signal Processing: Signals, Systems and Filters (Book style), 1st ed, pp 425
6. SenM.Kuo,SohiniMitra,andWoon-SengGan“ActiveNoiseControl System for Headphone Applications“
7. Reshma B and Kiran K A “Active Noise Control System for Head- phone Applications“
8. On the Numerical Stability and Accuracy of the Conventional Recur- sive Least Squares Algorithm

## 附录：英文原文（Study on active noise cancellation）

Study on active noise cancellation
Ian C. Díaz, Matías Fogg, Lisandro Alvarez, Manuel Dieguez

### Abstract

Today, active noise cancellation is applied in the most diverse contexts, from industry to medicine and consumer products. This research work refers to the different noise cancellation systems commonly used. In addition, it analyzes the different adaptive algorithms that can be used for the implementation of these systems. The paper emphasizes on the Broadband Feedforward cancellation system both in its practical aspects and in its implementation. Its implementation is explored with the FxLMS algorithms and its recursive variant FxRLS.

### I. INTRODUCTION

Active noise cancellation is a method for the elimination of one wave by the addition of another. It is implemented by means of an electroacoustic or electromechanical system that takes advantage of the destructive interference phenomenon. To achieve this, it uses a wave with equal amplitude but inverted phase with respect to the noise wave. Consequently, the combination of both waves results in the cancellation of both noises. As will be discussed later, noise cancellation systems or ANC for its acronym in English, are very effective in eliminating low frequency noise. This has caused a great deal of development in the industry since passive noise cancellation systems generally fail to eliminate noise in the low frequency range.
Noise can vary in frequency, amplitude, phase, speed of sound and can be caused by a myriad of sources. This very nature of noise implies that ANC systems must have an adaptive filter that allows modification to all the above mentioned variables. Adaptive filters adjust their coefficients, generally called "weights", to minimize the error signal. The coefficients can be determined by several algorithms, the most widely used is the least mean squares algorithm or LMS for its acronym in English. In addition, there are other algorithms such as NLMS, FxLMS, FxRLS, etc. These will be discussed later. This type of filters can be performed with finite response filters (FIR) and infinite response filters (IIR). As FIR filters are generally used, this is the type of filter that will be used in this work.
In this research work, noise is defined as any undesirable sound that you want to eliminate, noise generated by ma- chines, ambient noise, conversations in a cafe, etc.. In other words, noise can be generated by any means.
ANC systems can be based on pre-feedback or feed- forward, where the reference noise is sampled before it propagates to the second source, or on feedback where the noise is cancelled without a reference. That is, the signal is sampled after the injection of the canceling wave to correct the cancellation error.
Within the feedforward systems are 1) the narrowband systems or Narrowband that will be presented in the section II and 2) the broadband systems or Broadband that will be discussed in the section II. On the other hand, within the feedback systems is the adaptive narrowband system or Narrowband adaptive which will be introduced in section III.
In the IV section, the concept of Wiener adaptive filters and the criteria that must be taken into account to implement them correctly will be presented.
In the V section, the concepts and application of several algorithms to implement adaptive filters such as the LMS will be developed. In the VI section, a series of simulations of a Broadband Feedforward system are developed.

### II. FEEDFORWARD SYSTEMS

#### A. Narrowband Feedforward System

In many ANC applications noise signals are periodic. These signals can be generated by machines such as motors, fans, compressors, etc. This system uses the periodicity of these signals to cancel the noise. This type of system has the following advantages:

1. Feedback from the noise-canceling speaker to the reference microphone is avoided.
2. Alignments from the reference microphone are avoided.
3. As it is periodic noise, the causality limitation is eliminated.
4. The possibility of generating reference signals allows to control each harmonic independently.
5. It is only necessary to model the transfer function on frequencies close to that of the harmonics.

*Fig. 1. Representation of the system Narrowband Feedforward*

A basic diagram of such a system is shown in Fig. 1. The system has a sensor that acts as the trigger of the signal generator. The reference signal generated can be of two types: 1) a pulse train with period equal to the inverse
of the fundamental frequency of the periodic noise and 2) sine waves with the same frequencies as the harmonics of the periodic noise. To implement the 1) type the method called wave synthesis is used while for the 2) type an adaptive notch filter is used. No matter what type of reference signal is generated, it is processed by an adaptive filter and sent to the driver. By means of an error signal the adaptive filter is modified (thanks to different algorithms) to minimize the error.
The synthesis method is explained in more detail below. The sensor captures the periodic noise signal and the noise samples are stored. We define N as the number of samples in one cycle of the noise wave. The samples represent the waveform to be generated to suppress the noise. The samples are sequentially sent to an analog digital converter to produce the signal to be reproduced in the noise cancelling driver. In this process is the adaptive FIR filter that has a length equal to one period of the noise signal or N samples. The residual sound is picked up by the error microphone which is synchronized with the pulses of the reference signal. Subsequently, the adaptive filter is modified to decrease the error. It can be proved that the LMS recursion is obtained as:

> w(n+1) = w(n) − 2μ·e(n)·x(n)

Its development will be discussed in more detail in the section V. After a series of mathematical steps [1], the transfer function of the system is obtained:

> (1)H(z)=E(z)D(z)=1−z−N1−(1−2μ)z−N

(1) is an interesting equation as it relates the original noise,d(n) and its suppressed version, e(n). The zeros have a con-
stant amplitude of ( |z|=1) and are equidistant at positions 0,2N,4N,...,2(N−1)N on the unit circle. It has N poles at the same angles as the zeros, but on the circumference |Z|=(1−2mu)1N . The transfer function describes a notch filter
that attenuates at the fundamental and harmonic frequencies of the periodic noise. (1) shows that mu has a constraint regarding stability. mu must always satisfy 0<μ<1 for a train of unit amplitude pulses. The bandwidth for each notch this is BW≈2(1−2μ)1N . Note that, the bandwidth is proportional to mu. On the other hand, the time constant of the decay of the response envelope is approximately τ≈Tμ . Consequently, there is a tradeoff between the bandwidth and the duration of the transient response [1]. Finally, it should be clarified that (1) was calculated without considering the path between the noise-canceling driver and the error microphone, commonly called secondary path S2.If the same is considered, the transfer function (1) undergoes the following modification:

> (2)H(z)=E(z)D(z)=1−z−N1−(1−2μS2(z−1))z−N

However, the results involving (2) will not be developed since this research work focuses on the *Broadband Feedforward ANC* system.
As a final comment about this type of system, the time required must be considered. That is to say, the time it takes for the signal to be processed by the adaptive filter and then sent to the loudspeaker and also the time it takes for the signal to reach the error microphone must be taken into account. Taking these times into consideration, the algorithm that modifies the adaptive filter can be appropriately adapted to be able to use this type of systems in a real time implementation.

#### B. Broadband Feedforward System

The system is illustrated in Fig. 2. The system has a reference microphone that takes the input signal, the signal is processed by the ANC system that reproduces a signal in the cancellation speaker. It has an error microphone that modifies the behavior of the adaptive filter to minimize the error of the ANC system.

*Fig. 2. Representation of the Broadband Feedforward system*

The primary path P(z) is defined as the acoustic path between the coordinates near the reference microphone and the error microphone. There are also two secondary paths from the cancellation speaker to the reference signal S1(z) on one side and to the error microphone S2(z) on the other side. The adaptive filter W(z) is used to estimate P(z) to generate a noise cancelling signal.

To clarify the operation of the system, a block diagram of the system is illustrated in Fig. 3. The error microphone is depicted as the adder module whose input signals are d(n) and y(n). The system seeks to modify W(z) so that y(n) and −d(n) have maximum correlation with each other. If this is achieved, when combining both signals, the residual error is e(n)=d(n)+y(n)≈0 since as they have maximum correlation the output will be white Gaussian noise.

Evaluating the system in Fig.3 yields the transfer function of the input signal between x(n) and the error signal either x(n) :

> (3)E(z)X(z)=P(z)+W(z)1−S1(z)⋅W(z)⋅S2(z)

*Fig. 3 Block representation of the Broadband Feedforward*

Since perfect noise cancellation is desired, e(n)=0 so E(z)=0. Next, the transfer function that the adaptive filter must have is obtained:

> (4)W(z)=P(z)P(z)S1(z)−S2(z)

As clarified above, the adaptive filter can be implemented with an IIR filter but its adaptation can be problematic because the poles close to the unit circle can move out of the unit circle generating the condition of instability to the system in the adaptation process. Ergo, one might think that the solution is to use a FIR filter. However, the use of a FIR filter as an alternative has difficulties in practice. Generally, the error microphone and the cancellation loudspeaker are placed at the same distance from the reference microphone. In addition, the error microphone is usually placed close to the cancellation speaker. This arrangement in the components results in high-order transfer functions for the primary path P(z) and the first section of the secondary path S1(z), while the second section of the secondary path S2(z) has a low-order transfer. Therefore, (4) can only be approximated with a high-order FIR filter.

To solve this drawback, one could estimate S1(z) to remove its effect digitally. If an estimate of S1(z) defined as S1^(z) is introduced, the system in Fig. 3 undergoes the following modification:

One might think that since S1 is estimated one could estimate S2. This is what must inevitably be done since the path from the noise canceling speaker to the error microphone is not known in advance. Like S1^ , S2^ is estimated using an adaptive filter driven by an adaptive algorithm such as LMS. A system with two modes of operation is presented in Fig. 5: 1) offline processing where the noise input comes from within the system thus obtaining the estimates of S1^ and S2^ , and 2) online processing where the adaptive filter W(z) comes into action to suppress an unknown noise.

*Fig. 4. Block representation of the Broadband Feedforward system using S1(z)^*

*Fig. 5. Block diagram of the Broadband Feedforward System with online and offline modes of operation.*

*Fig. 6. Block representation of the system Broadband Feedforward without S1.*

It is useful to analyze how the system would be without considering S1. The system without S1 is illustrated in Fig. 6. Since it is desired that e(n)=d(n)+y(n)≈0 one must take W(z)+S2(z)P(z)=0. Therefore, the transfer function of the adaptive filter is :

> (5)W(z)=−P(z)S2(z)

Clearly (5) is simpler than (4). Like (4) the adaptive filter can be approximated by a FIR filter driven by an adaptive algorithm.

Finalizing the study of this type of systems it is not superfluous to mention the importance of the processing time. After the reference microphone takes the input signal, the system has a certain time to generate the cancellation speaker signal. If the time it takes to generate this signal (electronic *delay*) is longer than the time it takes for the noise to get from the reference microphone to the driver (acoustic *delay*), the efficiency of the system is severely impaired. This is because the system response is non-causal when the electronic delay is greater than the acoustic delay. When the causality condition is achieved the system is able to suppress random noises. On the contrary, if the causality condition is not achieved, the system can only eliminate periodic noises [4]. As a last clarification note that this system is not limited to periodic noises, this makes it somehow superior to the previous system. That is why this system has more applications than the *Narrowband Feedforward* system.

In conclusion, it can be said that the difficulty in this type of systems lies in the realization of the adaptive filter W(z) and consequently, the algorithm to be used to implement it. For this reason, in the section V its implementation in this type of noise cancellation systems is studied in more detail. In the same way, it is determined that this system is the one that will be simulated in the VI section, since in comparison with the rest of the systems developed in this work, it is the one that has more practical applications and therefore the one of more interest.

### III. NARROWBAND FEEDBACK SYSTEM

This section introduces the NarrowBand Feedback sys- tems. A one-channel block diagram of a Feedback system is illustrated in Fig. 7. The signal acquired by the error microphone is processed by the adaptive filter to generate a signal at the noise-cancelling driver. This type of system synthesizes its own reference signal based only on the input from the adaptive filter and the error signal. That is why the idea is to estimate the primary noise and use it as a reference for the adaptive filter.

*Fig. 7. Basic system representation Narrowband feedback*

*Fig. 8. System representation Narrowband Feedback*

If S(z) is defined as the acoustic path between the noise-canceling speaker and the error microphone, the system can be diagrammed as shown in Fig. 8. In such a configuration it is assumed that there is an estimate of S(z) called S^(z). Through it passes the output of W(z), y′(n) and outputs y^(n). Then the estimate of d(n) is obtained as:

> (6)d^(n)=e(n)−y^(n)

Then, when d^(n) is a good estimate of d(n), to reduce the error e(n) the adaptive filter transfer must be:

> (7)W(z)=−1S(z)

This scenario entails that y(n)=−d^(n) and hence e(n)=d(n)−d^(n) will be a small error signal when d^(n) is a good estimate.The W(z) configuration may not be implementable in practice due to the possibility that is a noncausal 1S(z) transfer. It is because of this that in practice one has to resort
to an approximation W(z)≈−11S(z).
The transfer H(z) of the system is given by:

> (8)H(z)=11−G(z)=1+W(z)S^(z)1+W(z)S^(z)−S(z)

It is observed that if a good estimate S^(z) is implemented such that S^(z)=S(z), then (8) reduces to.

> (9)H(z)=1+W(z)S(z)

Furthermore, if 1/S(z) is stable and causal,and W(z) is set to −1/S(z), perfect suppression of d(n) will occur.Since the stability and causality of 1/S(z) cannot be guaranteed in practice, a compromise decision must be made in the determination of the transfer W(z) to minimize the error e(n), this is where the FX-LMS algorithm developed in section V comes into play.
Fig 9 shows the system including the module implementing the adaptive algorithm.

*Fig. 9. Narrowband Feedback System*

### IV. WIENER ADAPTIVE FILTER

In this section the concept of the Wiener filter is developed since, as seen in the previous sections, it is the adaptive filter most commonly used in noise cancellation systems.
A block diagram of a Wiener filter W(z) is shown in Fig. 10. Here we seek to estimate the signal d(n) based on the signal x(n). It is assumed that both x(n) and d(n) are finite-length samples of random processes. The signal e(n) determines how good the estimate is, where, if the correlation between the y(n) and d(n) signals is maximized, the e(n) signal will be composed purely and exclusively of white Gaussian noise. Using e(n) the filter modifies its coefficients to minimize e(n). The coefficients are modified with an adaptive algorithm which are discussed in the section V.

*Fig. 10. Block representation of a filtering problem*

Before starting with a more detailed explanation of this type of filters, the mean square error criterion is introduced. It arises from the need to determine which filter coefficients produce the smallest error. It is defined as:

> (10)ε=E[|e(n)|2]

This concept is mentioned here because it will be very useful in the following.Continuing with a more detailed explanation of this type of filters, a transversal filter is illustrated in Fig. 11. Both the input x(n) and the output d(n) are assumed to be real values. The filter coefficients named w0,w1,w2,...,wN−1 are also real.The coefficient vector and the input vector are defined as follows:

> (11)w→=[w0,w1,w2,...,wN−1]T

*Fig. 11. Cross-sectional filter representation*

> (12)x(n)→=[x(n),x(n−1),x(n−2),...,x(n−(N−1))T]

The output y(n) is given by:

> (13)y(n)=x(n)→T⋅w→

The error is given by:

> (14)(1)e(n)=d(n)−y(n)(2)e(n)=d(n)−x(n)→T⋅w→

Using (10) it can be shown that:

> (15)ε=E[|e(n)|2]=E[d2(n)]−w→TE[x(n)⋅→d(n)]−E[d(n)⋅x→T(n)]⋅w→+w→Te[x(n)→⋅xT(n)→]⋅W→

If the cross-correlation vector and the autocorrelation
matrix are defined as:

> (16)pn=E[x→(n)⋅d(n)]

> (17)Rn=E[x→(n)⋅x→T(n)]

It is obtained that:

> (18)ε=E[d2(n)]−2⋅w→T⋅pn+w→T⋅Rn⋅w→

To obtain the coefficients that minimize the function (18) one must solve the system of equations that result from making the partial derivatives of ε with respect to each coefficient equal to zero. It can be shown that one arrives at:

> (19)w0→=R−1⋅p

(19) is known as the equation *wiener-Hopf* where the optimal filter coefficients can be obtained.Replacing the latter equation in 18 gives the minimun mean-squared error:

> (20)εmin=E[d2(n)]−pT⋅R−1⋅p

(20) allows to choose the optimal coefficients that mini-mize the error of the adaptive filter. This result will be used again in the V section.

### V. ALGORITHMS

For the implementation of adaptive signal filters, a dis-tinction is made between two categories of algorithms used to minimize the quadratic error between the output and the desired result: those that estimate the filter parameters deterministically and those that do so by means of statistical methods.
The former are characterized by high convergence speeds, but at the same time imply high computational capacity requirements: the recursive least squares method (hereafter RLS) is a prominent example of this category. The latter are based on stochastic methods and therefore require fewer resources: the least mean squares method (LMS) and its vari-ants (NMLS, FX-LMS, etc. are examples of this category).
A brief description of the various relevant algorithms is given below, with emphasis on the stochastic ones, since their computational complexity is relatively low.

#### A. Recursive Least Squares Algorithm: RLS

The RLS algorithm recursively finds the filter coefficients to minimize the cost function of a weighted linear regression of the input signals: it uses information from the input signals from the beginning of its operation, i.e. it has memory. Because of this, it operates with large information matri-ces, which implies great mathematical complexity. Although there are methods of this type designed to be more computa- tionally efficient, they fail to compare with stochastic filters, which only deal with the current input. The family of least squares algorithms is known to have numerical conditioning problems. The implementation of this family of algorithms is very sensitive to rounding errors in finite precision systems [8].

#### B. Least Mean Squares Algorithm: LMS

Starting from the basis of Wiener’s filter theory, we think of filters with a non-recursive structure. Therefore, the time expression of the filter output will be determined by (21).

> (21)y(n)=∑i=0N−1ai(n)x(n−i)=anTxn

Being:

> (22)xn=[x(n),x(n−1),...,x(n−N+1)]T

> (23)an=[a0(n),a1(n),an−1(n)]T

If a cost function is defined as

> (24)Ψ(an)=E(e2(n))

> (25)e(n)=y(n)+d(n)

Where e(n) is the error measured at the output of the system. Developing we arrive at the expression,

> (26)Ψ(an)=E[d2(n)]−2anTpn+anTRnan

Where,

> (27)pn=E[d(n)xn]

> (28)Rn=E[xnxnT]

Being pn the cross-correlation and Rn the auto-correlation. Having defined the cost function as seen in (26), we proceed to minimize it. For this, it is necessary to calculate the gradient of the cost function, since it will be useful to know the direction of motion to minimize the function. After some mathematical development [1], we arrive at the following expression:

> (29)∇Ψ(an)=−2pn+2Rnan

From this point on, it could be assumed that it would be sufficient to use the equation obtained from the gradient to apply the gradient descent algorithm and arrive at a minimum of the cost function. However, the dependence of the gradient on pn and Rn generates problems, since in general those values are not available. It is for this very reason that the estimators seen below are used:

> (30)pn^=d(n)xn

> (31)R^n=xnxnT

Therefore, using these estimators and replacing in (29), we obtain:

> (32)∇Ψ(an)=−2e(n)xn

Finally, after this mathematical development, and using the same logic in the derivation of the gradient descent algorithm, we obtain (33), which is the so-called LMS algorithm equation.

> (33)an+1=an+2αe(n)x(n)

1. F-X LMS Algorithm: As explained in section II, the filter transfer is given by W(z)=−P(z)S2(z) and then this is approximated to a FIR filter using different algorithms.The Filtered-X Least-Mean-Square, better known as FX-LMS, is simply the version of the LMS algorithm applied to FIR-type transfers.
   Therefore, if we define x′(n) as the filtered version of x(n) we obtain (34) which is the equation of the FX-LMS algorithm [1].

> (34)an+1=an+2αe(n)x′(n)

It is important to clarify that x′(n) is the anti-transform of X′(z)=X(z)S2^(z).This can be clearly seen in Fig. 12.

*Fig. 12. Implementation of an FXLMS algorithm*

2. Convergence: Although there will not be a very de- tailed analysis on how to evaluate and define the parameters for the LMS algorithm to converge, the condition that must be fulfilled to be able to say a priori that the algorithm converges is given by (35).

> (35)0<α<1NE[x2(n)]

The upper bound 1NE[x2(n)] may not become sufficiently restrictive [1]. This may be due to the delay generated by the ANC filter to the input signal before reaching the error microphone, which may generate instability. Therefore, if the S2(z) filter generates a significant delay to the signal, the upper limit of alpha should be reduced. Another alternative is to place the error microphone closer to the cancellation driver, thus reducing the delay due to the adaptation loop.

3. **F-X RLS algorithm**: On the other hand, implementing the RLS algorithm for filtered signal, we have the system shown in Fig. 13. Like the FX LMS the input signal is the signal x′(s) and the error is e(n). Therefore, the fitting of the coefficients are done with eqs:

> (36)k(n)=P(n−1)x′(n)y′H(n)P(n−1)x′(n)+λ

> (37)P(n)=λ−1P(n−1)−λ−1k(n)x′H(n)P(n−1)

> (38)w(n+1)=w(n)+k(n)e(n)

As we know, using this algorithm will result in an ex-tremely high convergence speed, but at the cost of compu-tational complexity. This generates problems in our system, since implementing an algorithm of these characteristics in a compact device, such as a headset, would be insufficiently expensive. However, in case we have the capability to use it, we will see the advantages of it below.

*Fig. 13. Diagram of ANC system for FX RLS algorithm update*

### VI. BROADBAND FEEDFORWARD ANC SIMULATION

To carry out the simulation of a Broadband Feedforward system we assume the following: 1) the path S1 is not considered, 2) the path S2 is defined beforehand but an estimate of it is made so either S2^ or S2 can be used in the simulation and 3) the adaptive FX-LMS algorithm discussed in section V is used. From 1) and 2) it follows that the result (5) seen in section II is used.
It should be noted that all simulations are performed using Python programming code.

#### A. Simulation of acoustic paths

In order to perform a simulation of everything discussed above, we must first have the acoustic paths P(z) and S2(z). For this we make use of the Python library pyroomacoustics which provides a simple way to simulate our system. For the acoustics path P(z) we define it as shown in Fig. 14, and its response has the form shown in Fig. 15.

*Fig. 14. Physical diagram of the path P(z)*

On the other hand, for the acoustic path S2(z) an attempt is made to mimic a overear headphone by defining it as shown in Fig. 16, and obtaining an impulse response as shown in Fig. 17.

#### B. Simulation with FX LMS

First, S2^ is simulated to study the FX-LMS algorithm. For the simulation, the LMS algorithm is used in order to approximate in offline mode as best as possible S2^(z) to S2(z).

*Fig. 15. Impulse response of the path P(z)*

*Fig. 16. Physical diagram of the path S2(z)*

*Fig. 17. Impulse response of the path S2(z)*

*Fig. 18. Block representation of the system to estimate S2.*

A block diagram of the estimation procedure is illustrated
in Fig. 18. The estimation is performed as follows: 1) place x(n) white Gaussian noise at the input of the system in order to best estimate the filter so that there is no predominance at certain frequencies, 2) define S2 as previously mentioned, and 3) place an adaptive Wiener filter on S2^. When per-forming the simulation, S2 will be gradually modified by minimizing e(n). The simulation is shown in Fig. 19. As can be seen, the error Js becomes smaller and smaller as time goes by, which means that the adaptive filter becomes more and more similar to what it wants to estimate, in this case S2.The whole procedure detailed in this paragraph is what is called *offline* mode in section II.

*Fig. 19. Simulation of the estimation error of S2^. Gaussian white noise is set as input x(n).*

Having the estimate S2^ it is possible to simulate the complete system. The test starts with a periodic noise signal. The sound produced in the cockpit of an airplane was selected. The results are shown in Fig. 20. The System input signal is the signal that is injected to be nulled, in this case it is the ambient cockpit noise, the Error signal is the resulting error signal e(n) which as stated repeatedly, is expected to tend to 0, and is what the pilot would hear in his ears. As can be seen the system works properly since the signal e(n) tends to decrease the sound quickly, and this translates to the adaptive algorithm being quick to modify the adaptive filter, achieving relative silence in the pilot’s ears. In Fig. 21 it can be observed in a random segment of the signal, how the signal generated by the adaptive filter tries to obtain the maximum correlation with respect to the desired inverted signal.
Fig. 22 shows the result of a simulation with a random noise. The noise to be suppressed is the noise produced by a fan in a factory. Note that although it seems that the algorithm takes longer to adapt than in the previous case, this is due to the shorter length of this audio.
Finally, Fig. 24 shows how the first 4 coefficients of the adaptive filter converge to their respective values.

*Fig. 20. Simulation of the system inside the aircraft cabin*

*Fig. 21. Sample of the correlation between the desired inverted signal and the signal generated by our system for the aircraft cockpit case.*

*Fig. 22. Simulation of the system with noise from a factory fan*

*Fig. 23. Sample of the correlation between the desired inverted signal and the signal generated by our system for the case of the factory fan.*

*Fig. 24. Adjustment of the first 4 coefficients of the adaptive filter.*

The system behaved appropriately under inputs of different nature. This shows that it is suitable for use in, for example, noise cancelling headphones.

*Fig. 25. Jmin for S2 filter estimation*

*Fig. 26. Simulation of a fan in a factory with the FX RLS algorithm*

*Fig. 27. Simulation of an aircraft cockpit with the FX RLS algorithm*

#### C. Simulation with FX RLS algorithm

As with the FX LMS, we first estimated the S2^ filter, giving
a result of J as can be seen in Fig. 25
Finally, we simulated the same environments proposed for
the LMS case and arrived at the results shown in Figures 26 and 27.

### VII. CONCLUSION

In this research work, the theory and implementation of three types of noise cancellation systems were developed. It was found that the Broadband Feedforward system has the most practical applications and was the most detailed. In conjunction, the FX-LMS algorithm was found to be the most appropriate for the implementation of the adaptive filter. Although the system was successfully simulated, its physical implementation is suggested to corroborate the results obtained in the simulations.
