import 'dart:io';

import 'http_chat_backend.dart';
import 'models.dart';

class ChatApplication {
  ChatApplication(this._backend);

  final HttpChatBackend _backend;

  static const String _ansiReset = '\u001B[0m';
  static const String _ansiUser = '\u001B[38;5;33m';
  static const String _ansiModel = '\u001B[38;5;214m';
  static const String _ansiDim = '\u001B[2m';

  static const UserContext _freeUserContext = UserContext(
    isPremium: 'false',
    deviceId: 'device-free-001',
    signedInId: null,
  );

  static const UserContext _premiumAppleUserContext = UserContext(
    isPremium: 'true',
    deviceId: 'device-premium-001',
    signedInId: 'apple-user-001',
  );

  String? _currentConversationId;
  String? _currentModelId;
  UserContext _currentUserContext = _freeUserContext;
  final List<MessageAttachment> _pendingAttachments = <MessageAttachment>[];

  Future<void> run() async {
    await _printWelcome();

    var running = true;
    while (running) {
      final prompt = _currentConversationId != null
          ? '\n${_ansiUser}You$_ansiDim [$_currentConversationId]$_ansiReset\n'
          : '\n${_ansiUser}You$_ansiReset\n';
      stdout.write(prompt);

      final rawInput = stdin.readLineSync();
      if (rawInput == null) {
        stdout.writeln('Goodbye!');
        break;
      }
      final input = _normalizeInput(rawInput);

      if (input.isEmpty) {
        continue;
      }
      if (_equalsAny(input, const <String>['/quit', '/exit'])) {
        stdout.writeln('Goodbye!');
        running = false;
      } else if (_equalsAny(input, const <String>['/setmodel'])) {
        await _handleSetModel();
      } else if (_equalsAny(input, const <String>['/clear'])) {
        await _handleClear();
      } else if (_equalsAny(input, const <String>['/help'])) {
        _printHelp();
      } else if (input.toLowerCase() == '/c' ||
          input.toLowerCase().startsWith('/c ')) {
        await _handleConversationCommand(input);
      } else if (_equalsAny(input, const <String>['/dump'])) {
        await _handleDump();
      } else if (input.toLowerCase().startsWith('/attach')) {
        _handleAttachCommand(input);
      } else if (input.toLowerCase().startsWith('/premium')) {
        _handlePremium(input);
      } else if (input.startsWith('/')) {
        stdout.writeln('Unknown command. Type /help for available commands.');
      } else {
        await _handleChat(input);
      }
    }
  }

  Future<void> _printWelcome() async {
    stdout.writeln('===========================================');
    stdout.writeln('   AI Chatbot CLI - LangChain4j Demo');
    stdout.writeln('===========================================');
    stdout.writeln();
    _printHelp();
    stdout.writeln();

    final models = await _backend.getAvailableModels();
    if (models.isNotEmpty) {
      if (_currentModelId == null ||
          !models.any((m) => m.modelId == _currentModelId)) {
        _currentModelId = models.first.modelId;
      }
      final current = models.firstWhere(
        (m) => m.modelId == _currentModelId,
        orElse: () => models.first,
      );
      stdout.writeln('Current model: ${current.displayName}');
    }

    stdout.writeln();
    stdout.writeln(
      'Use /c <id> to create or load a conversation, or /c to list all conversations.',
    );
  }

  void _printHelp() {
    stdout.writeln('Commands:');
    stdout.writeln('  /c <id>       - Create or load a conversation');
    stdout.writeln('  /c            - List all conversations');
    stdout.writeln('  /dump         - Dump current conversation messages');
    stdout.writeln('  /setmodel     - Switch AI model');
    stdout.writeln('  /premium      - Set premium mode (on|off)');
    stdout.writeln('  /attach <url> - Stage attachment for next message');
    stdout.writeln('  /clear        - Clear current conversation history');
    stdout.writeln('  /help         - Show this help');
    stdout.writeln('  /quit         - Exit application');
  }

  Future<void> _handleConversationCommand(String input) async {
    final parts = input.trim().split(RegExp(r'\s+'));
    if (parts.length < 2 || parts[1].trim().isEmpty) {
      await _handleListConversations();
      return;
    }

    final id = parts.sublist(1).join(' ').trim();
    final created = await _backend.createConversation(_currentUserContext, id);
    _currentConversationId = id;
    if (created) {
      stdout.writeln('Created conversation: $id');
    } else {
      final history = await _backend.getConversationHistory(
        _currentUserContext,
        id,
      );
      _printConversationPreview(id, history);
    }
  }

  void _printConversationPreview(String id, List<ConversationMessage> history) {
    if (history.isEmpty) {
      stdout.writeln('Loaded conversation: $id (empty)');
      return;
    }
    stdout.writeln('Loaded conversation: $id (${history.length} messages)');
    final start = history.length - 6 < 0 ? 0 : history.length - 6;
    if (start > 0) {
      stdout.writeln('  ... ($start earlier messages)');
    }
    for (var i = start; i < history.length; i++) {
      final msg = history[i];
      final preview = msg.text.length > 120
          ? '${msg.text.substring(0, 120)}...'
          : msg.text;
      stdout.writeln('  [${_messageRole(msg)}] $preview');
    }
  }

  Future<void> _handleListConversations() async {
    final conversations = await _backend.listConversations(_currentUserContext);
    if (conversations.isEmpty) {
      stdout.writeln('No conversations yet. Use /c <id> to start one.');
      return;
    }

    stdout.writeln('Conversations:');
    for (final conversation in conversations) {
      _printConversationListItem(conversation);
    }
  }

  Future<void> _handleDump() async {
    if (_currentConversationId == null) {
      stdout.writeln('No active conversation. Use /c <id> first.');
      return;
    }
    final history = await _backend.getConversationHistory(
      _currentUserContext,
      _currentConversationId!,
    );
    if (history.isEmpty) {
      stdout.writeln("Conversation '$_currentConversationId' has no messages.");
      return;
    }

    stdout.writeln(
      'Dumping conversation: $_currentConversationId (${history.length} messages)',
    );
    for (final msg in history) {
      stdout.writeln('[${_messageRole(msg)}] ${msg.text}');
      for (final attachment in msg.attachments) {
        final type = attachment.attachmentType.isEmpty
            ? 'link'
            : attachment.attachmentType.toLowerCase();
        final mime = attachment.mimeType.isEmpty
            ? ''
            : ' (${attachment.mimeType})';
        stdout.writeln('  -> attachment [$type] ${attachment.url}$mime');
      }
    }
  }

  Future<void> _handleSetModel() async {
    final models = await _backend.getAvailableModels();
    if (models.isEmpty) {
      stdout.writeln('No models available.');
      return;
    }

    stdout.writeln('\nAvailable models:');
    for (var i = 0; i < models.length; i++) {
      final marker = models[i].modelId == _currentModelId ? ' [current]' : '';
      stdout.writeln('  ${i + 1}. ${models[i].displayName}$marker');
    }

    stdout.write('\nSelect model (1-${models.length}): ');
    final choice = (stdin.readLineSync() ?? '').trim();
    final index = int.tryParse(choice);
    if (index == null) {
      stdout.writeln('Invalid input. Please enter a number.');
      return;
    }
    final selected = index - 1;
    if (selected < 0 || selected >= models.length) {
      stdout.writeln('Invalid selection. Please choose 1-${models.length}');
      return;
    }
    _currentModelId = models[selected].modelId;
    stdout.writeln('Switched to: ${models[selected].displayName}');
    stdout.writeln('(Will be used on next chat request)');
  }

  Future<void> _handleClear() async {
    if (_currentConversationId == null) {
      stdout.writeln('No active conversation. Use /c <id> first.');
      return;
    }
    await _backend.clearConversation(
      _currentUserContext,
      _currentConversationId!,
    );
    stdout.writeln('Conversation history cleared. Starting fresh!');
  }

  Future<void> _handleChat(String message) async {
    if (_currentConversationId == null) {
      stdout.writeln('No active conversation. Use /c <id> first.');
      return;
    }

    final models = await _backend.getAvailableModels();
    if (models.isNotEmpty &&
        (_currentModelId == null ||
            !models.any((m) => m.modelId == _currentModelId))) {
      _currentModelId = models.first.modelId;
    }
    final providerName = models.isEmpty
        ? 'AI'
        : (models
              .firstWhere(
                (m) => m.modelId == _currentModelId,
                orElse: () => models.first,
              )
              .displayName
              .split(' ')
              .first);

    stdout.writeln();
    stdout.write('$_ansiModel$providerName$_ansiReset\n');

    final attachments = List<MessageAttachment>.from(_pendingAttachments);
    _pendingAttachments.clear();

    final resolvedModelId =
        (_currentModelId == null || _currentModelId!.trim().isEmpty)
        ? (models.isNotEmpty ? models.first.modelId : '')
        : _currentModelId!;

    final result = await _backend.chat(
      _currentConversationId!,
      message,
      attachments,
      resolvedModelId,
      null,
      _currentUserContext,
      _CliChatStreamHandler(),
    );

    stdout.writeln();
    if (result.error != null && result.error!.isNotEmpty) {
      stdout.writeln('[Error] ${result.error}');
    }
    _printAttachments(result.responseAttachments);
  }

  void _handleAttachCommand(String input) {
    if (_currentConversationId == null) {
      stdout.writeln('No active conversation. Use /c <id> first.');
      return;
    }
    final parts = input.trim().split(RegExp(r'\s+'));
    if (parts.length < 2 || parts[1].trim().isEmpty) {
      stdout.writeln('Usage: /attach <URL>');
      return;
    }
    final attachment = MessageAttachmentExtractor.fromUrl(
      parts.sublist(1).join(' ').trim(),
    );
    if (attachment == null) {
      stdout.writeln('Could not parse URL for attachment.');
      return;
    }
    _pendingAttachments.add(attachment);
    stdout.writeln(
      'Attachment staged: [${attachment.attachmentType}] ${attachment.url}',
    );
  }

  void _handlePremium(String input) {
    final parts = input.trim().split(RegExp(r'\s+'));
    if (parts.length == 1) {
      final status = _currentUserContext.isPremium == 'true'
          ? 'on (premium apple)'
          : 'off (free)';
      stdout.writeln('Premium mode is currently: $status');
      stdout.writeln('Usage: /premium on|off');
      return;
    }

    switch (parts[1].toLowerCase()) {
      case 'on':
      case 'true':
      case '1':
        _currentUserContext = _premiumAppleUserContext;
        stdout.writeln(
          'Switched to premium apple user (${_currentUserContext.effectiveId}).',
        );
        break;
      case 'off':
      case 'false':
      case '0':
        _currentUserContext = _freeUserContext;
        stdout.writeln(
          'Switched to free user (${_currentUserContext.effectiveId}).',
        );
        break;
      default:
        stdout.writeln('Usage: /premium on|off');
    }
  }

  void _printAttachments(List<MessageAttachment> attachments) {
    if (attachments.isEmpty) {
      return;
    }
    stdout.writeln('Attachments:');
    for (final attachment in attachments) {
      final type = attachment.attachmentType.isEmpty
          ? 'link'
          : attachment.attachmentType.toLowerCase();
      final mime = attachment.mimeType.isEmpty
          ? ''
          : ' (${attachment.mimeType})';
      stdout.writeln('- [$type] ${attachment.url}$mime');
    }
  }

  void _printConversationListItem(Conversation conversation) {
    final marker = conversation.id == _currentConversationId ? ' [active]' : '';
    final titlePart =
        (conversation.title.isEmpty || conversation.title == conversation.id)
        ? ''
        : ' - ${conversation.title}';
    stdout.writeln('  - ${conversation.id}$titlePart$marker');
  }

  String _messageRole(ConversationMessage message) {
    final lower = message.type.toLowerCase();
    if (lower == 'user') {
      return 'you';
    }
    if (lower == 'ai') {
      return 'ai';
    }
    if (lower == 'system') {
      return 'system';
    }
    return '?';
  }

  String _normalizeInput(String raw) {
    final stripped = raw.trim();
    final depasted = stripped
        .replaceAll('\u001b[200~', '')
        .replaceAll('\u001b[201~', '')
        .trim();
    var cleaned = depasted;
    while (cleaned.isNotEmpty && _isFormatCodePoint(cleaned.runes.first)) {
      cleaned = String.fromCharCodes(cleaned.runes.skip(1));
    }
    if (cleaned.isEmpty) {
      return '';
    }
    const slashVariants = <String>['／', '⁄', '∕', '⧸', '╱'];
    if (slashVariants.any((prefix) => cleaned.startsWith(prefix))) {
      return '/${cleaned.substring(1)}';
    }
    return cleaned;
  }

  bool _isFormatCodePoint(int codePoint) {
    return codePoint == 0x200B ||
        codePoint == 0x200C ||
        codePoint == 0x200D ||
        codePoint == 0x200E ||
        codePoint == 0x200F ||
        codePoint == 0x202A ||
        codePoint == 0x202B ||
        codePoint == 0x202C ||
        codePoint == 0x202D ||
        codePoint == 0x202E ||
        codePoint == 0x2060 ||
        codePoint == 0xFEFF;
  }

  bool _equalsAny(String value, List<String> options) {
    final lower = value.toLowerCase();
    return options.any((option) => lower == option.toLowerCase());
  }
}

class _CliChatStreamHandler implements ChatStreamHandler {
  @override
  void onToken(String token) {
    stdout.write(token);
    stdout.flush();
  }

  @override
  void onImageGenerationStarted() {
    stdout.writeln('\n[Generating image...]');
    stdout.flush();
  }
}
