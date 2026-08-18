import 'package:flutter_tts/flutter_tts.dart';
import 'package:speech_to_text/speech_recognition_error.dart';
import 'package:speech_to_text/speech_recognition_result.dart';
import 'package:speech_to_text/speech_to_text.dart';

class VoiceService {
  final SpeechToText _speech = SpeechToText();
  final FlutterTts _tts = FlutterTts();

  bool _speechAvailable = false;
  bool get speechAvailable => _speechAvailable;
  bool get isListening => _speech.isListening;

  Future<void> initialize() async {
    _speechAvailable = await _speech.initialize(
      options: [SpeechToText.androidNoBluetooth],
    );

    await _tts.setLanguage('en-US');
    await _tts.setSpeechRate(0.46);
    await _tts.setPitch(1.03);
    await _tts.awaitSpeakCompletion(true);
    await _selectUsVoiceWithoutMaleLabel();
  }

  Future<void> _selectUsVoiceWithoutMaleLabel() async {
    final voices = await _tts.getVoices;
    if (voices is! List) return;

    final candidates = voices.whereType<Map>().where((voice) {
      final locale = '${voice['locale'] ?? ''}'.toLowerCase();
      final name = '${voice['name'] ?? ''}'.toLowerCase();
      final explicitlyMale = name.contains('male') && !name.contains('female');
      return (locale == 'en-us' || locale == 'en_us') && !explicitlyMale;
    }).toList();

    if (candidates.isEmpty) return;
    final preferred = candidates.firstWhere(
      (voice) => '${voice['name'] ?? ''}'.toLowerCase().contains('female'),
      orElse: () => candidates.first,
    );
    await _tts.setVoice({
      'name': '${preferred['name']}',
      'locale': '${preferred['locale']}',
    });
  }

  Future<void> startListening({
    required void Function(String words) onPartial,
    required void Function(String words) onFinal,
    required void Function(String message) onError,
  }) async {
    if (!_speechAvailable) {
      onError('Phone speech recognition is unavailable. Use the keyboard button.');
      return;
    }

    await _tts.stop();
    await _speech.listen(
      onResult: (SpeechRecognitionResult result) {
        final words = result.recognizedWords.trim();
        if (words.isEmpty) return;
        onPartial(words);
        if (result.finalResult) onFinal(words);
      },
      onDevice: true,
      listenFor: const Duration(seconds: 30),
      pauseFor: const Duration(seconds: 3),
      listenMode: ListenMode.confirmation,
      partialResults: true,
      cancelOnError: true,
    );

    if (_speech.hasError) {
      final SpeechRecognitionError? error = _speech.lastError;
      onError(error?.errorMsg ?? 'Voice input could not start.');
    }
  }

  Future<void> stopListening() => _speech.stop();

  Future<void> speak(
    String text, {
    required void Function() onStart,
    required void Function() onComplete,
  }) async {
    _tts.setStartHandler(onStart);
    _tts.setCompletionHandler(onComplete);
    _tts.setErrorHandler((_) => onComplete());
    await _tts.speak(text);
  }

  Future<void> stopSpeaking() => _tts.stop();
}
