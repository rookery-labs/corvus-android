package dev.corvus.laya

class LayaNativeEngine(jsonVocab: String? = null) : AutoCloseable {
    private var nativeHandle: Long = 0L

    init {
        System.loadLibrary("laya_onnx")
        nativeHandle = nativeInitTokenizer(jsonVocab)
    }

    fun encode(text: String, maxLen: Int = 128, injectMasks: Boolean = true): TokenizedBatch {
        check(nativeHandle != 0L) { "LayaNativeEngine is already closed" }
        return nativeEncode(nativeHandle, text, maxLen, injectMasks)
    }

    fun calibrateLogits(rawLogits: FloatArray, temperature: Float = 1.25f): FloatArray {
        return nativeCalibrateLogits(rawLogits, temperature)
    }

    fun evaluateDecision(probabilities: FloatArray, threshold: Float = 0.65f, temperature: Float = 1.25f): DecisionResult {
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

    override fun close() {
        if (nativeHandle != 0L) {
            nativeFreeTokenizer(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeInitTokenizer(jsonVocab: String?): Long
    private external fun nativeFreeTokenizer(handle: Long)
    private external fun nativeEncode(handle: Long, text: String, maxLen: Int, injectMasks: Boolean): TokenizedBatch
    private external fun nativeCalibrateLogits(rawLogits: FloatArray, temperature: Float): FloatArray
}
