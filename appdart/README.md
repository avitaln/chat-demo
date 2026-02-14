# appdart

Dart CLI client for the chat-demo backend, mirroring the Scala CLI behavior.

## Run

1. Start the backend server from repository root (example):
   - `./run.sh`
2. Run the Dart CLI:
   - `cd appdart`
   - `dart run bin/appdart.dart`
   - Optional server URL: `dart run bin/appdart.dart http://localhost:3000`

## Commands

- `/c <id>` create/load conversation
- `/c` list conversations
- `/dump` dump active conversation messages
- `/setmodel` switch model
- `/premium on|off` toggle user context
- `/attach <url>` stage attachment for next message
- `/clear` clear active conversation
- `/help` show help
- `/quit` or `/exit` leave app
