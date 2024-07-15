package io.kaizensolutions.jsonschema
import cats.effect.Resource
import cats.effect.Sync
import com.fasterxml.jackson.databind.ObjectMapper
import fs2.kafka.*
import io.confluent.kafka.schemaregistry.SchemaProvider
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import io.confluent.kafka.schemaregistry.json.JsonSchemaProvider
import io.confluent.kafka.schemaregistry.json.SpecificationVersion
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig.*
import io.confluent.kafka.serializers.json.KafkaJsonSchemaSerializerConfig.*
import sttp.tapir.json.pickler.Pickler

import java.util.Locale
import scala.jdk.CollectionConverters.*
import io.circe.generic.auto

object JsonSchemaSerializerSettings:
  val default: JsonSchemaSerializerSettings = JsonSchemaSerializerSettings()

final case class JsonSchemaSerializerSettings(
  schemaRegistryUrl: String = "http://localhost:8081",
  autoRegisterSchema: Boolean = true,
  useLatestVersion: Boolean = false,
  failOnUnknownProperties: Boolean = true,
  failOnInvalidSchema: Boolean = false,
  writeDatesAsISO8601: Boolean = false,
  jsonSchemaSpec: JsonSchemaVersion = JsonSchemaVersion.default,
  oneOfForNullables: Boolean = true,
  jsonIndentOutput: Boolean = false,
  envelopeMode: Boolean = true,
  cacheCapacity: Int = 1024,
  client: Option[SchemaRegistryClient] = None,
  mapper: Option[ObjectMapper] = None
):
  def withSchemaRegistryUrl(url: String): JsonSchemaSerializerSettings =
    copy(schemaRegistryUrl = url)

  def withAutoRegisterSchema(b: Boolean): JsonSchemaSerializerSettings =
    copy(autoRegisterSchema = b)

  def withUseLatestVersion(b: Boolean): JsonSchemaSerializerSettings =
    copy(useLatestVersion = b)

  def withFailOnUnknownProperties(b: Boolean): JsonSchemaSerializerSettings =
    copy(failOnUnknownProperties = b)

  def withFailOnInvalidSchema(b: Boolean): JsonSchemaSerializerSettings =
    copy(failOnInvalidSchema = b)

  def withWriteDatesAsISO8601(b: Boolean): JsonSchemaSerializerSettings =
    copy(writeDatesAsISO8601 = b)

  def withJsonSchemaSpec(spec: JsonSchemaVersion): JsonSchemaSerializerSettings =
    copy(jsonSchemaSpec = spec)

  def withOneOfForNullables(b: Boolean): JsonSchemaSerializerSettings =
    copy(oneOfForNullables = b)

  def withJsonIndentOutput(b: Boolean): JsonSchemaSerializerSettings =
    copy(jsonIndentOutput = b)

  def withCacheCapacity(value: Int): JsonSchemaSerializerSettings =
    copy(cacheCapacity = value)

  def withClient(client: SchemaRegistryClient): JsonSchemaSerializerSettings =
    copy(client = Option(client))

  def withMapper(mapper: ObjectMapper): JsonSchemaSerializerSettings =
    copy(mapper = Option(mapper))

  def forKey[F[_], A](using Pickler[A], Sync[F]): Resource[F, KeySerializer[F, A]] =
    create(isKey = true)

  def forValue[F[_], A](using Pickler[A], Sync[F]): Resource[F, ValueSerializer[F, A]] =
    create(isKey = false)

  private def config: Map[String, Any] = Map(
    SCHEMA_REGISTRY_URL_CONFIG -> schemaRegistryUrl,
    AUTO_REGISTER_SCHEMAS      -> autoRegisterSchema,
    USE_LATEST_VERSION         -> useLatestVersion,
    FAIL_INVALID_SCHEMA        -> failOnInvalidSchema,
    FAIL_UNKNOWN_PROPERTIES    -> failOnUnknownProperties,
    WRITE_DATES_AS_ISO8601     -> writeDatesAsISO8601.toString,
    SCHEMA_SPEC_VERSION        -> jsonSchemaSpec.toConfluentConfig,
    JSON_INDENT_OUTPUT         -> jsonIndentOutput.toString
  )

  private def create[F[_], A](isKey: Boolean)(using p: Pickler[A], sync: Sync[F]): Resource[F, Serializer[F, A]] =
    val configuredMapper = mapper.getOrElse(new ObjectMapper())
    val confluentConfig  = config.asJava
    client
      .map(Resource.pure[F, SchemaRegistryClient])
      .getOrElse:
        val providers: java.util.List[SchemaProvider] = List(new JsonSchemaProvider()).asJava
        val acquire = sync.delay(
          new CachedSchemaRegistryClient(schemaRegistryUrl, cacheCapacity, providers, confluentConfig)
        )
        val release = (client: SchemaRegistryClient) => sync.delay(client.close())
        Resource.make(acquire)(release)
      .flatMap: client =>
        JsonSchemaSerializer.create(isKey, confluentConfig, configuredMapper, client, envelopeMode)

enum JsonSchemaVersion:
  self =>
  case Draft4, Draft6, Draft7, Draft2019, Draft2020

  def toConfluentConfig: String =
    val confluentEnum = self match
      case Draft4    => SpecificationVersion.DRAFT_4
      case Draft6    => SpecificationVersion.DRAFT_6
      case Draft7    => SpecificationVersion.DRAFT_7
      case Draft2019 => SpecificationVersion.DRAFT_2019_09
      case Draft2020 => SpecificationVersion.DRAFT_2020_12
    confluentEnum.name().toLowerCase(Locale.ROOT)

object JsonSchemaVersion:
  // NOTE: This is what Tapir currently supports
  val default: JsonSchemaVersion = Draft4
