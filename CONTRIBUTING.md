# Contributing to android-agent-workflow

Thank you for your interest in contributing to `android-agent-workflow`, an open-source library within the [local-ai-mobile](https://github.com/local-ai-mobile) ecosystem!

## Development Setup

1. **Prerequisites**:
   - JDK 17 or higher (JDK 22 fully supported)
   - Android SDK (API 35 installed)
   - Gradle 9.3.1 (invoked via `./gradlew`)

2. **Building the Project**:
   ```bash
   ./gradlew assemble
   ```

3. **Running Robolectric Unit Tests**:
   ```bash
   ./gradlew test
   ```

## Architectural Guidelines
- Write strictly typed, immutable Kotlin data classes with copy-on-write semantics (`AgentSession`).
- Maintain isolated context scratchpads to ensure zero main-chat token pollution (`mainChatTokensPolluted == 0`).
- Never hardcode concrete tools inside core engine classes; always use `AgentTool` interfaces.
- Add comprehensive Robolectric unit tests for any new features or bug fixes.
