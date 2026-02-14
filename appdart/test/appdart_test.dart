import 'package:appdart/appdart.dart';
import 'package:test/test.dart';

void main() {
  test('provider modelId uses explicit id', () {
    const provider = ProviderConfig(
      providerType: 'gemini',
      model: 'gemini-2.5-pro',
      id: 'custom-id',
    );
    expect(provider.modelId, 'custom-id');
  });

  test('attachment extractor identifies image type', () {
    final attachment = MessageAttachmentExtractor.fromUrl(
      'https://example.com/image.png',
    );
    expect(attachment, isNotNull);
    expect(attachment!.attachmentType, 'image');
  });
}
