package com.deffrow.akuji

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

class AkujiVoice(context: Context) : TextToSpeech.OnInitListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val textToSpeech = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pending: PendingSpeech? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) return

        textToSpeech.language = Locale.US
        textToSpeech.setSpeechRate(0.92f)
        textToSpeech.setPitch(0.96f)
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post {
                    pending
                        ?.takeIf { it.utteranceId == utteranceId }
                        ?.let {
                            it.onStart()
                            it.onSpeechPulse()
                        }
                }
            }

            override fun onRangeStart(
                utteranceId: String?,
                start: Int,
                end: Int,
                frame: Int,
            ) {
                mainHandler.post {
                    pending
                        ?.takeIf { it.utteranceId == utteranceId }
                        ?.onSpeechPulse
                        ?.invoke()
                }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    pending
                        ?.takeIf { it.utteranceId == utteranceId }
                        ?.let {
                            it.onDone()
                            pending = null
                        }
                }
            }

            @Deprecated("Deprecated in Android")
            override fun onError(utteranceId: String?) {
                finishWithError(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finishWithError(utteranceId)
            }

            private fun finishWithError(utteranceId: String?) {
                mainHandler.post {
                    pending
                        ?.takeIf { it.utteranceId == utteranceId }
                        ?.let {
                            it.onError()
                            pending = null
                        }
                }
            }
        })

        pending?.let(::speakNow)
    }

    fun speak(
        text: String,
        onStart: () -> Unit,
        onSpeechPulse: () -> Unit = {},
        onDone: () -> Unit,
        onError: () -> Unit,
    ) {
        pending = PendingSpeech(
            utteranceId = UUID.randomUUID().toString(),
            text = text,
            onStart = onStart,
            onSpeechPulse = onSpeechPulse,
            onDone = onDone,
            onError = onError,
        )
        if (ready) speakNow(pending ?: return)
    }

    private fun speakNow(request: PendingSpeech) {
        textToSpeech.speak(
            request.text,
            TextToSpeech.QUEUE_FLUSH,
            Bundle(),
            request.utteranceId,
        )
    }

    fun stop() {
        textToSpeech.stop()
        pending = null
    }

    fun shutdown() {
        textToSpeech.stop()
        textToSpeech.shutdown()
    }

    private data class PendingSpeech(
        val utteranceId: String,
        val text: String,
        val onStart: () -> Unit,
        val onSpeechPulse: () -> Unit,
        val onDone: () -> Unit,
        val onError: () -> Unit,
    )
}
