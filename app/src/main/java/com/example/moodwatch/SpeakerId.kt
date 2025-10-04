package com.example.moodwatch

class SpeakerId {
    private var counselor: FloatArray? = null
    private var student: FloatArray? = null

    fun enrollCounselor(samples: List<FloatArray>) { counselor = avg(samples) }
    fun enrollStudent(samples: List<FloatArray>)   { student   = avg(samples) }

    fun label(spk: FloatArray?): String {
        if (spk == null) return "unknown"
        val c = counselor?.let { cosine(it, spk) } ?: -1f
        val s = student?.let   { cosine(it, spk) } ?: -1f
        return if (s >= c) "student" else "counselor"
    }

    private fun avg(list: List<FloatArray>): FloatArray {
        require(list.isNotEmpty())
        val n = list.first().size
        val out = FloatArray(n)
        for (v in list) {
            for (i in 0 until n) {
                out[i] = out[i] + v[i] // explicit to avoid “No set method …” on Lists
            }
        }
        val size = list.size.toFloat()
        for (i in 0 until n) out[i] = out[i] / size
        return l2(out)
    }

    private fun l2(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        val norm = kotlin.math.sqrt(n.toDouble()).toFloat().coerceAtLeast(1e-6f)
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / norm
        return out
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        val an = l2(a)
        val bn = l2(b)
        var dot = 0f
        val len = an.size.coerceAtMost(bn.size)
        for (i in 0 until len) dot += an[i] * bn[i]
        return dot
    }
}
