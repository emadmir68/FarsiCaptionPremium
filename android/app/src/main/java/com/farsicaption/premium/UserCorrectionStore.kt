package com.farsicaption.premium

import android.content.Context

class UserCorrectionStore(context: Context) {

    private val prefs = context.getSharedPreferences("persian_user_corrections", Context.MODE_PRIVATE)

    fun all(): Map<String, String> {
        return prefs.all
            .mapNotNull { (key, value) ->
                val v = value as? String ?: return@mapNotNull null
                if (key.isBlank() || v.isBlank()) null else key to v
            }
            .toMap()
    }

    fun size(): Int = all().size

    fun learn(originalRaw: String, editedRaw: String) {
        val original = PersianTextNormalizer.normalize(originalRaw)
        val edited = PersianTextNormalizer.normalize(editedRaw)
        if (original.isBlank() || edited.isBlank() || original == edited) return
        if (original.length > 140 || edited.length > 140) return

        val editor = prefs.edit()

        // Learn short phrase corrections directly.
        if (original.split(' ').size <= 7 && edited.split(' ').size <= 7) {
            editor.putString(original, edited)
        }

        val before = original.split(Regex("\s+")).filter { it.isNotBlank() }
        val after = edited.split(Regex("\s+")).filter { it.isNotBlank() }

        // 1-to-1 word corrections.
        if (before.size == after.size) {
            before.zip(after).forEach { (a, b) ->
                if (a != b && a.length in 2..24 && b.length in 2..24) {
                    editor.putString(a, b)
                }
            }
        }

        // Learn common "two words became one Persian word" fixes,
        // e.g. "هم کاران" -> "همکاران".
        var i = 0
        var j = 0
        while (i < before.size && j < after.size) {
            if (before[i] == after[j]) {
                i++
                j++
                continue
            }

            if (i + 1 < before.size) {
                val joined = (before[i] + before[i + 1])
                    .replace("‌", "")
                    .replace(" ", "")
                val target = after[j].replace("‌", "").replace(" ", "")
                if (joined == target) {
                    editor.putString(before[i] + " " + before[i + 1], after[j])
                    i += 2
                    j += 1
                    continue
                }
            }

            i++
            j++
        }

        editor.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
