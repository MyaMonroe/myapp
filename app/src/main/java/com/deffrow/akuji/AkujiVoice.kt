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
                mainHandler.post { pending?.onStart?.invoke() }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    pending?.onDone?.invoke()
                    pending = null
                }
            }

            @Deprecated("Deprecated in Android")
            override fun onError(utteranceId: String?) {
                finishWithError()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finishWithError()
            }

            private fun finishWithError() {
                mainHandler.post {
                    pending?.onError?.invoke()
                    pending = null
                }
            }
        })

        pending?.let(::speakNow)
    }

    fun speak(
        text: String,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: () -> Unit,
    ) {
        pending = PendingSpeech(text, onStart, onDone, onError)
        if (ready) speakNow(pending ?: return)
    }

    private fun speakNow(request: PendingSpeech) {
        val utteranceId = UUID.randomUUID().toString()
        textToSpeech.speak(
            request.text,
            TextToSpeech.QUEUE_FLUSH,
            Bundle(),
            utteranceId,
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
        val text: String,
        val onStart: () -> Unit,
        val onDone: () -> Unit,
        val onError: () -> Unit,
    )
}
