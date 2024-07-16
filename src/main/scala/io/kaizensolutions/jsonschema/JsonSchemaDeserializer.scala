package io.kaizensolutions.jsonschema

import cats.effect.{Resource, Sync}
import cats.syntax.all.*
import com.fasterxml.jackson.databind.JsonNode
import fs2.kafka.*
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import io.confluent.kafka.serializers.json.KafkaJsonSchemaDeserializer
import sttp.tapir.json.pickler.Pickler

import scala.jdk.CollectionConverters.*

private[jsonschema] object JsonSchemaDeserializer:
  def create[F[_], A](
    isKey: Boolean,
    confluentConfig: Map[String, Any],
    client: SchemaRegistryClient
  )(using p: Pickler[A], sync: Sync[F]): Resource[F, Deserializer[F, A]] =
    Resource
      .make(acquire = sync.delay(KafkaJsonSchemaDeserializer[JsonNode](client)))(des => sync.delay(des.close()))
      .evalTap: des =>
        sync.delay(des.configure(confluentConfig.asJava, isKey))
      .map(u => JsonSchemaDeserializer(u).deserializer)

private class JsonSchemaDeserializer[F[_], A](underlying: KafkaJsonSchemaDeserializer[JsonNode])(using
  sync: Sync[F],
  pickler: Pickler[A]
):
  import pickler.innerUpickle.*
  private given Reader[A] = pickler.innerUpickle.reader

  val deserializer: Deserializer[F, A] =
    Deserializer
      .delegate(underlying)
      .map: node =>
        Either.catchNonFatal(read[A](node.toString()))
      .rethrow
