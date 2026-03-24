# LCEBridge

<p align="center">
  <img src="https://img.shields.io/github/license/veroxsity/LCEBridge?style=for-the-badge" alt="License" />
  <img src="https://img.shields.io/github/last-commit/veroxsity/LCEBridge?style=for-the-badge" alt="Last Commit" />
  <img src="https://img.shields.io/github/repo-size/veroxsity/LCEBridge?style=for-the-badge" alt="Repo Size" />
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Version-0.3.0-2A9D8F?style=flat-square" alt="Version 0.3.0" />
  <img src="https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk" alt="Java 21" />
  <img src="https://img.shields.io/badge/Build-Gradle-02303A?style=flat-square&logo=gradle" alt="Gradle" />
  <img src="https://img.shields.io/badge/Network-Netty-333333?style=flat-square" alt="Netty" />
</p>

LCEBridge is a Java 21 protocol bridge that lets unmodified Minecraft Legacy Console Edition clients connect to Minecraft Java Edition servers.

```text
LCE Client <-> LCEBridge <-> Java Server
```

## Overview

The bridge translates between native LCE packets and modern Java Edition protocol traffic while preserving gameplay behavior as closely as possible. It is designed as a standalone runtime with protocol codecs, entity and inventory translation, world/chunk conversion, and mapping data generation all in one repository.

## Workspace Fit

- Use `LCEBridge` when the target server is Minecraft Java Edition rather than the native `LCEServer`
- Pair it with `LCEClient` or `LCEDebug` for protocol and gameplay testing from a Windows client build
- Use `LCELauncher` when you want managed bridge releases, Java route setup, and online-auth handoff

## Current Capabilities

- LCE login and session handling
- Java login and in-world join flow
- Launcher-provided online account handoff for Java online-mode servers
- Chunk translation and chunk streaming
- Block and item mapping
- Multiplayer entity synchronization
- Java-to-LCE chat and translated system message forwarding
- Standalone runtime packaging for public releases

## Repository Layout

| Path | Purpose |
|------|---------|
| `core/` | Packet codecs, session logic, mappings, and translation code |
| `bootstrap-standalone/` | Standalone runtime and application entrypoint |
| `tools/` | Mapping generation and maintenance scripts |

## Requirements

- Java 21
- Windows for the current local tooling and runtime workflow

## Build

Build everything:

```powershell
.\gradlew.bat build
```

Build the standalone runtime jar:

```powershell
.\gradlew.bat :bootstrap-standalone:shadowJar
```

The runnable artifact is written to:

```text
_build/bootstrap-standalone/libs/bootstrap-standalone-0.3.0.jar
```

Create the versioned release jar through the helper script:

```powershell
.\scripts\build-jar.ps1 --version v0.3.0
```

That writes the public release artifact into:

```text
scripts/output/bootstrap-standalone-0.3.0.jar
```

## Status

LCEBridge is usable for active multiplayer testing and public standalone releases, but it is still under development. Protocol coverage, gameplay parity, and edge-case compatibility across larger Java servers are ongoing work.

## Related Repositories

- Hub repo: https://github.com/veroxsity/MinecraftLCE
- Client repo: https://github.com/veroxsity/LCEClient
- Debug client repo: https://github.com/veroxsity/LCEDebug
- Launcher repo: https://github.com/veroxsity/LCELauncher
- Server repo: https://github.com/veroxsity/LCEServer
- Save converter repo: https://github.com/veroxsity/LCE-Save-Converter
