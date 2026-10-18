package dev.corvus.laya

import kotlin.math.exp

data class ReflexEvaluation(
    val batch: TokenizedBatch,
    val probabilities: FloatArray,
    val decision: DecisionResult,
    val selectedNodeId: Int?
)

interface ReflexEngine : AutoCloseable {
    fun encodePrompt(text: String, maxLen: Int = 128, injectMasks: Boolean = true): TokenizedBatch
    fun calibrate(rawLogits: FloatArray, temperature: Float = 1.25f): FloatArray
    fun evaluateProbabilities(
        probabilities: FloatArray,
        threshold: Float = 0.65f,
        temperature: Float = 1.25f
    ): DecisionResult

    fun evaluate(
        prompt: String,
        candidateNodeIds: List<Int>,
        rawLogits: FloatArray,
        temperature: Float = 1.25f,
        threshold: Float = 0.65f
    ): ReflexEvaluation
}

class JniReflexEngine(
    jsonVocab: String? = null,
    val defaultTemperature: Float = 1.25f,
    val defaultThreshold: Float = 0.65f
) : ReflexEngine {

    private val nativeEngine: LayaNativeEngine? = try {
        LayaNativeEngine(jsonVocab)
    } catch (_: Throwable) {
        null
    }

    override fun encodePrompt(text: String, maxLen: Int, injectMasks: Boolean): TokenizedBatch {
        nativeEngine?.let { return it.encode(text, maxLen, injectMasks) }
        return fallbackEncode(text, maxLen, injectMasks)
    }

    override fun calibrate(rawLogits: FloatArray, temperature: Float): FloatArray {
        nativeEngine?.let { return it.calibrateLogits(rawLogits, temperature) }
        return fallbackCalibrate(rawLogits, temperature)
    }

    override fun evaluateProbabilities(
        probabilities: FloatArray,
        threshold: Float,
        temperature: Float
    ): DecisionResult {
        if (probabilities.isEmpty()) {
            return DecisionResult(-1, 0f, true, temperature)
        }
        var bestIdx = 0
        var bestScore = probabilities[0]
        for (i in 1 until probabilities.size) {
            if (probabilities[i] > bestScore) {
                bestScore = probabilities[i]
                bestIdx = i
            }
        }
        val isNoul = bestScore < threshold
        return DecisionResult(
            choice = if (isNoul) -1 else bestIdx,
            score = bestScore,
            noul = isNoul,
            temperature = temperature
        )
    }

    override fun evaluate(
        prompt: String,
        candidateNodeIds: List<Int>,
        rawLogits: FloatArray,
        temperature: Float,
        threshold: Float
    ): ReflexEvaluation {
        val batch = encodePrompt(prompt, maxLen = 128, injectMasks = true)
        val probs = calibrate(rawLogits, temperature)
        val decision = evaluateProbabilities(probs, threshold, temperature)
        val selectedNodeId = if (!decision.noul && decision.choice in candidateNodeIds.indices) {
            candidateNodeIds[decision.choice]
        } else {
            null
        }
        return ReflexEvaluation(
            batch = batch,
            probabilities = probs,
            decision = decision,
            selectedNodeId = selectedNodeId
        )
    }

    override fun close() {
        nativeEngine?.close()
    }

    private fun fallbackEncode(text: String, maxLen: Int, injectMasks: Boolean): TokenizedBatch {
        if (maxLen <= 0) {
            return TokenizedBatch(LongArray(0), LongArray(0), LongArray(0), IntArray(0))
        }
        val tokens = mutableListOf<Long>()
        val maskIndices = mutableListOf<Int>()
        tokens.add(101L) // [CLS]

        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (word in words) {
            if (injectMasks && word.startsWith("[#") && word.endsWith("]")) {
                maskIndices.add(tokens.size)
                tokens.add(103L) // [MASK]
            }
            val hashId = 1000L + (word.hashCode().toLong() and 0x7FFFFFFFL) % 29000L
            tokens.add(hashId)
        }
        tokens.add(102L) // [SEP]

        val inputIds = LongArray(maxLen)
        val attentionMask = LongArray(maxLen)
        val tokenTypeIds = LongArray(maxLen)
        val limit = minOf(tokens.size, maxLen)
        for (i in 0 until limit) {
            inputIds[i] = tokens[i]
            attentionMask[i] = 1L
        }
        val validMasks = maskIndices.filter { it < maxLen }.toIntArray()
        return TokenizedBatch(inputIds, attentionMask, tokenTypeIds, validMasks)
    }

    private fun fallbackCalibrate(rawLogits: FloatArray, temperature: Float): FloatArray {
        if (rawLogits.isEmpty()) return FloatArray(0)
        val temp = if (temperature <= 0f) 1.0f else temperature
        val maxLogit = rawLogits.maxOrNull() ?: 0f
        val exps = FloatArray(rawLogits.size)
        var sum = 0.0
        for (i in rawLogits.indices) {
            val v = exp(((rawLogits[i] - maxLogit) / temp).toDouble())
            exps[i] = v.toFloat()
            sum += v
        }
        for (i in exps.indices) {
            exps[i] = (exps[i] / sum).toFloat()
        }
        return exps
    }
}
