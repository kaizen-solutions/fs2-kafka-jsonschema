package io.kaizensolutions.jsonschema

import cats.effect.Resource
import cats.effect.Sync
import com.fasterxml.jackson.databind.JsonNode
import fs2.kafka.*
import io.confluent.kafka.schemaregistry.SchemaProvider
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import io.confluent.kafka.schemaregistry.json.JsonSchemaProvider
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig.*
import io.confluent.kafka.serializers.json.KafkaJsonSchemaDeserializerConfig.*
import sttp.tapir.json.pickler.Pickler

import scala.jdk.CollectionConverters.*

object JsonSchemaDeserializerSettings {
  val default: JsonSchemaDeserializerSettings = JsonSchemaDeserializerSettings()
}

final case class JsonSchemaDeserializerSettings(
  schemaRegistryUrl: String = "http://localhost:8081",
  failOnUnknownProperties: Boolean = true,
  failOnInvalidSchema: Boolean = false,
  cacheCapacity: Int = 1024,
  client: Option[SchemaRegistryClient] = None
) { self =>
  def withSchemaRegistryUrl(url: String): JsonSchemaDeserializerSettings =
    self.copy(schemaRegistryUrl = url)

  def withFailOnUnknownProperties(b: Boolean): JsonSchemaDeserializerSettings =
    self.copy(failOnUnknownProperties = b)

  def withFailOnInvalidSchema(b: Boolean): JsonSchemaDeserializerSettings =
    self.copy(failOnInvalidSchema = b)

  def withClient(client: SchemaRegistryClient): JsonSchemaDeserializerSettings =
    self.copy(client = Option(client))

  def withCacheCapacity(value: Int): JsonSchemaDeserializerSettings =
    self.copy(cacheCapacity = value)

  def forKey[F[_], A](using Pickler[A], Sync[F]): Resource[F, KeyDeserializer[F, A]] =
    create(
      config + (JSON_KEY_TYPE -> classOf[JsonNode].getName()),
      isKey = true
    )

  def forValue[F[_], A](using Pickler[A], Sync[F]): Resource[F, ValueDeserializer[F, A]] =
    create(
      config + (JSON_VALUE_TYPE -> classOf[JsonNode].getName()),
      isKey = false
    )

  private def config = Map(
    SCHEMA_REGISTRY_URL_CONFIG -> schemaRegistryUrl,
    FAIL_INVALID_SCHEMA        -> failOnInvalidSchema,
    FAIL_UNKNOWN_PROPERTIES    -> failOnUnknownProperties
  )

  private def create[F[_], A](
    confluentConfig: Map[String, Any],
    isKey: Boolean
  )(using p: Pickler[A], sync: Sync[F]): Resource[F, Deserializer[F, A]] =
    client
      .map(Resource.pure[F, SchemaRegistryClient])
      .getOrElse:
        val providers: java.util.List[SchemaProvider] = List(new JsonSchemaProvider()).asJava
        val acquire = sync.delay(
          new CachedSchemaRegistryClient(schemaRegistryUrl, cacheCapacity, providers, confluentConfig.asJava)
        )
        val release = (client: SchemaRegistryClient) => sync.delay(client.close())
        Resource.make(acquire)(release)
      .flatMap(client => JsonSchemaDeserializer.create(isKey, confluentConfig, client))
}
