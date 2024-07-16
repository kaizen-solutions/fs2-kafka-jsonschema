package io.kaizensolutions.jsonschema

import cats.effect.*
import cats.syntax.all.*
import fs2.Stream
import fs2.kafka.*
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException
import io.confluent.kafka.schemaregistry.client.{CachedSchemaRegistryClient, SchemaRegistryClient}
import io.confluent.kafka.schemaregistry.json.JsonSchemaProvider
import io.confluent.kafka.schemaregistry.{CompatibilityLevel, SchemaProvider}
import io.github.embeddedkafka.schemaregistry.*
import org.apache.kafka.common.errors.{InvalidConfigurationException, SerializationException as UnderlyingSerializationException}
import sttp.tapir.Schema.annotations.*
import sttp.tapir.json.pickler.Pickler
import weaver.*

import java.io.File
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

object JsonSchemaSerdesSpec extends IOSuite:
  override type Res = Unit
  override def sharedResource: Resource[IO, Res] =
    val acquire = IO.delay(EmbeddedKafka.start())
    val release = (kafka: EmbeddedKWithSR) => IO.delay(kafka.stop(true))
    Resource.make(acquire)(release).void

  override def maxParallelism = 1

  test(
    "JsonSchemaSerialization will automatically register the JSON Schema and allow you to send JSON data to Kafka"
  ):
    val examplePersons = List.fill(100)(PersonV1("Bob", 40, List(Book("Bob the builder", 1337))))
    val serSettings    = JsonSchemaSerializerSettings.default
    producerTest[IO, PersonV1](
      schemaRegistry[IO],
      serSettings,
      "example-topic-persons",
      examplePersons,
      _.name,
      _.map: result =>
        expect(result == examplePersons)
    )

  test("Enabling use latest (and disabling auto-registration) without configuring the client will fail"):
    val examplePersons = List.fill(100)(PersonV1("Bob", 40, List(Book("Bob the builder", 1337))))
    val serSettings    = JsonSchemaSerializerSettings.default.withAutoRegisterSchema(false).withUseLatestVersion(true)
    producerTest[IO, PersonV1](
      noJsonSupportSchemaRegistry[IO],
      serSettings,
      "example-topic-persons",
      examplePersons,
      _.name,
      _.as(expect(false, "Expected an exception to be thrown"))
        .handleError:
          case r: UnderlyingSerializationException =>
            println(r)
            expect(r.getCause().getMessage.startsWith("Invalid schema"))
    )

  test("Attempting to publish an incompatible change with auto-registration will fail"):
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
      _.as(expect(false, "Expected an exception to be thrown"))
        .handleError:
          case r: InvalidConfigurationException =>
            expect(r.getMessage.startsWith("Schema being registered is incompatible with an earlier schema"))
    )

  test(
    "Attempting to publish an incompatible change without auto-registration (using latest server schema) will fail"
  ):
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
      _.as(expect(false, "Expected an exception to be thrown"))
        .handleError:
          case t: UnderlyingSerializationException =>
            val message = t.getCause.getMessage
            expect(
              message.startsWith("Incompatible schema") && message.endsWith(
                "Set latest.compatibility.strict=false to disable this check"
              )
            )
    )

  test(
    "Attempting to publish an incompatible change without auto-registration and not using the latest schema will fail"
  ):
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
      _.as(expect(false, "Expected an exception to be thrown"))
        .handleError:
          case exception: UnderlyingSerializationException =>
            expect(exception.getCause.getMessage.contains("Schema not found; error code: 40403"))
    )

  test("Publishing a forward compatible change with auto-registration is allowed (in forward-compatibility mode)"):
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
      assertion = _.map(result => expect(result == examplePersons))
    )

  test(
    "Reading data back from the topic with the latest schema is allowed provided you compensate for missing fields in your Decoder"
  ):
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

    result.map: result =>
      expect(result == (true, true))

  test("Reading data back from the topic with an older schema is allowed"):
    val settings = JsonSchemaDeserializerSettings.default

    val result: IO[Long] =
      consumeFromKafka[IO, PersonV1](
        schemaRegistry[IO],
        settings,
        "example-consumer-older",
        "example-topic-persons",
        200
      ).compile.foldChunks(0L)((acc, next) => acc + next.size)

    result.map: res =>
      expect(res == 200L)

  def producerTest[F[_]: Async, A: Pickler](
    client: Resource[F, SchemaRegistryClient],
    settings: JsonSchemaSerializerSettings,
    topic: String,
    input: List[A],
    key: A => String,
    assertion: F[List[A]] => F[Expectations],
    compatibilityMode: Option[CompatibilityLevel] = None
  ): F[Expectations] =
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

  def kafkaProducer[F[_]: Async, K, V](using
    keySerializer: KeySerializer[F, K],
    valueSerializer: ValueSerializer[F, V]
  ): Stream[F, KafkaProducer[F, K, V]] =
    val settings: ProducerSettings[F, K, V] =
      ProducerSettings[F, K, V].withBootstrapServers("localhost:6001")
    KafkaProducer.stream(settings)

  def kafkaConsumer[F[_]: Async, K, V](groupId: String)(implicit
    keyDeserializer: KeyDeserializer[F, K],
    valueDeserializer: ValueDeserializer[F, V]
  ): Stream[F, KafkaConsumer[F, K, V]] =
    val settings = ConsumerSettings[F, K, V]
      .withBootstrapServers("localhost:6001")
      .withGroupId(groupId)
      .withAutoOffsetReset(AutoOffsetReset.Earliest)
    KafkaConsumer.stream(settings)

  def schemaRegistry[F[_]](using sync: Sync[F]): Resource[F, SchemaRegistryClient] =
    val providers: java.util.List[SchemaProvider] = List(new JsonSchemaProvider()).asJava
    val acquire = Sync[F].delay:
      new CachedSchemaRegistryClient("http://localhost:6002", 1024, providers, Map.empty.asJava)
    val release = (client: SchemaRegistryClient) => sync.delay(client.close())
    Resource.make(acquire)(release)

  def noJsonSupportSchemaRegistry[F[_]](using sync: Sync[F]): Resource[F, SchemaRegistryClient] =
    Resource.make(
      Sync[F].delay:
        new CachedSchemaRegistryClient("http://localhost:6002", 1024)
    )(client => sync.delay(client.close()))
