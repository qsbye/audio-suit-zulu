package com.example.audio_stream_app.dsp.anc

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 实时消音控制器（需求 FR-5）。
 *
 * 音频线程每个 chunk 调用一次 [process]，内部完成：
 * 1. 回声剥离——本机反噪经空气回到麦克的回声与播放信号相差一个已知的
 *    回路时延 lead，故用「带延迟对齐的 NLMS」（[DelayAec]，比扩音器页
 *    NlmsAec 多了标定延迟对齐），从麦信号中减去自身反噪，得到外部噪声；
 * 2. PLL 相位/频偏跟踪——以训练得到的单周期模板做归一化互相关，
 *    允许基频缓慢漂移；输出锁定置信度；
 * 3. 幅度跟踪——最小二乘幅度系数慢自适应；
 * 4. 反波生成——在相位 φ(n+lead) 处由 [WaveformMlp] 推理（预测未来），
 *    取负、乘幅度，提前写出反向波形以抵消回路时延；
 * 5. 安全整形——增益缓升（约 1s）、失锁 0.5s 内淡出静音、输出硬限幅。
 *
 * 所有状态只允许在音频线程访问。
 */
class AncController(
    private val sampleRate: Int = 44100,
    private val chunkSamples: Int = 512
) {
    // ---- 训练产物 ----
    private var mlp: WaveformMlp? = null
    private var template = FloatArray(0)   // 未归一化的单周期模板（训练时真实幅度）
    private var templateRms = 1f
    private var period = 1f
    private var templatePower = 1.0

    // ---- 回路时延与用户调节 ----
    private var leadSamples = 0
    var maxAntiLevel = 0.5f
        private set

    // ---- 子模块 ----
    private var echo: DelayAec? = null

    // ---- PLL 状态 ----
    private var phase = 0.0           // chunk 首采样对应的模板下标（连续值）
    private var instPeriod = 1f       // 瞬时周期（跟踪漂移）
    private var slip = 0.0            // 平滑后的每采样滑移率
    private var confidence = 0f
    private var everAcquired = false
    private val acqBuffer = FloatArray(ACQ_SAMPLES)
    private var acqFilled = 0
    private var lockCount = 0
    private var unlockCount = 0
    private var locked = false

    // ---- 幅度与门控 ----
    private var ampSmooth = 0f
    private var gate = 0f
    private var enabled = false

    // ---- 周期带能量指标 ----
    private var beforeEnergy = 0.0
    private var nowEnergy = 0.0
    private var baselineAccum = 0.0
    private var baselineChunks = 0
    private var bandDbNow = 0f

    // ---- 播放历史（回声剥离参考） ----
    private var playRing = FloatArray(1)
    private var playPos = 0

    /** 是否处于锁定状态（供 UI 显示"锁定/搜索中"）。 */
    val isLocked: Boolean get() = locked

    /** 实时基频 Hz。 */
    val instantF0: Float get() = if (instPeriod > 0) sampleRate / instPeriod else 0f

    /** 当前实际反噪发送比例（门控×幅度，0..1）。 */
    val antiGain: Float get() = gate * abs(ampSmooth)

    /** 周期带能量相对开启前的差值 dB（负值=已下降）。 */
    val bandEnergyDeltaDb: Float get() = bandDbNow

    fun attach(
        mlp: WaveformMlp,
        rawTemplate: FloatArray,
        templateRms: Float,
        periodSamples: Int,
        leadSamples: Int
    ) {
        this.mlp = mlp
        this.template = rawTemplate
        this.templateRms = templateRms
        this.period = periodSamples.toFloat()
        this.instPeriod = periodSamples.toFloat()
        this.leadSamples = leadSamples
        var pwr = 0.0
        for (v in rawTemplate) pwr += v.toDouble() * v
        this.templatePower = (pwr / rawTemplate.size).coerceAtLeast(1e-9)
        val ringCap = (leadSamples + AEC_TAPS + 8).coerceAtLeast(AEC_TAPS * 2)
        this.playRing = FloatArray(ringCap)
        this.echo = DelayAec(AEC_TAPS, leadSamples.coerceAtLeast(0))
        resetRuntime()
    }

    /** 设置反相波硬限幅上限（归一化，UI 反噪增益滑条调用，5%..100%）。 */
    fun setMaxAntiLevel(v: Float) {
        maxAntiLevel = v.coerceIn(0.05f, 1f)
    }

    fun updateLead(samples: Int) {
        if (samples == leadSamples) return
        leadSamples = samples
        val ringCap = (leadSamples + AEC_TAPS + 8).coerceAtLeast(AEC_TAPS * 2)
        if (ringCap != playRing.size) playRing = FloatArray(ringCap)
        echo = DelayAec(AEC_TAPS, leadSamples.coerceAtLeast(0))
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        if (on) {
            // 重新统计开启前基线
            baselineAccum = 0.0
            baselineChunks = 0
            beforeEnergy = 0.0
            bandDbNow = 0f
        }
    }

    fun reset() = resetRuntime()

    private fun resetRuntime() {
        phase = 0.0
        instPeriod = period
        slip = 0.0
        confidence = 0f
        everAcquired = false
        acqFilled = 0
        lockCount = 0
        unlockCount = 0
        locked = false
        ampSmooth = 0f
        gate = 0f
        nowEnergy = 0.0
        bandDbNow = 0f
        playRing.fill(0f)
        echo?.reset()
    }

    /**
     * 处理一帧。[mic] 为麦克风采音，反相波写入 [out]（前 count 项）。
     * 返回本帧外部噪声周期分量的最小二乘幅度（调试/指标用）。
     */
    fun process(mic: ShortArray, out: ShortArray, count: Int): Float {
        val net = mlp
        val pInt = period.roundToInt()
        if (!enabled || net == null || pInt <= 1) {
            for (i in 0 until count) {
                out[i] = 0
                pushPlayed(0f)
            }
            return 0f
        }

        // 1) 回声剥离
        val ext = FloatArray(count)
        val aec = echo!!
        for (i in 0 until count) {
            ext[i] = aec.processSample(mic[i] / 32768f, playRing, playPos)
        }

        // 2) 相位获取/跟踪（trackPhase 只修正本块相位，帧推进统一在末尾做一次）
        val predStep = period / instPeriod
        if (!everAcquired) acquirePhase(ext, pInt, count) else trackPhase(ext, pInt, predStep)
        updateLockState()

        // 3) 门控（每块更新一次）：置信度跌破下阈值时无论是否仍在"正式锁定"
        //    （失锁有最长 0.5s 确认期）都立即淡出；锁定后约 1s 缓升
        when {
            confidence < LOCK_OFF_CORR -> gate -= gate * GATE_DOWN_ALPHA
            locked -> gate += (1f - gate) * GATE_UP_ALPHA
        }

        // 4) 生成反波：在相位 φ(n+lead) 处推理取负（提前 lead 采样抵消回路时延）
        val step = period / instPeriod   // 每真实采样对应的模板步进（漂移补偿）
        var periodicPower = 0.0
        for (i in 0 until count) {
            var played = 0f
            if (gate > 1e-4f) {
                val genIdx = ((phase + (i + leadSamples) * step) % pInt + pInt) % pInt
                val predicted = net.predict((genIdx / pInt).toFloat())
                played = -predicted * ampSmooth * templateRms * gate
                played = played.coerceIn(-maxAntiLevel, maxAntiLevel)
                if (played.isNaN()) played = 0f
            }
            out[i] = (played * 32767f).toInt().toShort()
            pushPlayed(played)
        }

        // 5) 帧相位统一推进到下一帧（全类中唯一的推进点）
        if (everAcquired) {
            phase = (phase + count * step) % pInt
            if (phase < 0.0) phase += pInt
        }

        // 6) 周期带能量指标（外部噪声周期分量功率）
        periodicPower = (ampSmooth.toDouble() * ampSmooth) * templatePower
        if (baselineChunks < BASELINE_CHUNKS) {
            baselineAccum += periodicPower
            baselineChunks++
            if (baselineChunks == BASELINE_CHUNKS) beforeEnergy = baselineAccum / BASELINE_CHUNKS
        } else {
            nowEnergy = if (nowEnergy <= 0.0) periodicPower
            else nowEnergy * 0.9 + periodicPower * 0.1
            if (beforeEnergy > 1e-10) {
                bandDbNow = (10f * (ln(nowEnergy.coerceAtLeast(1e-12) / beforeEnergy) / ln(10.0))).toFloat()
            }
        }
        return ampSmooth
    }

    /**
     * 首次全局捕获：用 FFT 互相关在一个周期内找初始相位。
     * 互相关约定 corr[lag] = Σ seg[lag+n]·template[n]，即全局采样 (lag+n)
     * 对应模板下标 n，故全局采样 g 的模板相位为 (g - lag)；这里把 [phase]
     * 对齐到「当前块首采样」的相位，帧推进统一由 process 末尾完成。
     */
    private fun acquirePhase(ext: FloatArray, pInt: Int, count: Int) {
        for (v in ext) {
            if (acqFilled >= ACQ_SAMPLES) break
            acqBuffer[acqFilled++] = v
        }
        if (acqFilled < minOf(ACQ_SAMPLES, pInt + chunkSamples)) return

        val n = minOf(acqFilled, pInt + chunkSamples - 1)
        val seg = FloatArray(n) { acqBuffer[it] }
        val corr = Fft.crossCorrelate(seg, template)
        var tplSq = 0.0
        for (v in template) tplSq += v.toDouble() * v
        var bestLag = -1
        var bestScore = 0f
        for (lag in 0 until pInt) {
            // 与 template 等长窗口的局部能量
            var localSq = 0.0
            var a = lag
            var len = 0
            while (len < template.size && a < seg.size) {
                localSq += seg[a].toDouble() * seg[a]
                a++; len++
            }
            if (len < template.size * 0.9) continue
            val score = (corr[lag].toDouble() / sqrt(tplSq * localSq.coerceAtLeast(1e-12))).toFloat()
            if (abs(score) > abs(bestScore)) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag >= 0 && abs(bestScore) >= LOCK_ON_CORR) {
            everAcquired = true
            // acqBuffer 从全局采样 0 开始填充：块首全局下标 = acqFilled-count
            var ph = (acqFilled - count) - bestLag.toDouble()
            ph %= pInt
            if (ph < 0.0) ph += pInt
            phase = ph
            confidence = abs(bestScore)
            ampSmooth = estimateAmplitude(ext, pInt, phase, 1f)  // 带符号
        }
    }

    /**
     * 局部 PLL：在预测相位 ±SEARCH 采样内做归一化互相关。
     * 注意只做「本块相位修正」(phase += bestD)，绝不推进帧相位——
     * 帧推进由 process 末尾唯一一处完成，避免双重推进。
     */
    private fun trackPhase(ext: FloatArray, pInt: Int, step: Float) {
        var bestD = 0f
        var bestScore = 0f
        var bestNum = 0.0
        var bestTplSq = 0.0
        var extSq = 0.0
        for (v in ext) extSq += v.toDouble() * v
        if (extSq < 1e-9) {
            confidence *= 0.8f
            return
        }
        for (d in -SEARCH..SEARCH) {
            var num = 0.0
            var tplSq = 0.0
            for (i in ext.indices) {
                val idx = (((phase + i * step + d).roundToInt()) % pInt + pInt) % pInt
                val t = template[idx].toDouble()
                num += ext[i].toDouble() * t
                tplSq += t * t
            }
            val score = (num / sqrt(extSq * tplSq.coerceAtLeast(1e-12))).toFloat()
            if (abs(score) > abs(bestScore)) {
                bestScore = score
                bestD = d.toFloat()
                bestNum = num
                bestTplSq = tplSq
            }
        }
        // 抛物线亚采样
        if (bestD.toInt() > -SEARCH && bestD.toInt() < SEARCH) {
            val dc = bestD.toInt()
            val sM = scoreAt(ext, pInt, dc - 1, step)
            val s0 = bestScore
            val sP = scoreAt(ext, pInt, dc + 1, step)
            val denom = sM - 2f * s0 + sP
            if (abs(denom) > 1e-8f) bestD += 0.5f * (sM - sP) / denom
        }
        confidence = abs(bestScore)

        if (abs(bestScore) >= LOCK_OFF_CORR) {
            // 最小二乘幅度系数（带符号：模板偶尔反相锁定时整体取负）
            val a = (bestNum / bestTplSq.coerceAtLeast(1e-12)).toFloat()
            ampSmooth = if (abs(ampSmooth) <= 1e-6f) a
            else ampSmooth * (1f - AMP_ALPHA) + a * AMP_ALPHA
            // PLL：由相位误差更新频偏
            val slipNow = bestD / ext.size
            slip = slip * (1 - SLIP_ALPHA) + slipNow * SLIP_ALPHA
            instPeriod = (period / (1f + slip.toFloat())).coerceIn(period * 0.95f, period * 1.05f)
            // 仅修正块首相位，不做帧推进
            var ph = (phase + bestD) % pInt
            if (ph < 0.0) ph += pInt
            phase = ph
        }
    }

    private fun scoreAt(ext: FloatArray, pInt: Int, d: Int, step: Float): Float {
        var num = 0.0
        var tplSq = 0.0
        var extSq = 0.0
        for (i in ext.indices) {
            val idx = (((phase + i * step + d).roundToInt()) % pInt + pInt) % pInt
            val t = template[idx].toDouble()
            num += ext[i].toDouble() * t
            tplSq += t * t
            extSq += ext[i].toDouble() * ext[i]
        }
        return (num / sqrt(extSq * tplSq.coerceAtLeast(1e-12))).toFloat()
    }

    private fun estimateAmplitude(
        ext: FloatArray, pInt: Int, phaseStart: Double, step: Float
    ): Float {
        var num = 0.0
        var tplSq = 0.0
        for (i in ext.indices) {
            val idx = (((phaseStart + i * step).roundToInt()) % pInt + pInt) % pInt
            val t = template[idx].toDouble()
            num += ext[i].toDouble() * t
            tplSq += t * t
        }
        return (num / tplSq.coerceAtLeast(1e-12)).toFloat()
    }

    private fun updateLockState() {
        if (confidence >= LOCK_ON_CORR) {
            lockCount++
            unlockCount = 0
        } else if (confidence < LOCK_OFF_CORR) {
            unlockCount++
            lockCount = 0
        }
        if (!locked && lockCount >= LOCK_CHUNKS) locked = true
        if (locked && unlockCount >= UNLOCK_CHUNKS) {
            locked = false
            lockCount = 0
            // 长失锁期间相位基准可能已漂移到搜索窗外，作废后重新全局捕获
            everAcquired = false
            acqFilled = 0
        }
    }

    private fun pushPlayed(v: Float) {
        playRing[playPos] = v
        playPos = (playPos + 1) % playRing.size
    }

    /**
     * 延迟对齐 NLMS 回声估计：回声 ≈ Σ_j w[j]·played[age = lead±taps/2]。
     * 标定给出主延迟，自适应权重建延迟附近的房间响应。
     *
     * 权重做非负投影：本机反噪经紧凑声学通路（扬声器→麦，低频段波长≫间距）
     * 回来的主传递增益为正；而外部周期声源恰好是播放反噪的"未来值"，若允许
     * 负权重，NLMS 会把外部声源本身误当回声整体抵消，使外部幅度不可观测、
     * 控制环坍缩。投影到 ≥0 后，权重只学习真实回声，闭环稳定。
     */
    private class DelayAec(private val taps: Int, private val delay: Int) {
        private val w = FloatArray(taps)
        fun reset() = w.fill(0f)

        fun processSample(mic: Float, ring: FloatArray, pos: Int): Float {
            val half = taps / 2
            val cap = ring.size
            var echoEst = 0f
            var energy = 0f
            for (j in 0 until taps) {
                val age = delay - half + j
                if (age < 0) continue
                var idx = pos - 1 - age
                idx = ((idx % cap) + cap) % cap
                val x = ring[idx]
                echoEst += w[j] * x
                energy += x * x
            }
            val err = mic - echoEst
            val g = AEC_STEP * err / (energy + 1e-3f)
            for (j in 0 until taps) {
                val age = delay - half + j
                if (age < 0) continue
                var idx = pos - 1 - age
                idx = ((idx % cap) + cap) % cap
                // 泄漏项：周期参考的自相关秩亏，不加泄漏时无真实回声支撑的
                // 权重会沿相关方向无限扩散；泄漏把权重收缩回 0，只保留真正
                // 持续解释残差的紧凑回声通路
                var nw = w[j] * (1f - AEC_LEAK) + g * ring[idx]
                if (nw < 0f) nw = 0f                 // 非负投影，见类注释
                if (nw > AEC_TAP_MAX) nw = AEC_TAP_MAX
                w[j] = nw
            }
            return err
        }
    }

    private companion object {
        const val AEC_TAPS = 16
        const val AEC_STEP = 0.1f
        const val AEC_LEAK = 0.05f
        const val AEC_TAP_MAX = 1.0f
        const val LOCK_ON_CORR = 0.35f
        const val LOCK_OFF_CORR = 0.22f
        const val LOCK_CHUNKS = 3
        const val UNLOCK_CHUNKS = 43      // 约 0.5s（43×11.6ms）
        const val SEARCH = 6
        const val ACQ_SAMPLES = 2048
        const val BASELINE_CHUNKS = 26   // 约 0.3s 基线
        const val AMP_ALPHA = 0.1f
        const val SLIP_ALPHA = 0.1
        // 门控时间常数：缓升约 1s、淡出约 0.15s（按 chunk=512@44.1k≈11.6ms）
        const val CHUNK_S = 512f / 44100f
        const val GATE_UP_ALPHA = CHUNK_S / (1.0f + CHUNK_S)
        const val GATE_DOWN_ALPHA = CHUNK_S / (0.15f + CHUNK_S)
    }
}
