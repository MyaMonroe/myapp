import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter_gemma/flutter_gemma.dart';
import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';

enum ModelImportStatus { installed, cancelled }

class ModelImportResult {
  const ModelImportResult(this.status, {this.fileName});

  final ModelImportStatus status;
  final String? fileName;
}

class ModelService {
  InferenceModel? _model;
  InferenceChat? _chat;

  bool get isReady => _model != null && _chat != null;

  static ModelType inferModelType(String fileName) {
    final normalized = fileName.toLowerCase().replaceAll(RegExp(r'[^a-z0-9]'), '');
    if (normalized.contains('gemma4')) return ModelType.gemma4;
    return ModelType.gemmaIt;
  }

  static ModelFileType inferFileType(String fileName) {
    final extension = path.extension(fileName).toLowerCase();
    return switch (extension) {
      '.litertlm' => ModelFileType.litertlm,
      '.task' => ModelFileType.task,
      '.bin' || '.tflite' => ModelFileType.binary,
      _ => throw const FormatException(
          'Choose a .litertlm, .task, .bin, or .tflite model file.',
        ),
    };
  }

  Future<bool> restore({
    required String coreInstruction,
    required String memoryContext,
  }) async {
    try {
      await _openActiveModel(
        coreInstruction: coreInstruction,
        memoryContext: memoryContext,
      );
      return true;
    } catch (_) {
      await close();
      return false;
    }
  }

  Future<ModelImportResult> importFromPhone({
    required String coreInstruction,
    required String memoryContext,
  }) async {
    final selection = await FilePicker.pickFiles(
      type: FileType.custom,
      allowedExtensions: const ['litertlm', 'task', 'bin', 'tflite'],
      allowMultiple: false,
      withData: false,
    );
    if (selection == null || selection.files.isEmpty) {
      return const ModelImportResult(ModelImportStatus.cancelled);
    }

    final selected = selection.files.single;
    final sourcePath = selected.path;
    if (sourcePath == null) {
      throw const FileSystemException(
        'Android did not provide a readable path for this model file.',
      );
    }

    final modelType = inferModelType(selected.name);
    final fileType = inferFileType(selected.name);
    final appSupport = await getApplicationSupportDirectory();
    final modelDirectory = Directory(path.join(appSupport.path, 'models'));
    await modelDirectory.create(recursive: true);

    // File pickers may hand back a temporary cache path. AKUJI owns a private
    // copy so the model remains available after reboots and cache cleanup.
    final permanentPath = path.join(modelDirectory.path, selected.name);
    if (path.normalize(sourcePath) != path.normalize(permanentPath)) {
      await File(sourcePath).copy(permanentPath);
    }

    await close();
    await FlutterGemma.installModel(
      modelType: modelType,
      fileType: fileType,
    ).fromFile(permanentPath).install();
    await _openActiveModel(
      coreInstruction: coreInstruction,
      memoryContext: memoryContext,
    );

    return ModelImportResult(
      ModelImportStatus.installed,
      fileName: selected.name,
    );
  }

  Future<String> ask(String prompt) async {
    final chat = _chat;
    if (chat == null) {
      throw StateError('Connect a local Gemma model first.');
    }

    await chat.addQueryChunk(Message.text(text: prompt.trim(), isUser: true));
    final response = await chat.generateChatResponse();
    if (response is TextResponse) return response.token.trim();
    throw StateError(
      'The local model returned a non-text response without an enabled tool.',
    );
  }

  Future<void> _openActiveModel({
    required String coreInstruction,
    required String memoryContext,
  }) async {
    final model = await FlutterGemma.getActiveModel(
      maxTokens: 4096,
      preferredBackend: PreferredBackend.gpu,
      maxConcurrentSessions: 1,
    );
    final memory = memoryContext.trim().isEmpty
        ? 'No saved conversation turns yet.'
        : memoryContext.trim();
    final chat = await model.createChat(
      maxOutputTokens: 384,
      temperature: 0.7,
      topK: 40,
      topP: 0.9,
      systemInstruction: '''
$coreInstruction

PERSISTENT LOCAL MEMORY — recent saved turns:
$memory
''',
    );

    _model = model;
    _chat = chat;
  }

  Future<void> close() async {
    final model = _model;
    _chat = null;
    _model = null;
    if (model != null) await model.close();
  }
}
