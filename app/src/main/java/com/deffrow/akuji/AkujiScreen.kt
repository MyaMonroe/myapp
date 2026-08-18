package com.deffrow.akuji

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.launch

private enum class AkujiState(val label: String) {
    Awake("AWAKE"),
    Listening("LISTENING"),
    Thinking("THINKING"),
    Speaking("SPEAKING"),
    Importing("COPYING MODEL"),
    Loading("LOADING MODEL"),
    ModelReady("LOCAL MODEL READY"),
    BrainError("MODEL ERROR"),
    MicrophoneBlocked("MICROPHONE BLOCKED"),
    VoiceUnavailable("VOICE UNAVAILABLE"),
}

@Composable
fun AkujiApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val memory = remember { AkujiMemoryStore(context) }
    val brain = remember { AkujiLocalModelBrain(context, memory) }
    val voice = remember { AkujiVoice(context) }

    var state by remember { mutableStateOf(AkujiState.Awake) }
    var caption by remember {
        mutableStateOf(
            if (brain.hasModel) "AKUJI's local model is connected."
            else "AKUJI's body, voice, and memory are ready for a local model.",
        )
    }

    DisposableEffect(voice, brain) {
        onDispose {
            voice.shutdown()
            brain.close()
        }
    }

    LaunchedEffect(brain) {
        if (brain.hasModel) {
            state = AkujiState.Loading
            brain.prepare()
                .onSuccess { state = AkujiState.ModelReady }
                .onFailure {
                    caption = it.message ?: "The local model could not start."
                    state = AkujiState.BrainError
                }
        }
    }

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            state = AkujiState.Importing
            caption = "Copying the local model into AKUJI..."
            brain.importModel(uri) { progress ->
                caption = "Copying the local model into AKUJI... $progress%"
            }.onFailure {
                caption = it.message ?: "AKUJI could not import that model."
                state = AkujiState.BrainError
                return@launch
            }

            state = AkujiState.Loading
            caption = "Loading AKUJI's local model..."
            brain.prepare()
                .onSuccess {
                    caption = "AKUJI's local model is connected to her body and voice."
                    state = AkujiState.ModelReady
                }
                .onFailure {
                    caption = it.message ?: "The local model could not start."
                    state = AkujiState.BrainError
                }
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()

        if (result.resultCode != Activity.RESULT_OK || spoken.isNullOrBlank()) {
            state = AkujiState.Awake
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            state = AkujiState.Thinking
            runCatching { brain.respond(spoken) }
                .onSuccess { reply ->
                    caption = reply.text
                    if (!reply.shouldSpeak) {
                        state = AkujiState.Awake
                        return@onSuccess
                    }
                    voice.speak(
                        text = reply.text,
                        onStart = { state = AkujiState.Speaking },
                        onDone = {
                            state = if (brain.hasModel) AkujiState.ModelReady else AkujiState.Awake
                        },
                        onError = { state = AkujiState.VoiceUnavailable },
                    )
                }
                .onFailure {
                    caption = it.message ?: "The local brain could not answer."
                    state = AkujiState.BrainError
                }
        }
    }

    fun startListening() {
        voice.stop()
        state = AkujiState.Listening
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to AKUJI")
        }
        speechLauncher.launch(intent)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startListening() else state = AkujiState.MicrophoneBlocked
    }

    fun requestConversation() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) startListening()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    MaterialTheme {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            AkujiBody(
                state = state,
                caption = caption,
                onTalk = ::requestConversation,
                hasModel = brain.hasModel,
                onConnectModel = { modelPicker.launch(arrayOf("*/*")) },
            )
        }
    }
}

@Composable
private fun AkujiBody(
    state: AkujiState,
    caption: String,
    onTalk: () -> Unit,
    hasModel: Boolean,
    onConnectModel: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "akuji-presence")
    val breath by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == AkujiState.Speaking) 1.028f else 1.012f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (state == AkujiState.Speaking) 520 else 2_300),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    val aura by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = if (state == AkujiState.Speaking) 0.9f else 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (state == AkujiState.Speaking) 420 else 1_800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "aura",
    )

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF07050A))) {
        Image(
            painter = painterResource(R.drawable.akuji_full_body),
            contentDescription = "AKUJI, the locked DEFF ROW visual identity",
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = breath
                    scaleY = breath
                },
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x33000000),
                        0.48f to Color.Transparent,
                        0.73f to Color(0xAA09050D),
                        1f to Color(0xFF07050A),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "AKUJI",
                        color = Color(0xFFF1D99B),
                        fontWeight = FontWeight.Black,
                        fontSize = 26.sp,
                        letterSpacing = 5.sp,
                    )
                    Text(
                        text = "DEFF ROW // LOCAL BODY",
                        color = Color(0xFFCBA3C9),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        letterSpacing = 1.5.sp,
                    )
                }

                Text(
                    text = state.label,
                    color = Color(0xFFF1D99B),
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(Color(0x990A080D))
                        .border(1.dp, Color(0x88C9A84C), RoundedCornerShape(100.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = caption,
                    color = Color(0xFFF7EEDC),
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xB5120B16))
                        .border(1.dp, Color(0x55C9A84C), RoundedCornerShape(24.dp))
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                )

                Spacer(Modifier.height(18.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(104.dp)
                        .alpha(aura)
                        .drawBehind {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    listOf(Color(0xFFC9A84C), Color.Transparent),
                                ),
                                radius = size.minDimension / 2,
                            )
                        },
                ) {
                    Button(
                        onClick = onTalk,
                        enabled = state != AkujiState.Listening &&
                            state != AkujiState.Thinking,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF241128),
                            contentColor = Color(0xFFF1D99B),
                            disabledContainerColor = Color(0xFF151019),
                        ),
                        modifier = Modifier
                            .size(82.dp)
                            .border(2.dp, Color(0xFFC9A84C), CircleShape),
                    ) {
                        Text(
                            text = if (state == AkujiState.Listening) "..." else "TALK",
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            letterSpacing = 1.sp,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Voice + memory stay on this phone",
                    color = Color(0xFFAA9BAE),
                    fontSize = 11.sp,
                )
                if (!hasModel) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onConnectModel,
                        enabled = state != AkujiState.Importing && state != AkujiState.Loading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF120B16),
                            contentColor = Color(0xFFF1D99B),
                        ),
                        modifier = Modifier.border(
                            1.dp,
                            Color(0x66C9A84C),
                            RoundedCornerShape(100.dp),
                        ),
                    ) {
                        Text(
                            text = "CONNECT LOCAL BRAIN",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp,
                        )
                    }
                }
            }
        }
    }
}
