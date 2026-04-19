# ConflictCourt

JetBrains plugin that detects merge conflicts, extracts them to a Vercel "War Room," uses Codex to summarize intent, and allows for a one-click AI-powered resolution.

## Boilerplate Generated

This repository now contains a minimal IntelliJ Platform plugin scaffold in Kotlin:

- `build.gradle.kts`: Gradle build for the IntelliJ Platform plugin.
- `settings.gradle.kts`: Sets the Gradle root project name.
- `gradle.properties`: Basic Gradle and Kotlin defaults.
- `gradle/wrapper`: Gradle wrapper configuration.
- `gradlew` and `gradlew.bat`: Wrapper scripts so the project can build without a system Gradle installation.
- `src/main/kotlin`: Kotlin source code for the plugin.
- `src/main/resources/META-INF/plugin.xml`: Plugin metadata and action registration.

The sample action is available from `Tools | ConflictCourt: Open War Room`. It currently shows a notification so you can verify the plugin loads correctly inside a sandbox IDE.

There is also a starter tool window named `ConflictCourt` docked on the right side of the IDE. It is implemented as a placeholder panel for the eventual merge-conflict workflow.

## Running It

Open the project in IntelliJ IDEA and import it as a Gradle project. Then run:

```bash
./gradlew runIde
```

That launches a sandbox IDE with the plugin installed.

## What `.idea` Is

`.idea/` is IntelliJ IDEA's project metadata directory. It stores IDE configuration for this specific checkout.

Common files you may see there:

- `workspace.xml`: Your personal local workspace state. Open files, recent run configurations, window layout, and similar machine-specific settings.
- `misc.xml`: Project SDK and language-level settings.
- `modules.xml`: Which `.iml` modules are attached to the project.
- `vcs.xml`: Version control mappings.
- `codeStyles/`: Shared formatting rules, if your team commits them.
- `inspectionProfiles/`: Shared inspection settings, if your team commits them.

As a rule:

- Commit shared team settings only when they are intentional, stable, and useful to everyone.
- Do not commit personal state like `workspace.xml`.

That is why the root `.gitignore` excludes `.idea/` by default in this scaffold.
