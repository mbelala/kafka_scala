package org.esgi.project


import java.time.Instant
import java.util.{Properties, UUID}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.typesafe.config.{Config, ConfigFactory}
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.streams.kstream.{Materialized, Serialized, TimeWindows, Windowed}
import org.apache.kafka.streams.scala.ImplicitConversions._
import org.apache.kafka.streams.scala._
import org.apache.kafka.streams.scala.kstream._
import org.apache.kafka.streams.kstream.Printed
import org.apache.kafka.streams.state.{QueryableStoreTypes, ReadOnlyKeyValueStore, ReadOnlyWindowStore, WindowStoreIterator}
import org.apache.kafka.streams.{KafkaStreams, StreamsConfig, Topology}
import org.esgi.project.models.{BestView, View, view_count}
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json.{JsValue, Json}
import io.github.azhur.kafkaserdeplayjson.{PlayJsonSupport => coucou}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

object Main extends PlayJsonSupport with coucou {
  implicit val system: ActorSystem = ActorSystem.create("this-system")
  implicit val materializer: ActorMaterializer = ActorMaterializer.create(system)
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  val logger: Logger = LoggerFactory.getLogger(this.getClass)
  val config: Config = ConfigFactory.load()

  // Configure Kafka Streams

  val props: Properties = {
    val p = new Properties()
    p.put(StreamsConfig.APPLICATION_ID_CONFIG, "manalkafka")
    p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "212.47.229.218:9092")
    p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
    p
  }

  // randomize store names
  val randomUuid = UUID.randomUUID.toString
  val thirtySecondsStoreName = s"thirtySecondsVisitsStore-$randomUuid"
  val oneMinuteStoreName = s"oneMinuteVisitsStore-$randomUuid"
  val fiveMinutesStoreName = s"fiveMinuteVisitsStore-$randomUuid"
  val StoreView = s"ManalStoreView-$randomUuid"
  val StoreLike = s"StoreLike-$randomUuid"

  // Run streams
  val streams: KafkaStreams = new KafkaStreams(buildProcessingGraph, props)
  streams.start()

  def buildProcessingGraph: Topology = {
    import Serdes._

    val builder: StreamsBuilder = new StreamsBuilder

    val viewsStream: KStream[String, JsValue] = builder.stream[String, String]("views")
      .mapValues(value => Json.parse(value))

    //viewsStream.print(Printed.toSysOut[String, JsValue])

    val likesStream: KStream[String, JsValue] = builder.stream[String, String]("likes")
      .mapValues(value => Json.parse(value))

    //val top10Stream : KStream[Int, ]

    // repartition by URL instead of random id
    val groupedByUrl: KGroupedStream[Int, JsValue] = viewsStream
      .map { (_, view) =>
        val parsedVisit = view.as[View]
        (parsedVisit._id, view)
      }
      .groupByKey(Serialized.`with`(Serdes.Integer, PlaySerdes.create))


    // window per asked time frames
    val thirtySecondsWindowedVisits: TimeWindowedKStream[Int, JsValue] = groupedByUrl
      .windowedBy(TimeWindows.of(30.seconds.toMillis).advanceBy(1.second.toMillis))

    val oneMinuteWindowedVisits: TimeWindowedKStream[Int, JsValue] = groupedByUrl
      .windowedBy(TimeWindows.of(1.minute.toMillis).advanceBy(1.second.toMillis))

    val fiveMinutesWindowedVisits: TimeWindowedKStream[Int, JsValue] = groupedByUrl
      .windowedBy(TimeWindows.of(5.minute.toMillis).advanceBy(1.second.toMillis))

    // count hits
    val bestviews: KTable[Int, view_count] = groupedByUrl
//      .count()(Materialized.as(StoreView).withValueSerde(Serdes.Long))
      .aggregate(view_count(0,"",0))((_, newValue , currentValue) => view_count(_id = currentValue._id,title = currentValue.title, view_count = currentValue.view_count+ 1))(Materialized.as(StoreView).withValueSerde(toSerde)) // aggregate version



    val oneMinuteTable: KTable[Windowed[Int], Long] = oneMinuteWindowedVisits
      .aggregate(0L)((_, _, currentValue) => currentValue + 1)(Materialized.as(oneMinuteStoreName).withValueSerde(Serdes.Long)) // aggregate version

    val fiveMinuteTable: KTable[Windowed[Int], Long] = fiveMinutesWindowedVisits
      .count()(Materialized.as(fiveMinutesStoreName).withValueSerde(Serdes.Long))

    // TODO: Count visits per category (second part of the URL)

    // join metrics & visits topics and create a new object which will be written to the topic augmented-metrics (use VisitWithLatency case class)


    // write it to Kafka

    // TODO: compute mean latency per URL

    builder.build()
  }

  def routes(): Route = path("stats" / Segment) { period: String =>
    get {
      import scala.collection.JavaConverters._
      period match {
        case "30s" =>
          // load our materialized store
          val best: ReadOnlyKeyValueStore[Int, view_count] = streams.store( StoreView, QueryableStoreTypes.keyValueStore[Int, view_count]())
          // fetch all available keys
          val availableKeys = best.all().asScala.toList.sortBy(_.value.view_count).take(10)
          // define our time interval to fetch the last window (nearest one to now)
          //val toTime = Instant.now().toEpochMilli
          //val fromTime = toTime - (30 * 1000)

          complete(
          //construire une list des valeurs du view
              for(k<-availableKeys)
            yield BestView(title=k.value.title, views=k.value.view_count)
      )
        case "1m" =>
          // do the same for a 1 minute window
          complete(
            HttpResponse(StatusCodes.NotFound, entity = "Not found")
          )
        case "5m" =>
          // do the same for a 5 minutes window
          complete(
            HttpResponse(StatusCodes.NotFound, entity = "Not found")
          )
        case _ =>
          // unhandled period asked
          complete(
            HttpResponse(StatusCodes.NotFound, entity = "Not found")
          )
      }
    }
  }

  def main(args: Array[String]) {
    Http().bindAndHandle(routes(), "0.0.0.0", 8080)
    logger.info(s"App started on 8080")
  }
}
