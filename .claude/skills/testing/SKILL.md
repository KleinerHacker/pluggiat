---
name: testing
description: Rules for writing tests - JUnit, TestFX for headless Java FX UI tests, package mirroring, coverage and the developer test vs. regression test ("RT" suffix) split. Load before creating or changing any test class.
---

# Testing

* JUnit test system MUST be used
* For Java FX UI tests use TestFX framework
  * Must run in the background without an open UI
* Every use case MUST be tested
* Code coverage should reach 100%
* The package structure of the production code is to be mirrored
* EVERY test method is to be documented with a detailed KDoc describing the use case
* ALL test data MUST be written in ENGLISH
    * This covers sample texts, names, JSON fixtures and expected values
    * Exception: a test that verifies a specific language or locale on purpose
* Tests are to be split into two categories
    * **Developer tests** - Simple unit tests covering individual pieces of functionality
      * All Test Classes without suffix of "RT"
    * **Regression tests** - Tests feeding input through several classes of the library and
      comparing the produced result against a fixed reference (e.g. a golden file)
      * Identified by Class Name ending with "RT"
      * A test spanning several classes without comparing against a fixed reference stays a
        developer test without the "RT" suffix
    * The "IT" suffix and integration tests are FORBIDDEN - this project has no application module
