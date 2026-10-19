package dev.corvus.laya

data class TokenizedBatch(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray,
    val maskIndices: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TokenizedBatch) return false
        return inputIds.contentEquals(other.inputIds) &&
                attentionMask.contentEquals(other.attentionMask) &&
                tokenTypeIds.contentEquals(other.tokenTypeIds) &&
                maskIndices.contentEquals(other.maskIndices)
    }

    override fun hashCode(): Int {
        var result = inputIds.contentHashCode()
        result = 31 * result + attentionMask.contentHashCode()
        result = 31 * result + tokenTypeIds.contentHashCode()
        result = 31 * result + maskIndices.contentHashCode()
        return result
    }
}

data class DecisionResult(
    val choice: Int,
    val score: Float,
    val noul: Boolean,
    val temperature: Float
)
