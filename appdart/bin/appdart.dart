import 'package:appdart/appdart.dart';

Future<void> main(List<String> arguments) async {
  final serverUrl = arguments.isNotEmpty
      ? arguments.first
      : 'http://localhost:3000';
  final backend = HttpChatBackend(serverUrl);
  final app = ChatApplication(backend);
  await app.run();
}
