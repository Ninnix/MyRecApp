package org.myrec.app

import android.hardware.camera2.CameraCharacteristics
import kotlin.math.roundToInt

internal object SoftwareHdr {
    fun exposureSteps(camera: CameraCharacteristics, stops: Double = .85): Int {
        val step = camera.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toDouble() ?: return 0
        val range = camera.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return 0
        if (!step.isFinite() || step <= 0 || range.lower >= 0) return 0
        return (-stops/step).roundToInt().coerceIn(range.lower,-1)
    }

    fun strength(camera: CameraCharacteristics): Float {
        val steps = exposureSteps(camera)
        if (steps == 0) return 0f
        val ev = -steps * camera.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)!!.toFloat()
        return (.85f * ev).coerceAtMost(1f)
    }

    // Lift midtones from the shorter exposure. Anchor the noise floor and keep
    // a gradual shoulder at white. Scale chroma around the new luma to fit SDR.
    val shader = """
        uniform sampler2D exposureMap;
        uniform float useExposureMap;
        vec3 recoverExposure(vec3 rgb, float strength, vec2 uv) {
            float luma = dot(rgb, vec3(.2126, .7152, .0722));
            float toe = .12;
            if (useExposureMap > .5) {
                vec2 local = texture2D(exposureMap, uv).rg;
                float lift = mix(1.38, .55, smoothstep(.16, .68, local.r));
                float confidence = (1. - .72 * smoothstep(.12, .48, abs(luma - local.r)))
                                 * (1. - .60 * smoothstep(.09, .24, abs(local.r - local.g)));
                strength *= mix(1., lift, confidence);
                toe += .06 * (1. - smoothstep(.055, .22, local.r));
            }
            float headroom = 1. - luma;
            float mapped = luma + strength * smoothstep(.025, toe, luma) * luma * headroom * headroom;
            float peak = max(rgb.r, max(rgb.g, rgb.b));
            float chroma = min(mapped / max(luma, .0001), (1. - mapped) / max(peak - luma, .0001));
            return vec3(mapped) + (rgb - luma) * chroma;
        }
    """.trimIndent()
}
