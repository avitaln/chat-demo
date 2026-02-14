import 'dart:core';

class UserContext {
  const UserContext({
    required this.isPremium,
    required this.deviceId,
    this.signedInId,
  });

  final String isPremium;
  final String deviceId;
  final String? signedInId;

  String get effectiveId => signedInId ?? deviceId;
}

class Conversation {
  const Conversation({
    required this.id,
    required this.title,
    required this.createdAt,
  });

  final String id;
  final String title;
  final int createdAt;

  factory Conversation.fromJson(Map<String, dynamic> json) {
    return Conversation(
      id: _asString(json['id']),
      title: _asString(json['title']),
      createdAt: _asInt(json['createdAt']),
    );
  }
}

class ConversationMessage {
  const ConversationMessage({
    required this.id,
    required this.type,
    required this.text,
    required this.attachments,
    required this.archived,
    required this.createdAt,
  });

  final String id;
  final String type;
  final String text;
  final List<MessageAttachment> attachments;
  final bool archived;
  final int createdAt;

  factory ConversationMessage.fromJson(Map<String, dynamic> json) {
    return ConversationMessage(
      id: _asString(json['id']),
      type: _asString(json['type']),
      text: _asString(json['text']),
      attachments: MessageAttachment.listFromJson(json['attachments']),
      archived: _asBool(json['archived']),
      createdAt: _asInt(json['createdAt']),
    );
  }
}

class MessageAttachment {
  const MessageAttachment({
    required this.attachmentType,
    required this.url,
    required this.mimeType,
    this.title,
  });

  final String attachmentType;
  final String url;
  final String mimeType;
  final String? title;

  Map<String, String> toJson() {
    final map = <String, String>{};
    if (attachmentType.isNotEmpty) {
      map['type'] = attachmentType;
    }
    if (url.isNotEmpty) {
      map['url'] = url;
    }
    if (mimeType.isNotEmpty) {
      map['mimeType'] = mimeType;
    }
    if (title != null && title!.isNotEmpty) {
      map['title'] = title!;
    }
    return map;
  }

  factory MessageAttachment.fromJson(Map<String, dynamic> json) {
    final type = _firstNonBlank(
      _asNullableString(json['type']),
      _asNullableString(json['attachmentType']),
    );
    return MessageAttachment(
      attachmentType: type ?? '',
      url: _asString(json['url']),
      mimeType: _asString(json['mimeType']),
      title: _asNullableString(json['title']),
    );
  }

  static List<MessageAttachment> listFromJson(dynamic value) {
    if (value is! List) {
      return const <MessageAttachment>[];
    }
    final result = <MessageAttachment>[];
    for (final entry in value) {
      if (entry is Map) {
        final map = Map<String, dynamic>.from(entry);
        final url = _asNullableString(map['url']);
        if (url != null && url.isNotEmpty) {
          result.add(MessageAttachment.fromJson(map));
        }
      }
    }
    return result;
  }
}

class ProviderConfig {
  const ProviderConfig({
    required this.providerType,
    required this.model,
    required this.id,
  });

  final String providerType;
  final String model;
  final String id;

  String get modelId {
    if (id.isNotEmpty) {
      return id;
    }
    return '${providerType.toLowerCase()}:$model';
  }

  String get displayName {
    final lower = providerType.toLowerCase();
    final providerName = switch (lower) {
      'gemini' => 'Gemini',
      'chatgpt' => 'ChatGPT',
      'claude' => 'Claude',
      'grok' => 'Grok',
      _ => providerType,
    };
    return '$providerName ($model)';
  }

  factory ProviderConfig.fromJson(Map<String, dynamic> json) {
    return ProviderConfig(
      providerType: _asString(json['providerType']),
      model: _asString(json['model']),
      id: _asString(json['id']),
    );
  }
}

class ChatResult {
  const ChatResult({required this.responseAttachments, this.error});

  final List<MessageAttachment> responseAttachments;
  final String? error;
}

abstract class ChatStreamHandler {
  void onToken(String token);

  void onImageGenerationStarted();
}

class MessageAttachmentExtractor {
  static final RegExp _urlPattern = RegExp(r'(https?://\S+)');
  static const String _imageUrlPrefix = 'image url:';

  static MessageAttachment? fromUrl(String url) {
    final sanitized = _sanitizeUrl(url);
    if (sanitized == null || sanitized.isEmpty) {
      return null;
    }
    final attachmentType = _detectType(sanitized, explicitImage: false);
    final mimeType = _inferMimeType(sanitized, attachmentType);
    return MessageAttachment(
      attachmentType: attachmentType,
      url: sanitized,
      mimeType: mimeType,
      title: null,
    );
  }

  static List<MessageAttachment> extractFromText(String text) {
    final seen = <String>{};
    final attachments = <MessageAttachment>[];
    final lower = text.toLowerCase();

    for (final match in _urlPattern.allMatches(text)) {
      final rawUrl = match.group(1);
      final url = _sanitizeUrl(rawUrl);
      if (url == null || url.isEmpty || seen.contains(url)) {
        continue;
      }
      seen.add(url);
      final contextStart = match.start - 15 < 0 ? 0 : match.start - 15;
      final context = lower.substring(contextStart, match.start);
      final explicitImage = context.contains(_imageUrlPrefix);
      final attachmentType = _detectType(url, explicitImage: explicitImage);
      final mimeType = _inferMimeType(url, attachmentType);
      attachments.add(
        MessageAttachment(
          attachmentType: attachmentType,
          url: url,
          mimeType: mimeType,
          title: null,
        ),
      );
    }

    return attachments;
  }

  static String? _sanitizeUrl(String? url) {
    if (url == null) {
      return null;
    }
    var sanitized = url.trim();
    while (sanitized.isNotEmpty &&
        _isTrailingPunctuation(sanitized.codeUnitAt(sanitized.length - 1))) {
      sanitized = sanitized.substring(0, sanitized.length - 1);
    }
    return sanitized;
  }

  static bool _isTrailingPunctuation(int codeUnit) {
    return codeUnit == 0x2E || // .
        codeUnit == 0x2C || // ,
        codeUnit == 0x3B || // ;
        codeUnit == 0x29 || // )
        codeUnit == 0x5D || // ]
        codeUnit == 0x7D; // }
  }

  static String _detectType(String url, {required bool explicitImage}) {
    if (explicitImage || _isImage(url)) {
      return 'image';
    }
    if (_isDocument(url)) {
      return 'document';
    }
    return 'link';
  }

  static bool _isImage(String url) {
    final lower = _basePath(url).toLowerCase();
    return lower.endsWith('.png') ||
        lower.endsWith('.jpg') ||
        lower.endsWith('.jpeg') ||
        lower.endsWith('.gif') ||
        lower.endsWith('.webp') ||
        lower.endsWith('.bmp') ||
        lower.endsWith('.svg');
  }

  static bool _isDocument(String url) {
    final lower = _basePath(url).toLowerCase();
    return lower.endsWith('.pdf') ||
        lower.endsWith('.doc') ||
        lower.endsWith('.docx') ||
        lower.endsWith('.txt') ||
        lower.endsWith('.rtf') ||
        lower.endsWith('.csv') ||
        lower.endsWith('.xlsx') ||
        lower.endsWith('.xls') ||
        lower.endsWith('.ppt') ||
        lower.endsWith('.pptx');
  }

  static String _inferMimeType(String url, String attachmentType) {
    final lower = _basePath(url).toLowerCase();
    if (attachmentType == 'image') {
      if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) {
        return 'image/jpeg';
      }
      if (lower.endsWith('.gif')) {
        return 'image/gif';
      }
      if (lower.endsWith('.webp')) {
        return 'image/webp';
      }
      if (lower.endsWith('.bmp')) {
        return 'image/bmp';
      }
      if (lower.endsWith('.svg')) {
        return 'image/svg+xml';
      }
      return 'image/png';
    }
    if (attachmentType == 'document') {
      if (lower.endsWith('.pdf')) {
        return 'application/pdf';
      }
      if (lower.endsWith('.csv')) {
        return 'text/csv';
      }
      if (lower.endsWith('.txt')) {
        return 'text/plain';
      }
      if (lower.endsWith('.doc')) {
        return 'application/msword';
      }
      if (lower.endsWith('.docx')) {
        return 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
      }
      if (lower.endsWith('.xls')) {
        return 'application/vnd.ms-excel';
      }
      if (lower.endsWith('.xlsx')) {
        return 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
      }
      if (lower.endsWith('.ppt')) {
        return 'application/vnd.ms-powerpoint';
      }
      if (lower.endsWith('.pptx')) {
        return 'application/vnd.openxmlformats-officedocument.presentationml.presentation';
      }
      return 'application/octet-stream';
    }
    return 'text/uri-list';
  }

  static String _basePath(String url) {
    final queryIndex = url.indexOf('?');
    if (queryIndex >= 0) {
      return url.substring(0, queryIndex);
    }
    return url;
  }
}

String _asString(dynamic value) {
  if (value == null) {
    return '';
  }
  return value.toString();
}

String? _asNullableString(dynamic value) {
  if (value == null) {
    return null;
  }
  final result = value.toString();
  return result.isEmpty ? null : result;
}

bool _asBool(dynamic value) {
  if (value is bool) {
    return value;
  }
  if (value is num) {
    return value != 0;
  }
  if (value is String) {
    final lower = value.toLowerCase().trim();
    return lower == 'true' || lower == '1';
  }
  return false;
}

int _asInt(dynamic value) {
  if (value is int) {
    return value;
  }
  if (value is num) {
    return value.toInt();
  }
  if (value is String) {
    return int.tryParse(value) ?? 0;
  }
  return 0;
}

String? _firstNonBlank(String? first, String? second) {
  if (first != null && first.trim().isNotEmpty) {
    return first;
  }
  if (second != null && second.trim().isNotEmpty) {
    return second;
  }
  return null;
}
