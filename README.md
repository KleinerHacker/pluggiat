# pluggiat

pluggiat is a plugin manager system for the JVM, written in Kotlin. It discovers, loads and
manages plugins for a host application, so the host itself does not have to implement plugin
discovery, isolation or lifecycle handling.

## Features

* Plugin discovery and loading _(planned)_
* Isolated plugin classpaths _(planned)_
* Plugin lifecycle management (register, start, stop, unregister) _(planned)_

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
