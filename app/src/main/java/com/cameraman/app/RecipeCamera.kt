package com.cameraman.app

import android.graphics.RectF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Most phone cameras are around f/1.8; used when the camera does not report its aperture. */
private const val DEFAULT_APERTURE = 1.8f

/** What the camera's auto exposure chose for the latest frame. */
data class MeterReading(val exposureSeconds: Float, val iso: Int, val aperture: Float) {
    /** Light level of the scene as EV at ISO 100: about 15 in sun, 12 in shade, 7 indoors, 3 at night. */
    val ev100: Float get() = log2(aperture * aperture / exposureSeconds) - log2(iso / 100f)
}

/** Reads exposure time, ISO and aperture from every preview frame (Camera2 capture results). */
class LightMeter : CameraCaptureSession.CaptureCallback() {
    @Volatile
    var latest: MeterReading? = null
        private set

    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
        val exposureNanos = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
        if (exposureNanos <= 0L || iso <= 0) return
        val aperture = result.get(CaptureResult.LENS_APERTURE) ?: DEFAULT_APERTURE
        latest = MeterReading(exposureNanos / 1e9f, iso, aperture)
    }
}

/** Preview use case that also feeds every frame's exposure to [lightMeter]. */
@OptIn(ExperimentalCamera2Interop::class)
fun previewWithMeter(lightMeter: LightMeter): Preview {
    val builder = Preview.Builder()
    Camera2Interop.Extender(builder).setSessionCaptureCallback(lightMeter)
    return builder.build()
}

/** Manual shutter and ISO for one photo. */
data class ManualExposure(val exposureSeconds: Float, val iso: Int)

/**
 * Sets the Camera2 options the app controls: the recipe's white balance preset, and a manual shutter/ISO
 * while a recipe photo is taken. Replaces any options set before.
 */
@OptIn(ExperimentalCamera2Interop::class)
fun applyCameraOptions(camera: Camera, whiteBalanceMode: Int?, manual: ManualExposure?) {
    val options = CaptureRequestOptions.Builder()
    val availableModes = Camera2CameraInfo.from(camera.cameraInfo)
        .getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
    if (whiteBalanceMode != null && availableModes?.contains(whiteBalanceMode) == true) {
        options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, whiteBalanceMode)
    }
    if (manual != null) {
        options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, (manual.exposureSeconds * 1e9).toLong())
        options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, manual.iso)
    }
    Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options.build())
}

/**
 * Shutter and ISO for a recipe photo, keeping the brightness auto exposure chose but never using a slower
 * shutter than the recipe. Returns null when auto is already fast enough or the camera has no manual control.
 */
@OptIn(ExperimentalCamera2Interop::class)
fun manualExposureFor(camera: Camera, scene: Scene, meter: MeterReading): ManualExposure? {
    if (meter.exposureSeconds <= scene.shutterSeconds * 1.05f) return null
    val cameraInfo = Camera2CameraInfo.from(camera.cameraInfo)
    val capabilities = cameraInfo.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
    if (capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) != true) return null
    val isoRange = cameraInfo.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return null
    // Same amount of light: ISO × time stays the same.
    val light = meter.iso * meter.exposureSeconds
    val iso = (light / scene.shutterSeconds).roundToInt()
    return if (iso > isoRange.upper) {
        // Not enough light for the recipe shutter: use the highest ISO and a shutter between the two.
        ManualExposure(light / isoRange.upper, isoRange.upper)
    } else {
        ManualExposure(scene.shutterSeconds, iso.coerceAtLeast(isoRange.lower))
    }
}

/** Sets exposure compensation (in stops) as close as the camera allows. */
fun setEvCompensation(camera: Camera, ev: Float) {
    val exposureState = camera.cameraInfo.exposureState
    if (!exposureState.isExposureCompensationSupported) return
    val step = exposureState.exposureCompensationStep.toFloat()
    if (step <= 0f) return
    val range = exposureState.exposureCompensationRange
    camera.cameraControl.setExposureCompensationIndex((ev / step).roundToInt().coerceIn(range.lower, range.upper))
}

/** Focuses and meters on the face ([faceOnScreen] in 0..1 of the preview). Goes back to normal after a few seconds. */
fun focusOnFace(camera: Camera, previewView: PreviewView, faceOnScreen: RectF) {
    if (previewView.width == 0 || previewView.height == 0) return
    val point = previewView.meteringPointFactory.createPoint(
        faceOnScreen.centerX() * previewView.width,
        faceOnScreen.centerY() * previewView.height
    )
    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
        .setAutoCancelDuration(3, TimeUnit.SECONDS)
        .build()
    camera.cameraControl.startFocusAndMetering(action)
}
