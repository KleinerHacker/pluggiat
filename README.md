# pluggiat

pluggiat is a plugin manager system for the JVM, written in Kotlin. It discovers, loads and
manages plugins for a host application, so the host itself does not have to implement plugin
discovery, isolation or lifecycle handling.

## Features

* Plugin discovery and loading _(planned)_
* Isolated plugin classpaths _(planned)_
* Plugin lifecycle management (register, start, stop, unregister) _(planned)_

## AI transparency notice

Parts of the source code, the tests and this documentation were generated with the help of AI
coding assistants. Every generated contribution is reviewed, adapted and accepted by a human
maintainer before it is released; the maintainers remain responsible for the published software.

This notice is published in the spirit of the transparency requirements of the European Union's
Artificial Intelligence Act (Regulation (EU) 2024/1689).

## Checkout, build and run

```bash
git clone https://github.com/KleinerHacker/pluggiat.git
cd pluggiat
./gradlew build
```

On Windows use `gradlew.bat` instead of `./gradlew`.

## Consuming the artifacts

pluggiat is published as a Maven artifact:

```kotlin
dependencies {
    implementation("org.pcsoft.framework:pluggiat:<version>")
}
```

## Documentation

* [MkDocs documentation](https://kleinerhacker.github.io/pluggiat/latest/)
* [API documentation](https://kleinerhacker.github.io/pluggiat/latest/dokka/html/index.html)
* [Licence report](https://kleinerhacker.github.io/pluggiat/latest/licences/index.html)

## Implementation status

| Feature                                          | State   |
|---------------------------------------------------|---------|
| Plugin discovery and loading                       | planned |
| Isolated plugin classpaths                          | planned |
| Plugin lifecycle management                         | planned |
