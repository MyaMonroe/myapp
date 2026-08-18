import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'services/memory_store.dart';
import 'services/model_service.dart';
import 'services/voice_service.dart';
import 'widgets/akuji_avatar.dart';

class AkujiApp extends StatelessWidget {
  const AkujiApp({super.key});

  @override
  Widget build(BuildContext context) {
    const gold = Color(0xFFFFC65A);
    const ink = Color(0xFF070A11);

    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'AKUJI',
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: ink,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF194BFF),
          brightness: Brightness.dark,
          primary: gold,
          surface: const Color(0xFF101725),
        ),
        useMaterial3: true,
      ),
      home: const AkujiHome(),
    );
  }
}

class AkujiHome extends StatefulWidget {
  const AkujiHome({super.key});

  @override
  State<AkujiHome> createState() => _AkujiHomeState();
}

class _AkujiHomeState extends State<AkujiHome> {
  final MemoryStore _memory = MemoryStore();
  final ModelService _model = ModelService();
  final VoiceService _voice = VoiceService();

  AvatarMode _mode = AvatarMode.booting;
  String _statusDetail = 'Opening private memory';
  String _reply = 'I’m waking up on your phone.';
  String _coreInstruction = '';
  bool _modelReady = false;
  bool _speechReady = false;
  bool _handlingFinalSpeech = false;
  int _memoryCount = 0;

  @override
  void initState() {
    super.initState();
    _initialize();
  }

  Future<void> _initialize() async {
    try {
      await _memory.initialize();
      _coreInstruction = await rootBundle.loadString('assets/akuji_core.md');
      final memoryContext = await _memory.recentTranscript();
      _memoryCount = await _memory.messageCount();

      if (mounted) {
        setState(() => _statusDetail = 'Checking local Gemma');
      }
      final restored = await _model.restore(
        coreInstruction: _coreInstruction,
        memoryContext: memoryContext,
      );

      await _voice.initialize();
      if (!mounted) return;
      setState(() {
        _modelReady = restored;
        _speechReady = _voice.speechAvailable;
        _mode = restored ? AvatarMode.idle : AvatarMode.offline;
        _statusDetail = restored
            ? 'Gemma, body, and memory are local'
            : 'AKUJI needs a local Gemma model';
        _reply = restored
            ? 'I’m here, Peachez.'
            : 'My body and memory are ready. Connect my local Gemma model when you’re ready.';
      });
    } catch (error) {
      _showError('AKUJI could not finish waking up: ${_cleanError(error)}');
    }
  }

  Future<void> _connectModel() async {
    if (_coreInstruction.isEmpty) return;
    Navigator.of(context).maybePop();

    setState(() {
      _mode = AvatarMode.thinking;
      _statusDetail = 'Saving Gemma inside AKUJI';
      _reply = 'Choose the Gemma model file already on your phone.';
    });

    try {
      final result = await _model.importFromPhone(
        coreInstruction: _coreInstruction,
        memoryContext: await _memory.recentTranscript(),
      );
      if (!mounted) return;

      if (result.status == ModelImportStatus.cancelled) {
        setState(() {
          _mode = _modelReady ? AvatarMode.idle : AvatarMode.offline;
          _statusDetail = _modelReady
              ? 'Gemma, body, and memory are local'
              : 'AKUJI needs a local Gemma model';
          _reply = _modelReady
              ? 'The existing local model is still connected.'
              : 'Nothing changed. My body and memory are still safe.';
        });
        return;
      }

      setState(() {
        _modelReady = true;
        _mode = AvatarMode.idle;
        _statusDetail = 'Gemma, body, and memory are local';
        _reply = 'Connected permanently to ${result.fileName}.';
      });
    } catch (error) {
      _showError('That model could not connect: ${_cleanError(error)}');
    }
  }

  Future<void> _toggleListening() async {
    if (!_modelReady) {
      setState(() {
        _mode = AvatarMode.offline;
        _reply = 'Connect my local Gemma model first.';
      });
      return;
    }

    if (_voice.isListening) {
      await _voice.stopListening();
      if (mounted) {
        setState(() {
          _mode = AvatarMode.idle;
          _statusDetail = 'Gemma, body, and memory are local';
        });
      }
      return;
    }

    _handlingFinalSpeech = false;
    setState(() {
      _mode = AvatarMode.listening;
      _statusDetail = 'Listening on device';
      _reply = 'I’m listening.';
    });

    await _voice.startListening(
      onPartial: (words) {
        if (!mounted || _handlingFinalSpeech) return;
        setState(() => _reply = words);
      },
      onFinal: (words) {
        if (_handlingFinalSpeech) return;
        _handlingFinalSpeech = true;
        _runPrompt(words);
      },
      onError: (message) {
        if (!mounted) return;
        _showError(message);
      },
    );
  }

  Future<void> _runPrompt(String prompt) async {
    final cleanPrompt = prompt.trim();
    if (cleanPrompt.isEmpty || !_modelReady) return;

    await _voice.stopListening();
    setState(() {
      _mode = AvatarMode.thinking;
      _statusDetail = 'Thinking privately on this phone';
      _reply = cleanPrompt;
    });

    try {
      await _memory.saveMessage(role: 'user', content: cleanPrompt);
      final response = await _model.ask(cleanPrompt);
      await _memory.saveMessage(role: 'assistant', content: response);
      final count = await _memory.messageCount();
      if (!mounted) return;

      setState(() {
        _reply = response;
        _memoryCount = count;
        _mode = AvatarMode.speaking;
        _statusDetail = 'Speaking through the US phone voice';
      });

      try {
        await _voice.speak(
          response,
          onStart: () {
            if (!mounted) return;
            setState(() => _mode = AvatarMode.speaking);
          },
          onComplete: () {
            if (!mounted) return;
            setState(() {
              _mode = AvatarMode.idle;
              _statusDetail = 'Gemma, body, and memory are local';
            });
          },
        );
      } catch (_) {
        if (!mounted) return;
        setState(() {
          _mode = AvatarMode.idle;
          _statusDetail = 'Response saved; phone voice needs setup';
        });
      }
    } catch (error) {
      _showError('I hit a local model error: ${_cleanError(error)}');
    }
  }

  Future<void> _openKeyboard() async {
    final controller = TextEditingController();
    final prompt = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      backgroundColor: const Color(0xFF111827),
      builder: (context) {
        return Padding(
          padding: EdgeInsets.fromLTRB(
            20,
            18,
            20,
            MediaQuery.viewInsetsOf(context).bottom + 20,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text(
                'TYPE TO AKUJI',
                style: TextStyle(
                  color: Color(0xFFFFC65A),
                  fontWeight: FontWeight.w800,
                  letterSpacing: 1.3,
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: controller,
                autofocus: true,
                minLines: 1,
                maxLines: 4,
                textCapitalization: TextCapitalization.sentences,
                decoration: const InputDecoration(
                  hintText: 'Say what you need…',
                  border: OutlineInputBorder(),
                ),
                onSubmitted: (value) => Navigator.pop(context, value),
              ),
              const SizedBox(height: 12),
              FilledButton(
                onPressed: () => Navigator.pop(context, controller.text),
                child: const Text('SEND'),
              ),
            ],
          ),
        );
      },
    );
    controller.dispose();
    if (prompt != null && prompt.trim().isNotEmpty) {
      await _runPrompt(prompt);
    }
  }

  void _openStatus() {
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: const Color(0xFF111827),
      builder: (context) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(22),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text(
                'AKUJI SYSTEM',
                style: TextStyle(
                  color: Color(0xFFFFC65A),
                  fontSize: 18,
                  fontWeight: FontWeight.w900,
                  letterSpacing: 1.4,
                ),
              ),
              const SizedBox(height: 18),
              _StatusRow(
                label: 'BODY',
                value: 'Approved AKUJI asset • embedded',
                active: true,
              ),
              _StatusRow(
                label: 'BRAIN',
                value: _modelReady ? 'Local Gemma • connected' : 'Not connected',
                active: _modelReady,
              ),
              _StatusRow(
                label: 'MEMORY',
                value: '$_memoryCount saved turns • local SQLite',
                active: true,
              ),
              _StatusRow(
                label: 'VOICE',
                value: _speechReady
                    ? 'US phone voice • temporary bridge'
                    : 'Keyboard available • voice unavailable',
                active: _speechReady,
              ),
              const SizedBox(height: 18),
              FilledButton.icon(
                onPressed: _connectModel,
                icon: const Icon(Icons.memory_rounded),
                label: Text(_modelReady ? 'CHANGE LOCAL MODEL' : 'CONNECT LOCAL GEMMA'),
              ),
              const SizedBox(height: 8),
              const Text(
                'Changing the model never deletes AKUJI’s body or saved memory.',
                textAlign: TextAlign.center,
                style: TextStyle(color: Color(0xFFAAB3C5), fontSize: 12),
              ),
            ],
          ),
        ),
      ),
    );
  }

  void _showError(String message) {
    if (!mounted) return;
    setState(() {
      _mode = AvatarMode.error;
      _statusDetail = 'Needs attention';
      _reply = message;
    });
  }

  String _cleanError(Object error) {
    return error
        .toString()
        .replaceFirst('Exception: ', '')
        .replaceFirst('FormatException: ', '')
        .replaceFirst('FileSystemException: ', '')
        .trim();
  }

  String get _modeLabel => switch (_mode) {
        AvatarMode.booting => 'WAKING',
        AvatarMode.offline => 'BODY ONLINE',
        AvatarMode.idle => 'PRESENT',
        AvatarMode.listening => 'LISTENING',
        AvatarMode.thinking => 'THINKING',
        AvatarMode.speaking => 'SPEAKING',
        AvatarMode.error => 'ATTENTION',
      };

  @override
  void dispose() {
    _voice.stopListening();
    _voice.stopSpeaking();
    _model.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final height = MediaQuery.sizeOf(context).height;
    return Scaffold(
      body: DecoratedBox(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [Color(0xFF080C16), Color(0xFF0B1322), Color(0xFF05070C)],
          ),
        ),
        child: SafeArea(
          child: Stack(
            children: [
              Positioned.fill(
                child: Opacity(
                  opacity: 0.14,
                  child: CustomPaint(painter: _CircuitPainter()),
                ),
              ),
              Column(
                children: [
                  Padding(
                    padding: const EdgeInsets.fromLTRB(20, 10, 10, 8),
                    child: Row(
                      children: [
                        const Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                'AKUJI',
                                style: TextStyle(
                                  fontSize: 25,
                                  fontWeight: FontWeight.w900,
                                  letterSpacing: 4,
                                ),
                              ),
                              Text(
                                'DEFF ROW • PRIVATE DEVICE CORE',
                                style: TextStyle(
                                  color: Color(0xFF78859C),
                                  fontSize: 10,
                                  fontWeight: FontWeight.w700,
                                  letterSpacing: 1.1,
                                ),
                              ),
                            ],
                          ),
                        ),
                        IconButton(
                          tooltip: 'AKUJI system status',
                          onPressed: _openStatus,
                          icon: const Icon(Icons.tune_rounded),
                        ),
                      ],
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 20),
                    child: Row(
                      children: [
                        Container(
                          width: 8,
                          height: 8,
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            color: _mode == AvatarMode.error
                                ? const Color(0xFFFF5D73)
                                : _modelReady
                                    ? const Color(0xFF56E39F)
                                    : const Color(0xFFFFC65A),
                            boxShadow: [
                              BoxShadow(
                                color: (_modelReady
                                        ? const Color(0xFF56E39F)
                                        : const Color(0xFFFFC65A))
                                    .withValues(alpha: 0.5),
                                blurRadius: 10,
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(width: 8),
                        Text(
                          _modeLabel,
                          style: const TextStyle(
                            fontWeight: FontWeight.w800,
                            fontSize: 11,
                            letterSpacing: 1.5,
                          ),
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text(
                            _statusDetail,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            textAlign: TextAlign.end,
                            style: const TextStyle(
                              color: Color(0xFF8994A8),
                              fontSize: 11,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 12),
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 22),
                      child: AkujiAvatar(mode: _mode),
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(18, 12, 18, 8),
                    child: Container(
                      constraints: BoxConstraints(maxHeight: height * 0.18),
                      width: double.infinity,
                      padding: const EdgeInsets.fromLTRB(18, 14, 18, 14),
                      decoration: BoxDecoration(
                        color: const Color(0xFF111827).withValues(alpha: 0.94),
                        borderRadius: BorderRadius.circular(22),
                        border: Border.all(
                          color: const Color(0xFF2A3850).withValues(alpha: 0.8),
                        ),
                      ),
                      child: SingleChildScrollView(
                        child: Text(
                          _reply,
                          textAlign: TextAlign.center,
                          style: const TextStyle(
                            color: Color(0xFFE9EDF5),
                            height: 1.35,
                            fontSize: 15,
                          ),
                        ),
                      ),
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(34, 2, 34, 18),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        _RoundAction(
                          tooltip: 'Type to AKUJI',
                          icon: Icons.keyboard_rounded,
                          onPressed: _modelReady ? _openKeyboard : null,
                        ),
                        const SizedBox(width: 22),
                        Semantics(
                          button: true,
                          label: _voice.isListening
                              ? 'Stop listening'
                              : 'Speak to AKUJI',
                          child: InkResponse(
                            onTap: _toggleListening,
                            radius: 44,
                            child: AnimatedContainer(
                              duration: const Duration(milliseconds: 250),
                              width: 76,
                              height: 76,
                              decoration: BoxDecoration(
                                shape: BoxShape.circle,
                                gradient: LinearGradient(
                                  colors: _mode == AvatarMode.listening
                                      ? const [Color(0xFF56D7FF), Color(0xFF194BFF)]
                                      : const [Color(0xFFFFD783), Color(0xFFD79626)],
                                ),
                                boxShadow: [
                                  BoxShadow(
                                    color: const Color(0xFFFFC65A)
                                        .withValues(alpha: 0.32),
                                    blurRadius: 26,
                                    spreadRadius: 2,
                                  ),
                                ],
                              ),
                              child: Icon(
                                _voice.isListening
                                    ? Icons.stop_rounded
                                    : Icons.mic_rounded,
                                color: const Color(0xFF080B12),
                                size: 34,
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(width: 22),
                        _RoundAction(
                          tooltip: 'AKUJI system status',
                          icon: _modelReady
                              ? Icons.memory_rounded
                              : Icons.link_off_rounded,
                          onPressed: _openStatus,
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _RoundAction extends StatelessWidget {
  const _RoundAction({
    required this.tooltip,
    required this.icon,
    required this.onPressed,
  });

  final String tooltip;
  final IconData icon;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    return IconButton.filledTonal(
      tooltip: tooltip,
      onPressed: onPressed,
      icon: Icon(icon),
      style: IconButton.styleFrom(
        fixedSize: const Size(48, 48),
        backgroundColor: const Color(0xFF192335),
        foregroundColor: const Color(0xFFDCE5F6),
      ),
    );
  }
}

class _StatusRow extends StatelessWidget {
  const _StatusRow({
    required this.label,
    required this.value,
    required this.active,
  });

  final String label;
  final String value;
  final bool active;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 7),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 70,
            child: Text(
              label,
              style: const TextStyle(
                color: Color(0xFF7F8AA0),
                fontSize: 11,
                fontWeight: FontWeight.w800,
                letterSpacing: 1.1,
              ),
            ),
          ),
          Container(
            width: 7,
            height: 7,
            margin: const EdgeInsets.only(top: 4, right: 9),
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: active ? const Color(0xFF56E39F) : const Color(0xFFFFC65A),
            ),
          ),
          Expanded(
            child: Text(
              value,
              style: const TextStyle(color: Color(0xFFE5EAF3), fontSize: 13),
            ),
          ),
        ],
      ),
    );
  }
}

class _CircuitPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = const Color(0xFF3568C8)
      ..strokeWidth = 1
      ..style = PaintingStyle.stroke;

    for (var i = 0; i < 7; i++) {
      final x = size.width * (i / 6);
      canvas.drawLine(Offset(x, 0), Offset(x - 70, size.height), paint);
    }
    for (var i = 1; i < 9; i++) {
      final y = size.height * (i / 9);
      canvas.drawLine(Offset(0, y), Offset(size.width, y - 36), paint);
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
