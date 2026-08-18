import 'package:akuji/services/model_service.dart';
import 'package:flutter_gemma/flutter_gemma.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('model file detection', () {
    test('recognizes Gemma 4 names', () {
      expect(
        ModelService.inferModelType('gemma-4-e2b-it.litertlm'),
        ModelType.gemma4,
      );
    });

    test('keeps Gemma 3 and Gemma 3n on the Gemma IT template', () {
      expect(
        ModelService.inferModelType('gemma-3n-E2B-it.task'),
        ModelType.gemmaIt,
      );
    });

    test('maps supported model formats to the correct engine', () {
      expect(
        ModelService.inferFileType('akuji.litertlm'),
        ModelFileType.litertlm,
      );
      expect(ModelService.inferFileType('akuji.task'), ModelFileType.task);
      expect(ModelService.inferFileType('akuji.bin'), ModelFileType.binary);
    });
  });
}
