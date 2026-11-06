package com.simform.audio_waveforms

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import com.simform.audio_waveforms.Constants.LOG_TAG
import com.simform.audio_waveforms.encoders.*
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.io.File
import kotlin.math.sqrt

class AudioRecorder : PluginRegistry.RequestPermissionsResultListener {
    private var permissions = arrayOf(Manifest.permission.RECORD_AUDIO)
    private var audioRecord: AudioRecord? = null
    private var channelConfig: Int = AudioFormat.CHANNEL_IN_MONO
    private var audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize: Int? = null
    private var recorderState: RecorderState = RecorderState.Disposed
    private var filePath: String? = null
    private var recordingThread: Thread? = null
    private var recorderSettings: RecorderSettings? = null
    private var encoder: Encoder? = null
    lateinit var channel: MethodChannel
    private var commonEncoder = CommonEncoder()
    private var wavEncoder: WavEncoder? = null
    private var successCallback: RequestPermissionsSuccessCallback? = null
    private var totalSamples = 0L
    private val channelCount: Int
        get() = when (channelConfig) {
            AudioFormat.CHANNEL_IN_MONO -> 1
            AudioFormat.CHANNEL_IN_STEREO -> 2
            else -> 1
        }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ): Boolean {
        return if (requestCode == Constants.RECORD_AUDIO_REQUEST_CODE) {
            successCallback?.onSuccess(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
    }

    private fun isPermissionGranted(activity: Activity?): Boolean {
        val result = ActivityCompat.checkSelfPermission(activity!!, permissions[0])
        return result == PackageManager.PERMISSION_GRANTED
    }

    fun checkPermission(
        result: Result, activity: Activity?, successCallback: RequestPermissionsSuccessCallback
    ) {
        this.successCallback = successCallback
        if (!isPermissionGranted(activity)) {
            activity?.let {
                ActivityCompat.requestPermissions(
                    it, permissions, Constants.RECORD_AUDIO_REQUEST_CODE
                )
            }
        } else {
            result.success(true)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("MissingPermission")
    fun initRecorder(
        recorderSettings: RecorderSettings, channel: MethodChannel, result: Result
    ) {
        filePath = recorderSettings.path
        if (filePath == null) return
        this.channel = channel
        bufferSize =
            AudioRecord.getMinBufferSize(recorderSettings.sampleRate, channelConfig, audioFormat)

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            result.error(
                LOG_TAG,
                "Invalid buffer size: $bufferSize",
                null
            )
        }
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                recorderSettings.sampleRate,
                channelConfig,
                audioFormat,
                bufferSize!!
            )
        } catch (e: Exception) {
            result.error(
                LOG_TAG,
                "Error initializing AudioRecord: ${e.message}",
                null
            )
            return
        }
        this.recorderSettings = recorderSettings
        encoder = recorderSettings.encoder
        recorderState = RecorderState.Initialised
        result.success(true)
    }

    fun start(result: Result) {
        if (recorderSettings == null || bufferSize == null) {
            result.error(
                LOG_TAG,
                "recorder settings is null or bufferSize is null",
                "recorderSettings: $recorderSettings, bufferSize: $bufferSize"
            )
            return
        }
        lastLevelAtMs = 0L
        audioRecord?.startRecording()
        recorderState = RecorderState.Recording
        if (encoder?.encodeForWav == true) {
            wavEncoder = WavEncoder(
                wavFile = File(recorderSettings!!.path!!),
                sampleRate = recorderSettings!!.sampleRate
            )
            wavEncoder?.start(result)
        } else {
            commonEncoder.initCodec(recorderSettings = recorderSettings!!, result = result) {
                recordingThread?.join()
            }
        }
        val buffer = ByteArray(bufferSize!!)
        recordingThread = Thread {
            while (recorderState == RecorderState.Recording || recorderState == RecorderState.Paused) {
                if (recorderState == RecorderState.Recording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                    if (read > 0) {
                        val audioData = buffer.copyOf(read)
                        if (encoder?.encodeForWav == true) {
                            wavEncoder?.writePcmData(audioData)
                        } else {
                            try {
                                commonEncoder.queueInputBuffer(audioData)
                            } catch (e: Exception) {
                                // A codec error used to kill this thread, so
                                // the wave froze and the rest of the take was
                                // never written. Keep reading the microphone.
                                Log.e(LOG_TAG, "Encoder dropped a buffer: ${e.message}")
                            }
                        }
                        val rms = calculateRms(audioData, read)
                        totalSamples += read / channelCount
                        val durationSec =
                            totalSamples.toDouble() / (recorderSettings?.sampleRate
                                ?: Constants.DEFAULT_SAMPLE_RATE)
                        val milliSeconds = (durationSec * 1000).toLong()
                        sendLevelToFlutter(rms, milliSeconds)
                    }
                } else {
                    // Paused: stay in the loop so resume keeps the same file,
                    // but don't spin the CPU while the user is not talking.
                    try {
                        Thread.sleep(50)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }
        recordingThread?.start()
        result.success(true)
    }

    fun stop(result: Result) {
        try {
            val sampleRate = recorderSettings?.sampleRate ?: Constants.DEFAULT_SAMPLE_RATE
            val capturedMs = if (sampleRate > 0) {
                (totalSamples * 1000L / sampleRate).toInt()
            } else {
                -1
            }
            audioRecord?.stop()
            totalSamples = 0L
            recorderState = RecorderState.Stopped
            if (encoder?.encodeForWav == true) {
                wavEncoder?.stop(result)
                recordingThread?.join()
                sendRecordingResult(result, capturedMs)
            } else {
                commonEncoder.setOnEncodingCompleted {
                    sendRecordingResult(result, capturedMs)
                }
                commonEncoder.signalToStop()
            }

        } catch (e: Exception) {
            result.error(LOG_TAG, e.message, "An error occurred while stopping the recorder")
            return
        }
        release()
    }

    private fun sendRecordingResult(result: Result, capturedMs: Int) {
        // The sample clock is already known. Opening the file with
        // MediaMetadataRetriever here is what held "Saving…" on screen.
        val hashMap = HashMap<String, Any?>()
        hashMap[Constants.resultFilePath] = recorderSettings?.path
        hashMap[Constants.resultDuration] = capturedMs
        result.success(hashMap)
    }

    private var lastLevelAtMs = 0L

    /// Level only, and not on every buffer. Shipping the raw PCM on each read
    /// filled the platform channel until stop() sat behind it and the wave
    /// stopped moving, which the app then treated as a dead microphone.
    private fun sendLevelToFlutter(rms: Double, milliSeconds: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLevelAtMs < 100L) return
        lastLevelAtMs = now
        val args: MutableMap<String, Any?> = HashMap()
        args[Constants.normalisedRms] = rms
        args[Constants.recordedDuration] = milliSeconds
        Handler(Looper.getMainLooper()).post {
            channel.invokeMethod(Constants.onAudioChunk, args)
        }
    }

    private fun calculateRms(chunk: ByteArray, size: Int): Double {
        var sum = 0.0
        var count = 0

        val adjustedSize = if (size % 2 == 0) size else size - 1
        for (i in 0 until adjustedSize step 2) {
            val low = chunk[i].toInt() and 0xff
            val high = chunk[i + 1].toInt()
            val sample = (high shl 8) or low

            sum += sample * sample.toDouble()
            count++
        }

        val normalisedRms = sqrt(sum / count) / 32767.0
        return normalisedRms
    }

    fun pause(result: Result) {
        recorderState = RecorderState.Paused
        result.success(false)
    }

    fun resume(result: Result) {
        recorderState = RecorderState.Recording
        result.success(true)
    }

    fun release() {
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error releasing AudioRecord: ${e.message}")
        }

        audioRecord = null
        recorderState = RecorderState.Disposed
    }
}
