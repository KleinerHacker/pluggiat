# pluggiat

**pluggiat is a plugin manager system for the JVM, written in Kotlin.** It discovers, loads and
manages plugins for a host application, so the host itself does not have to implement plugin
discovery, isolation or lifecycle handling. It is designed to be embedded into any JVM
application.

## AI transparency notice

!!! note "Parts of this software were created with AI"

    Parts of the source code, the tests and this documentation were generated with the help of AI
    coding assistants. Every generated contribution is reviewed, adapted and accepted by a human
    maintainer before it is released; the maintainers remain responsible for the published software.

    This notice is published in the spirit of the transparency requirements of the European Union's
    Artificial Intelligence Act (Regulation (EU) 2024/1689).

## Core concepts

* **Plugin manifest** - every plugin ships a `META-INF/plugin.yml` (or `.yaml`) file describing
  its identity, version, author and the extension points it contributes.
* **Extension points** - plugins register implementations under a freely chosen key
  (`extensions.<key>`). Each implementation is resolved, type-checked against an annotated
  configuration class and instantiated as a factory/singleton.
* **Plugin locations and load modes** - the host configures one or more locations to scan, each
  with a scan strategy (`SingleJarScanStrategy`, `MultiJarWithOwnFolderScanStrategy` or
  `ZipJarScanStrategy`, the default) and a builtin/external classification.
* **Security concepts** - each location is protected by an ordered fallback chain of
  `PluginSecurityStrategy` implementations, e.g. `InsecureSecurityStrategy` (no check),
  `SignatureSecurityStrategy` (signature against a host-provided public key) or
  `ChecksumSecurityStrategy` (host-managed approval of unknown or changed plugins); no implicit
  default, a chain must always be configured explicitly.
* **Isolation** - plugins run in their own parent-last classloaders and can only see the part of
  the host's API that the host explicitly whitelists.
* **Lifecycle** - plugins can be enabled, disabled or unloaded; an unhandled runtime error in a
  plugin's extension deactivates only that plugin.

## Who should read what

This documentation is split by audience:

* **Plugin development** - for authors writing a plugin that is loaded by a pluggiat-based host:
  manifest format, extension points, plugin dependencies and lifecycle hooks.
* **Host integration** - for developers embedding pluggiat into their own application: plugin
  locations and load modes, security configuration, the SDK whitelist and plugin lifecycle
  management from the host's perspective.
* **Troubleshooting** - log level overview and explanations for the error and conflict cases the
  framework can report.

## Where to go next

* [API Docs](dokka/html/index.html) - the generated Dokka API documentation
* [Licences](licences/index.html) - the dependency licence report
