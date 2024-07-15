package io.kaizensolutions.jsonschema

import cats.effect.*
import cats.syntax.all.*
import com.dimafeng.testcontainers.DockerComposeContainer.ComposeFile
import com.dimafeng.testcontainers.{DockerComposeContainer, ExposedService}
import com.dimafeng.testcontainers.munit.TestContainersForAll
import fs2.Stream
import fs2.kafka.*
import io.confluent.kafka.schemaregistry.client.{CachedSchemaRegistryClient, SchemaRegistryClient}
import io.confluent.kafka.schemaregistry.json.JsonSchemaProvider
import io.confluent.kafka.schemaregistry.{CompatibilityLevel, SchemaProvider}
import munit.CatsEffectSuite
import org.apache.kafka.common.errors.{
  InvalidConfigurationException,
  SerializationException as UnderlyingSerializationException
}
import sttp.tapir.Schema.annotations.*
import sttp.tapir.json.pickler.Pickler

import java.io.File
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

class JsonSchemaSerDesSpec extends CatsEffectSuite with TestContainersForAll:
  test(
    "JsonSchemaSerialization will automatically register the JSON Schema and allow you to send JSON data to Kafka"
  ) {
    val examplePersons = List.fill(100)(PersonV1("Bob", 40, List(Book("Bob the builder", 1337))))
    val serSettings    = JsonSchemaSerializerSettings.default
    producerTest[IO, PersonV1](
      schemaRegistry[IO],
      serSettings,
      "example-topic-persons",
      examplePersons,
      _.name,
      result => assertIO(result, examplePersons)
    )
  }

  test("Enabling use latest (and disabling auto-registration) without configuring the client will fail") {
    val examplePersons = List.fill(100)(PersonV1("Bob", 40, List(Book("Bob the builder", 1337))))
    val serSettings    = JsonSchemaSerializerSettings.default.withAutoRegisterSchema(false).withUseLatestVersion(true)
    producerTest[IO, PersonV1](
      noJsonSupportSchemaRegistry[IO],
      serSettings,
      "example-topic-persons",
      examplePersons,
      _.name,
      result =>
        interceptIO[RuntimeException](result)
          .map: t =>
            assert(t.getCause.getMessage.contains("Invalid schema"))
    )
  }

  test("Attempting to publish an incompatible change with auto-registration will fail") {
    val settings =
      JsonSchemaSerializerSettings.default
        .withAutoRegisterSchema(true)

    val topic = "example-topic-persons"

    val examplePersons =
      List.fill(100)(PersonV2Bad("Bob", 40, List(Book("Bob the builder - incompatible rename edition", 1337))))

    producerTest[IO, PersonV2Bad](
      schemaRegistry[IO],
      settings,
      topic,
      examplePersons,
      _.name,
      result =>
        interceptIO[InvalidConfigurationException](result)
          .map(_.getMessage.startsWith("Schema being registered is incompatible with an earlier schema"))
    )
  }

  test(
    "Attempting to publish an incompatible change without auto-registration (using latest server schema) will fail"
  ) {
    val settings =
      JsonSchemaSerializerSettings.default
        .withAutoRegisterSchema(false)
        .withUseLatestVersion(true)

    val topic = "example-topic-persons"

    val examplePersons =
      List.fill(100)(PersonV2Bad("Bob", 40, List(Book("Bob the builder - incompatible rename edition", 1337))))

    producerTest[IO, PersonV2Bad](
      schemaRegistry[IO],
      settings,
      topic,
      examplePersons,
      _.name,
      result =>
        interceptIO[UnderlyingSerializationException](result).map: t =>
          val message = t.getCause.getMessage
          message.startsWith("Incompatible schema") && message.endsWith(
            "Set latest.compatibility.strict=false to disable this check"
          )
    )
  }

  test(
    "Attempting to publish an incompatible change without auto-registration and not using the latest schema will fail"
  ) {
    val settings =
      JsonSchemaSerializerSettings.default
        .withAutoRegisterSchema(false)

    val topic = "example-topic-persons"

    val examplePersons =
      List.fill(100)(PersonV2Bad("Bob", 40, List(Book("Bob the builder - incompatible rename edition", 1337))))

    producerTest[IO, PersonV2Bad](
      schemaRegistry[IO],
      settings,
      topic,
      examplePersons,
      _.name,
      result =>
        interceptIO[UnderlyingSerializationException](result)
          .map: exception =>
            exception.getCause.getMessage.contains("Schema not found; error code: 40403")
    )
  }

  test("Publishing a forward compatible change with auto-registration is allowed (in forward-compatibility mode)") {
    val settings =
      JsonSchemaSerializerSettings.default
        .withAutoRegisterSchema(true)

    val topic = "example-topic-persons"

    val examplePersons =
      List.fill(100)(
        PersonV2Good(
          "Bob",
          40,
          List(Book("Bob the builder - incompatible rename edition", 1337)),
          List("coding"),
          Some("more information")
        )
      )

    producerTest[IO, PersonV2Good](
      compatibilityMode = Option(CompatibilityLevel.FORWARD),
      client = schemaRegistry[IO],
      settings = settings,
      topic = topic,
      input = examplePersons,
      key = _.name,
      assertion = result => assertIO(result, examplePersons)
    )
  }

  test(
    "Reading data back from the topic with the latest schema is allowed provided you compensate for missing fields in your Decoder"
  ) {
    val settings = JsonSchemaDeserializerSettings.default

    val result: IO[(Boolean, Boolean)] =
      consumeFromKafka[IO, PersonV2Good](
        schemaRegistry[IO],
        settings,
        "example-consumer",
        "example-topic-persons",
        200
      ).compile.toList
        .map(list =>
          (
            list.take(100).forall(each => each.hobbies.isEmpty && each.optionalField.isEmpty),
            list.drop(100).forall(each => each.hobbies.nonEmpty && each.optionalField.nonEmpty)
          )
        )

    assertIO(result, (true, true))
  }

  test("Reading data back from the topic with an older schema is allowed") {
    val settings = JsonSchemaDeserializerSettings.default

    val result: IO[Long] =
      consumeFromKafka[IO, PersonV1](
        schemaRegistry[IO],
        settings,
        "example-consumer-older",
        "example-topic-persons",
        200
      ).compile.foldChunks(0L)((acc, next) => acc + next.size)

    assertIO(result, 200L)
  }

  def producerTest[F[_]: Async, A: Pickler](
    client: Resource[F, SchemaRegistryClient],
    settings: JsonSchemaSerializerSettings,
    topic: String,
    input: List[A],
    key: A => String,
    assertion: F[List[A]] => F[Any],
    compatibilityMode: Option[CompatibilityLevel] = None
  ): F[Any] = {
    val produceElements: F[List[A]] =
      Stream
        .resource[F, SchemaRegistryClient](client)
        .evalTap: client =>
          compatibilityMode.fold(ifEmpty = ().pure[F]): newCompat =>
            val subject = s"$topic-value" // change accordingly if you use isKey
            Async[F].delay:
              client.updateCompatibility(subject, newCompat.name)
        .map(settings.withClient)
        .flatMap(settings => Stream.resource(settings.forValue[F, A]))
        .flatMap: serializer =>
          given ValueSerializer[F, A] = serializer
          kafkaProducer[F, String, A]
        .flatMap { kafkaProducer =>
          Stream
            .emits[F, A](input)
            .chunks
            .evalMap { chunkA =>
              kafkaProducer.produce(
                ProducerRecords(
                  chunkA.map(a => ProducerRecord[String, A](topic, key(a), a))
                )
              )
            }
            .groupWithin(1000, 1.second)
            .evalMap(_.flatTraverse(_.map(_.map(_._1.value))))
            .flatMap(Stream.chunk)
        }
        .compile
        .toList

    assertion(produceElements)
  }

  def consumeFromKafka[F[_]: Async, A: Pickler](
    client: Resource[F, SchemaRegistryClient],
    settings: JsonSchemaDeserializerSettings,
    groupId: String,
    topic: String,
    numberOfElements: Long
  ): Stream[F, A] =
    Stream
      .resource(client)
      .map(settings.withClient)
      .flatMap(settings => Stream.resource(settings.forValue[F, A]))
      .flatMap: des =>
        given ValueDeserializer[F, A] = des
        kafkaConsumer[F, Option[String], A](groupId)
      .evalTap(_.subscribeTo(topic))
      .flatMap(_.stream)
      .map(_.record.value)
      .take(numberOfElements)

  override type Containers = DockerComposeContainer

  override def startContainers(): Containers =
    DockerComposeContainer
      .Def(
        composeFiles = ComposeFile(Left(new File("./docker-compose.yaml"))),
        exposedServices = List(
          ExposedService(name = "kafka-schema-registry", 8081),
          ExposedService(name = "kafka1", 9092)
        )
      )
      .start()

  def kafkaProducer[F[_]: Async, K, V](using
    keySerializer: KeySerializer[F, K],
    valueSerializer: ValueSerializer[F, V]
  ): Stream[F, KafkaProducer[F, K, V]] = {
    val settings: ProducerSettings[F, K, V] =
      ProducerSettings[F, K, V].withBootstrapServers("localhost:9092")
    KafkaProducer.stream(settings)
  }

  def kafkaConsumer[F[_]: Async, K, V](groupId: String)(implicit
    keyDeserializer: KeyDeserializer[F, K],
    valueDeserializer: ValueDeserializer[F, V]
  ): Stream[F, KafkaConsumer[F, K, V]] = {
    val settings = ConsumerSettings[F, K, V]
      .withBootstrapServers("localhost:9092")
      .withGroupId(groupId)
      .withAutoOffsetReset(AutoOffsetReset.Earliest)
    KafkaConsumer.stream(settings)
  }

  def schemaRegistry[F[_]](using sync: Sync[F]): Resource[F, SchemaRegistryClient] =
    val providers: java.util.List[SchemaProvider] = List(new JsonSchemaProvider()).asJava
    val acquire = Sync[F].delay:
      new CachedSchemaRegistryClient("http://localhost:8081", 1024, providers, Map.empty.asJava)
    val release = (client: SchemaRegistryClient) => sync.delay(client.close())
    Resource.make(acquire)(release)

  def noJsonSupportSchemaRegistry[F[_]](using sync: Sync[F]): Resource[F, SchemaRegistryClient] =
    Resource.make(
      Sync[F].delay:
        new CachedSchemaRegistryClient("http://localhost:8081", 1024)
    )(client => sync.delay(client.close()))

final case class Book(
  @description("name of the book") name: String,
  @description("international standard book number") isbn: Int
)
object Book:
  given Pickler[Book] = Pickler.derived

final case class PersonV1(
  @description("name of the person") name: String,
  @description("age of the person") age: Int,
  @description("A list of books that the person has read") books: List[Book]
)
object PersonV1:
  given Pickler[PersonV1] = Pickler.derived

// V2 is backwards incompatible with V1 because the key has changed
final case class PersonV2Bad(
  @description("name of the person") name: String,
  @description("age of the person") age: Int,
  @description("A list of books that the person has read") booksRead: List[Book]
)
object PersonV2Bad:
  given Pickler[PersonV2Bad] = Pickler.derived

final case class PersonV2Good(
  @description("name of the person") name: String,
  @description("age of the person") age: Int,
  @description("A list of books that the person has read") books: List[Book],
  @description("A list of hobbies") @default(Nil)
  hobbies: List[String],
  @description("An optional field to add extra information") @default(Option.empty[String])
  optionalField: Option[String]
)
object PersonV2Good:
  given Pickler[PersonV2Good] = Pickler.derived
