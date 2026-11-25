// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.util.Log
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import kotlin.math.abs
import kotlin.math.ln

/**
 * Predictive touch targets: when a tap lands near the border between two letter keys, the
 * letter that is more likely to come next (judged from the current suggestions) wins.
 *
 * Each key has a fixed core (the middle [CORE] of its hitbox in each direction) that always
 * belongs to it, so a key can never become hard to hit; only the border strip moves, by at
 * most (0.5 - CORE) of a key width. Off unless suggestions for the current word are known.
 */
object KeyTargetModel {
    private const val TAG = "KeyTargets"
    /** Half extent of the fixed core, as a fraction of key size. 0.30 = middle 60%. */
    private const val CORE = 0.30f
    /** How far outside its own hitbox a neighbouring key may claim a touch. */
    private const val MARGIN = 0.20f
    /** Weight of the language prior against touch distance. */
    private const val LAMBDA = 0.30f
    /** Share of probability spread evenly over all letters (smoothing). */
    private const val UNIFORM = 0.5f
    private const val LETTERS = 26f
    private const val RANK_DECAY = 0.75f

    @JvmField @Volatile var enabled = true
    @JvmStatic @Volatile var typedWordSupplier: (() -> String?)? = null

    private class Snapshot(val prefix: String, val words: Array<String>, val weights: FloatArray)
    private class Dist(val word: String, val snap: Snapshot, val probs: HashMap<Int, Float>)

    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var cache: Dist? = null

    @JvmStatic
    fun clear() {
        snapshot = null
    }

    @JvmStatic
    fun onSuggestions(sw: SuggestedWords?) {
        if (sw == null || sw.isEmpty || sw.isPunctuationSuggestions) { snapshot = null; return }
        val style = sw.mInputStyle
        val prediction = sw.isPrediction
        if (!prediction && style != SuggestedWords.INPUT_STYLE_TYPING) { snapshot = null; return }
        val prefix = if (prediction) "" else (sw.mTypedWordInfo?.mWord ?: run { snapshot = null; return })
        val words = ArrayList<String>()
        val weights = ArrayList<Float>()
        var w = 1f
        for (i in 0 until sw.size()) {
            val info = sw.getInfo(i)
            val kind = info.kind
            if (kind == SuggestedWordInfo.KIND_TYPED && !sw.mTypedWordValid) continue
            if (kind == SuggestedWordInfo.KIND_HARDCODED || kind == SuggestedWordInfo.KIND_APP_DEFINED
                || kind == SuggestedWordInfo.KIND_SHORTCUT) continue
            val word = info.mWord ?: continue
            words.add(word.lowercase())
            weights.add(w)
            w *= RANK_DECAY
        }
        snapshot = if (words.isEmpty()) null
            else Snapshot(prefix.lowercase(), words.toTypedArray(), weights.toFloatArray())
    }

    private fun distribution(word: String): Dist? {
        val snap = snapshot ?: return null
        val c = cache
        if (c != null && c.snap === snap && c.word == word) return c
        if (!word.startsWith(snap.prefix)) return null
        val n = word.length
        var total = 0f
        var letterMass = 0f
        val mass = HashMap<Int, Float>()
        for (i in snap.words.indices) {
            val cand = snap.words[i]
            if (!cand.startsWith(word)) continue
            val wt = snap.weights[i]
            total += wt
            if (cand.length > n) {
                val cp = cand.codePointAt(n)
                if (Character.isLetter(cp)) {
                    mass[cp] = (mass[cp] ?: 0f) + wt
                    letterMass += wt
                }
            }
        }
        if (letterMass <= 0f) return null
        val probs = HashMap<Int, Float>(mass.size * 2)
        for ((cp, m) in mass) probs[cp] = (1f - UNIFORM) * m / total + UNIFORM / LETTERS
        return Dist(word, snap, probs).also { cache = it }
    }

    private fun isLetterKey(key: Key) = key.code > 0 && Character.isLetter(key.code)

    /** Returns the key to use for a touch at (x, y) that the plain geometry assigned to [primary]. */
    @JvmStatic
    fun adjust(primary: Key, x: Int, y: Int, nearest: List<Key>): Key {
        if (!enabled || !isLetterKey(primary)) return primary
        val word = typedWordSupplier?.invoke()?.lowercase() ?: return primary
        val dist = distribution(word) ?: return primary
        val hb = primary.hitBox
        val pw = hb.width().toFloat()
        val ph = hb.height().toFloat()
        if (pw <= 0f || ph <= 0f) return primary
        val pdx = (x - hb.exactCenterX()) / pw
        val pdy = (y - hb.exactCenterY()) / ph
        if (abs(pdx) <= CORE && abs(pdy) <= CORE) return primary
        var best = primary
        var bestScore = score(pdx, pdy, prob(dist, primary))
        for (key in nearest) {
            if (key === primary || !isLetterKey(key)) continue
            val kb = key.hitBox
            val kw = kb.width().toFloat()
            val kh = kb.height().toFloat()
            if (kw <= 0f || kh <= 0f) continue
            if (x < kb.left - MARGIN * kw || x > kb.right + MARGIN * kw
                || y < kb.top - MARGIN * kh || y > kb.bottom + MARGIN * kh) continue
            val s = score((x - kb.exactCenterX()) / kw, (y - kb.exactCenterY()) / kh, prob(dist, key))
            if (s > bestScore) { bestScore = s; best = key }
        }
        if (best !== primary && Log.isLoggable(TAG, Log.DEBUG))
            Log.d(TAG, "moved ${primary.label} -> ${best.label} (dx=%.2f dy=%.2f)".format(pdx, pdy))
        return best
    }

    private fun prob(dist: Dist, key: Key): Float =
        dist.probs[Character.toLowerCase(key.code)] ?: (UNIFORM / LETTERS)

    // Gaussian touch model with sigma = half a key, plus the weighted log prior.
    private fun score(dx: Float, dy: Float, p: Float): Float =
        -2f * (dx * dx + dy * dy) + LAMBDA * ln(p)
}
