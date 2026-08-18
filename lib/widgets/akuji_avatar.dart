import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

enum AvatarMode { booting, offline, idle, listening, thinking, speaking, error }

class AkujiAvatar extends StatefulWidget {
  const AkujiAvatar({super.key, required this.mode});

  final AvatarMode mode;

  @override
  State<AkujiAvatar> createState() => _AkujiAvatarState();
}

class _AkujiAvatarState extends State<AkujiAvatar>
    with SingleTickerProviderStateMixin {
  late final AnimationController _breath;
  late final Future<Uint8List> _imageBytes;

  @override
  void initState() {
    super.initState();
    _breath = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2800),
      lowerBound: 0,
      upperBound: 1,
    )..repeat(reverse: true);
    _imageBytes = rootBundle
        .loadString('assets/akuji_full_body.b64')
        .then((value) => base64Decode(value.replaceAll(RegExp(r'\s'), '')));
  }

  @override
  void dispose() {
    _breath.dispose();
    super.dispose();
  }

  Color get _glowColor => switch (widget.mode) {
        AvatarMode.listening => const Color(0xFF56D7FF),
        AvatarMode.thinking => const Color(0xFF906BFF),
        AvatarMode.speaking => const Color(0xFFFFC65A),
        AvatarMode.error => const Color(0xFFFF5D73),
        AvatarMode.offline => const Color(0xFF6E7485),
        _ => const Color(0xFF2D8FFF),
      };

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _breath,
      builder: (context, child) {
        final active = widget.mode == AvatarMode.speaking ||
            widget.mode == AvatarMode.listening ||
            widget.mode == AvatarMode.thinking;
        final scale = 1 + (_breath.value * (active ? 0.012 : 0.005));
        return Transform.scale(
          scale: scale,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 350),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(34),
              boxShadow: [
                BoxShadow(
                  color: _glowColor.withValues(alpha: active ? 0.42 : 0.16),
                  blurRadius: active ? 46 : 24,
                  spreadRadius: active ? 5 : 1,
                ),
              ],
            ),
            child: child,
          ),
        );
      },
      child: ClipRRect(
        borderRadius: BorderRadius.circular(34),
        child: FutureBuilder<Uint8List>(
          future: _imageBytes,
          builder: (context, snapshot) {
            if (!snapshot.hasData) {
              return const ColoredBox(
                color: Color(0xFF0C1220),
                child: Center(child: CircularProgressIndicator()),
              );
            }
            return Image.memory(
              snapshot.data!,
              fit: BoxFit.contain,
              filterQuality: FilterQuality.high,
              gaplessPlayback: true,
            );
          },
        ),
      ),
    );
  }
}
