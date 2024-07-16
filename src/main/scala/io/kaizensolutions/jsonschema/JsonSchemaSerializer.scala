package io.kaizensolutions.jsonschema

import cats.effect.{Resource, Sync}
import cats.syntax.functor.*
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import fs2.kafka.*
import io.circe.syntax.*
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import io.confluent.kafka.schemaregistry.json.{JsonSchema, JsonSchemaUtils}
import io.confluent.kafka.serializers.json.KafkaJsonSchemaSerializer
import sttp.apispec.circe.*
import sttp.apispec.{ExampleSingleValue, SchemaType}
import sttp.tapir.docs.apispec.schema.*
import sttp.tapir.json.pickler.Pickler

import scala.jdk.CollectionConverters.*

private[jsonschema] object JsonSchemaSerializer:
  def create[F[_], A](
    isKey: Boolean,
    confluentConfig: java.util.Map[String, Any],
    mapper: ObjectMapper,
    client: SchemaRegistryClient,
    envelopeMode: Boolean
  )(using p: Pickler[A], sync: Sync[F]): Resource[F, Serializer[F, A]] =
    Resource
      .make(sync.delay(KafkaJsonSchemaSerializer[JsonNode](client, confluentConfig)))(client => sync.delay(client.close()))
      .evalTap: client =>
        sync.delay(client.configure(confluentConfig, isKey))
      .evalMap: underlying =>
        JsonSchemaSerializer(underlying, mapper, envelopeMode).serializer

private class JsonSchemaSerializer[F[_], A](
  underlying: KafkaJsonSchemaSerializer[JsonNode],
  mapper: ObjectMapper,
  envelopeMode: Boolean
)(using
  sync: Sync[F],
  pickler: Pickler[A]
):
  import pickler.innerUpickle.*
  private given Writer[A] = pickler.innerUpickle.writer

  private val makeJsonSchema: F[JsonSchema] =
    val tapirSchema = pickler.schema
    val tapirJsonSchema =
      TapirSchemaToJsonSchema(tapirSchema, markOptionsAsNullable = false, metaSchema = MetaSchemaDraft04)
    sync.delay:
      JsonSchema:
        tapirJsonSchema.asJson.deepDropNullValues.noSpaces

  def serializer: F[Serializer[F, A]] =
    makeJsonSchema
      .map: jsonSchema =>
        Serializer
          .delegate[F, JsonNode](underlying)
          .contramap[A]: value =>
            val str  = write(value)
            val node = mapper.readValue(str, classOf[JsonNode])
            if envelopeMode then JsonSchemaUtils.envelope(jsonSchema, node)
            else node
          .suspend
