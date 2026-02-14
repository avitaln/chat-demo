import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;

import 'models.dart';

class HttpChatBackend {
  HttpChatBackend(String baseUrl, {http.Client? client})
    : _normalizedUrl = baseUrl.endsWith('/')
          ? baseUrl.substring(0, baseUrl.length - 1)
          : baseUrl,
      _client = client ?? http.Client();

  final String _normalizedUrl;
  final http.Client _client;

  Future<bool> createConversation(
    UserContext userContext,
    String conversationId,
  ) async {
    try {
      final requestBody = <String, dynamic>{
        'isPremium': userContext.isPremium,
        'deviceId': userContext.deviceId,
      };
      if (userContext.signedInId != null &&
          userContext.signedInId!.isNotEmpty) {
        requestBody['signedInId'] = userContext.signedInId;
      }

      final response = await _client.post(
        _uri('/conversations/${_encode(conversationId)}'),
        headers: _jsonHeaders,
        body: jsonEncode(requestBody),
      );

      if (response.statusCode == 201) {
        return true;
      }
      if (response.statusCode == 200) {
        if (response.body.trim().isEmpty) {
          return false;
        }
        final parsed = _decodeJsonMap(response.body);
        return parsed['status'] == 'created';
      }
      if (response.statusCode >= 400) {
        final backendError = _safeBackendError(response.body);
        throw Exception(
          'HTTP ${response.statusCode}${backendError == null ? '' : ': $backendError'}',
        );
      }
      return response.body.trim().isNotEmpty;
    } catch (error) {
      throw _operationFailure('create conversation', error);
    }
  }

  Future<List<ConversationMessage>> getConversationHistory(
    UserContext userContext,
    String conversationId,
  ) async {
    try {
      final response = await _client.get(
        _uri(
          '/conversations/${_encode(conversationId)}',
          query: _userQuery(userContext),
        ),
      );
      final body = _decodeJsonMap(response.body);
      final messagesValue = body['messages'];
      if (messagesValue is! List) {
        return const <ConversationMessage>[];
      }

      final result = <ConversationMessage>[];
      for (final entry in messagesValue) {
        if (entry is Map) {
          result.add(
            ConversationMessage.fromJson(Map<String, dynamic>.from(entry)),
          );
        }
      }
      return result;
    } catch (error) {
      throw _operationFailure('get conversation history', error);
    }
  }

  Future<List<Conversation>> listConversations(UserContext userContext) async {
    try {
      final response = await _client.get(
        _uri('/conversations', query: _userQuery(userContext)),
      );
      final body = _decodeJsonMap(response.body);
      final conversationsValue = body['conversations'];
      if (conversationsValue is! List) {
        return const <Conversation>[];
      }

      final result = <Conversation>[];
      for (final entry in conversationsValue) {
        if (entry is Map) {
          result.add(Conversation.fromJson(Map<String, dynamic>.from(entry)));
        }
      }
      return result;
    } catch (error) {
      throw _operationFailure('list conversations', error);
    }
  }

  Future<void> setConversationTitle(
    UserContext userContext,
    String conversationId,
    String title,
  ) async {
    try {
      final requestBody = <String, dynamic>{
        'title': title,
        'isPremium': userContext.isPremium,
        'deviceId': userContext.deviceId,
      };
      if (userContext.signedInId != null &&
          userContext.signedInId!.isNotEmpty) {
        requestBody['signedInId'] = userContext.signedInId;
      }

      final response = await _client.put(
        _uri('/conversations/${_encode(conversationId)}/title'),
        headers: _jsonHeaders,
        body: jsonEncode(requestBody),
      );
      if (response.statusCode >= 400) {
        final backendError = _safeBackendError(response.body);
        throw Exception(
          'HTTP ${response.statusCode}${backendError == null ? '' : ': $backendError'}',
        );
      }
    } catch (error) {
      throw _operationFailure('set conversation title', error);
    }
  }

  Future<List<ProviderConfig>> getAvailableModels() async {
    try {
      final response = await _client.get(_uri('/models'));
      final body = _decodeJsonMap(response.body);
      final modelsValue = body['models'];
      if (modelsValue is! List) {
        return const <ProviderConfig>[];
      }
      final result = <ProviderConfig>[];
      for (final entry in modelsValue) {
        if (entry is Map) {
          result.add(ProviderConfig.fromJson(Map<String, dynamic>.from(entry)));
        }
      }
      return result;
    } catch (error) {
      throw _operationFailure('get models', error);
    }
  }

  Future<ChatResult> chat(
    String conversationId,
    String message,
    List<MessageAttachment> attachments,
    String modelId,
    String? agentId,
    UserContext userContext,
    ChatStreamHandler streamHandler,
  ) async {
    try {
      final requestBody = <String, dynamic>{
        'message': message,
        'modelId': modelId,
        'isPremium': userContext.isPremium,
        'deviceId': userContext.deviceId,
      };
      if (agentId != null && agentId.isNotEmpty) {
        requestBody['agentId'] = agentId;
      }
      if (userContext.signedInId != null &&
          userContext.signedInId!.isNotEmpty) {
        requestBody['signedInId'] = userContext.signedInId;
      }
      if (attachments.isNotEmpty) {
        requestBody['attachments'] = attachments
            .map((a) => a.toJson())
            .toList();
      }

      final request =
          http.Request(
              'POST',
              _uri('/conversations/${_encode(conversationId)}/chat'),
            )
            ..headers.addAll(_jsonHeaders)
            ..body = jsonEncode(requestBody);

      final response = await _client.send(request);
      if (response.statusCode >= 400) {
        final body = await response.stream.bytesToString();
        final backendError = _safeBackendError(body);
        return ChatResult(
          responseAttachments: const <MessageAttachment>[],
          error:
              'HTTP error: HTTP ${response.statusCode}${backendError == null ? '' : ': $backendError'}',
        );
      }

      final responseAttachments = <MessageAttachment>[];
      String? error;

      await for (final line
          in response.stream
              .transform(utf8.decoder)
              .transform(const LineSplitter())) {
        if (!line.startsWith('data: ')) {
          continue;
        }
        final data = line.substring(6).trim();
        if (data.isEmpty) {
          continue;
        }
        final event = _decodeJsonMap(data);

        if (event.containsKey('token')) {
          streamHandler.onToken((event['token'] ?? '').toString());
        }
        if (event['imageGenerating'] == true) {
          streamHandler.onImageGenerationStarted();
        }
        if (event['done'] == true) {
          if (event.containsKey('error')) {
            error = event['error']?.toString();
          }
          responseAttachments.addAll(
            MessageAttachment.listFromJson(event['attachments']),
          );
        }
      }

      return ChatResult(responseAttachments: responseAttachments, error: error);
    } catch (error) {
      return ChatResult(
        responseAttachments: const <MessageAttachment>[],
        error: 'HTTP error: $error',
      );
    }
  }

  Future<void> clearConversation(
    UserContext userContext,
    String conversationId,
  ) async {
    try {
      await _client.delete(
        _uri(
          '/conversations/${_encode(conversationId)}',
          query: _userQuery(userContext),
        ),
      );
    } catch (error) {
      throw _operationFailure('clear conversation', error);
    }
  }

  Uri _uri(String path, {Map<String, String>? query}) {
    final raw = '$_normalizedUrl$path';
    final uri = Uri.parse(raw);
    if (query == null || query.isEmpty) {
      return uri;
    }
    return uri.replace(queryParameters: query);
  }

  Map<String, String> _userQuery(UserContext context) {
    final result = <String, String>{
      'isPremium': context.isPremium,
      'deviceId': context.deviceId,
    };
    if (context.signedInId != null && context.signedInId!.isNotEmpty) {
      result['signedInId'] = context.signedInId!;
    }
    return result;
  }

  String _encode(String value) {
    return Uri.encodeComponent(value);
  }

  Map<String, dynamic> _decodeJsonMap(String body) {
    final decoded = jsonDecode(body);
    if (decoded is! Map) {
      return <String, dynamic>{};
    }
    return Map<String, dynamic>.from(decoded);
  }

  String? _safeBackendError(String body) {
    if (body.trim().isEmpty) {
      return null;
    }
    try {
      final parsed = _decodeJsonMap(body);
      final value = parsed['error'];
      if (value == null) {
        return null;
      }
      return value.toString();
    } catch (_) {
      return body.trim();
    }
  }

  Exception _operationFailure(String operation, Object error) {
    final message = _isConnectFailure(error)
        ? 'Failed to $operation: backend unavailable at $_normalizedUrl. Start the server (for example with ./run.sh) or pass the correct server URL to the app.'
        : 'Failed to $operation: ${_safeErrorMessage(error)}';
    return Exception(message);
  }

  bool _isConnectFailure(Object error) {
    return error is SocketException;
  }

  String _safeErrorMessage(Object error) {
    final text = error.toString();
    if (text.isEmpty) {
      return 'Unknown error';
    }
    return text;
  }

  static const Map<String, String> _jsonHeaders = <String, String>{
    'Content-Type': 'application/json',
  };
}
