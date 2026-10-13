package com.farsicaption.premium
import org.json.JSONArray
import org.json.JSONObject
internal object CaptionJson {
    fun encode(captions: List<CaptionItem>) = JSONArray().apply {
        captions.forEach { c -> put(JSONObject().put("text", c.text).put("start", c.start).put("end", c.end)
            .put("words", JSONArray().apply { c.words.forEach { w -> put(JSONObject().put("text", w.text).put("start", w.start).put("end", w.end)) } })) }
    }
    fun decode(array: JSONArray): List<CaptionItem> = (0 until array.length()).map { i ->
        val c = array.getJSONObject(i)
        val words = c.optJSONArray("words") ?: JSONArray()
        CaptionItem(c.getString("text"), c.getDouble("start"), c.getDouble("end"),
            (0 until words.length()).map { j -> val w = words.getJSONObject(j); TimedWord(w.getString("text"), w.getDouble("start"), w.getDouble("end")) })
    }
}
