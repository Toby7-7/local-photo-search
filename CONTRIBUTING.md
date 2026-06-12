# Contributing

Thank you for your interest in contributing to local-photo-search.

## How to Build

This project uses Gradle with the Kotlin DSL.

```bash
./gradlew build
```

On Windows:

```bash
gradlew.bat build
```

## How to Run Tests

```bash
./gradlew :app:testStandardDebugUnitTest
./gradlew :app:testQnnDebugUnitTest
```

To run a specific test class:

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.photosearch.app.SomeTest"
```

## How to Submit a Pull Request

1. Fork the repository and create your feature branch from `main`.
2. Make your changes, following the code style guidelines below.
3. Ensure all tests pass by running `./gradlew test`.
4. Commit your changes with a clear, descriptive commit message.
5. Push your branch and open a pull request against `main`.
6. In the PR description, explain what the change does and why it is needed.

## Code Style

- Follow the [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html) (official).
- Match the existing patterns in the codebase (naming, formatting, project structure).
- Match the existing patterns in the codebase (naming, formatting, project structure).
- Run `./gradlew lintStandardDebug` to check for lint issues before committing.

## Model Weights

This project bundles pre-downloaded machine learning model weights under `models/`.
Please do **not** submit pull requests that include new or updated model weight files.
Model weights are managed separately and updated via a distinct process. External
contributions of model weights will not be accepted.
