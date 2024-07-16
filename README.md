## FS2 Kafka JsonSchema

[![Continuous Integration](https://github.com/kaizen-solutions/fs2-kafka-jsonschema-support/actions/workflows/ci.yml/badge.svg)](https://github.com/kaizen-solutions/fs2-kafka-jsonschema-support/actions/workflows/ci.yml)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.kaizen-solutions/fs2-kafka-jsonschema-support_3/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.kaizen-solutions/fs2-kafka-jsonschema_3)

Provides FS2 Kafka `Serializer`s and `Deserializer`s that provide integration with Confluent Schema Registry for JSON messages with JSON Schemas.

__Note:__ _This library only works with Scala 3.3.x and above._ For Scala 2.x, see [here](https://github.com/kaizen-solutions/fs2-kafka-jsonschema-support).

This functionality is backed by the following libraries:
- [Tapir's JSON Pickler](https://tapir.softwaremill.com/en/latest/endpoint/pickler.html)
- [Tapir's JSON Schema](https://tapir.softwaremill.com/en/latest/docs/json-schema.html)
- [FS2 kafka](https://github.com/fd4s/fs2-kafka)
- [Confluent Schema Registry](https://github.com/confluentinc/schema-registry)

### Usage

Add the following to your `build.sbt`
```sbt
resolvers ++= Seq("confluent" at "https://packages.confluent.io/maven")
libraryDependencies += "io.kaizen-solutions" %% "fs2-kafka-jsonschema" % "<latest-version>"
```

### Example

Define the datatype that you would like to send/receive over Kafka via the JSON + JSON Schema format. You do this by defining your datatype and providing a `Pickler` instance for it.
The `Pickler` instance comes from the Tapir library.

```scala
import sttp.tapir.Schema.annotations.*
import sttp.tapir.json.pickler.*

final case class Book(
  @description("name of the book") name: String,
  @description("international standard book number") isbn: Int
)
object Book:
  given Pickler[Book] = Pickler.derived
```

Next, you can create a fs2 Kafka `Serializer` and `Deserializer` for this datatype and use it when building your FS2 Kafka producer/consumer.

```scala
import io.kaizensolutions.jsonschema.*
import cats.effect.*
import fs2.kafka.*

def bookSerializer[F[_]: Sync]: Resource[F, ValueSerializer[F, Book]] =
  JsonSchemaSerializerSettings.default
    .withSchemaRegistryUrl("http://localhost:8081")
    .forValue[F, Book]

def bookDeserializer[F[_]: Sync]: Resource[F, ValueDeserializer[F, Book]] =
  JsonSchemaDeserializerSettings.default
    .withSchemaRegistryUrl("http://localhost:8081")
    .forValue[F, Book]
```
