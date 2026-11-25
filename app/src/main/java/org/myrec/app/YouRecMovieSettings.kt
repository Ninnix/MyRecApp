package org.myrec.app

import org.json.JSONObject

internal object YouRecMovieSettings {
    fun encode(project: Project): JSONObject = JSONObject()
        .put("codec", if (project.movieSettings.color == ExportColor.SDR) "H.264" else "H.265")
        .put("resolution", project.movieSettings.resolution.label)
        .put("frameRate", (project.movieSettings.fps ?: project.fps).takeIf { it in MovieSettings.frameRates } ?: 30)

    fun decode(settings: JSONObject?): MovieSettings {
        if (settings == null) return MovieSettings()
        val codec = when (settings.optString("codec","H.264")) {
            "H.264" -> ExportColor.SDR
            "H.265" -> ExportColor.SDR_HEVC
            else -> error("Unknown YouRec export codec")
        }
        val resolution = ExportResolution.entries.firstOrNull { it.label == settings.optString("resolution","Project size") }
            ?: error("Unknown YouRec export resolution")
        val rate = settings.optInt("frameRate",30)
        require(rate in MovieSettings.frameRates) { "Unknown YouRec export frame rate" }
        return MovieSettings(resolution, rate, codec)
    }
}
