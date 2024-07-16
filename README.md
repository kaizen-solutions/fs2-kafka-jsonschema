## FS2 Kafka JsonSchema support ##

[![Continuous Integration](https://github.com/kaizen-solutions/fs2-kafka-jsonschema-support/actions/workflows/ci.yml/badge.svg)](https://github.com/kaizen-solutions/fs2-kafka-jsonschema-support/actions/workflows/ci.yml)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.kaizen-solutions/fs2-kafka-jsonschema-support_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.kaizen-solutions/fs2-kafka-jsonschema-support_2.13)
[![JitPack](https://jitpack.io/v/kaizen-solutions/fs2-kafka-jsonschema-support.svg)](https://jitpack.io/#kaizen-solutions/fs2-kafka-jsonschema-support)


Provides FS2 Kafka `Serializer`s and `Deserializer`s that provide integration with Confluent Schema Registry for JSON messages with JSON Schemas. 

__Note:__ _This library only works with Scala 3.3.x and above._ For Scala 2.x, see [here](https://github.com/kaizen-solutions/fs2-kafka-jsonschema-support).

This functionality is backed by the following libraries:
- [Tapir's JSON Pickler](https://tapir.softwaremill.com/en/latest/endpoint/pickler.html)
- [Tapir's JSON Schema](https://tapir.softwaremill.com/en/latest/docs/json-schema.html)
- [FS2 kafka](https://github.com/fd4s/fs2-kafka)
- [Confluent Schema Registry](https://github.com/confluentinc/schema-registry)

### Usage ###

Add the following to your `build.sbt`
```sbt
resolvers ++= Seq("confluent" at "https://packages.confluent.io/maven")
libraryDependencies += "io.kaizen-solutions" %% "fs2-kafka-jsonschema" % "<latest-version>"
```
