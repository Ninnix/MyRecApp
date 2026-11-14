package org.myrec.app

import org.json.JSONArray
import org.json.JSONObject

object PauseJson {
    fun encode(review: PauseReview) = JSONObject().apply {
        put("automatic", review.automatic); put("threshold", review.thresholdDb); put("minimum", review.minimum)
        put("before", review.before); put("after", review.after)
        put("start", review.analyzed.start); put("end", review.analyzed.end)
        put("candidates", JSONArray().apply { for (c in review.candidates) put(JSONObject().apply {
            put("id", c.id); put("start", c.raw.start); put("end", c.raw.end); put("enabled", c.enabled)
        }) })
    }
    fun decode(o: JSONObject): PauseReview {
        val array = o.getJSONArray("candidates")
        require(array.length() <= 10000)
        return PauseReview(o.getBoolean("automatic"), o.getDouble("threshold"), o.getDouble("minimum"),
            o.getDouble("before"), o.getDouble("after"), SourceInterval(o.getDouble("start"), o.getDouble("end")),
            (0 until array.length()).map { val c = array.getJSONObject(it)
                PauseCandidate(c.getString("id"), SourceInterval(c.getDouble("start"), c.getDouble("end")), c.getBoolean("enabled")) })
            .also { require(it.valid()) { "Invalid pause review" } }
    }
}
