/*
 * Copyright 2015 data Artisans GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dataartisans.flinktraining.exercises.table_scala.stream.popularPlaces

import com.dataartisans.flinktraining.exercises.datastream_java.utils.GeoUtils.{IsInNYC, ToCellId, ToCoords}
import com.dataartisans.flinktraining.exercises.table_java.sources.TaxiRideTableSource
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.api.scala._
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.table.api.{Table, TableEnvironment}
import org.apache.flink.types.Row

object PopularPlacesSql {

  def main(args: Array[String]) {

    // read parameters
    val params = ParameterTool.fromArgs(args)
    val input = params.getRequired("input")

    val maxEventDelay = 60       // events are out of order by max 60 seconds
    val servingSpeedFactor = 600 // events of 10 minutes are served in 1 second

    // set up streaming execution environment
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)

    // create TableEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)

    // register TaxiRideTableSource as table "TaxiRides"
    tEnv.registerTableSource(
      "TaxiRides",
      new TaxiRideTableSource(input, maxEventDelay, servingSpeedFactor))

    // register user-defined functions
    tEnv.registerFunction("isInNYC", new IsInNYC)
    tEnv.registerFunction("toCellId", new ToCellId)
    tEnv.registerFunction("toCoords", new ToCoords)

    val popPlaces: Table = tEnv.sql(
      """
        |SELECT
        |  toCoords(cell), wstart, wend, isStart, popCnt
        |FROM
        |  (SELECT
        |    cell,
        |    isStart,
        |    HOP_START(eventTime, INTERVAL '5' MINUTE, INTERVAL '15' MINUTE) AS wstart,
        |    HOP_END(eventTime, INTERVAL '5' MINUTE, INTERVAL '15' MINUTE) AS wend,
        |    COUNT(isStart) AS popCnt
        |  FROM
        |    (SELECT
        |      eventTime,
        |      isStart,
        |      CASE WHEN isStart THEN toCellId(startLon, startLat) ELSE toCellId(endLon, endLat) END AS cell
        |    FROM TaxiRides
        |    WHERE isInNYC(startLon, startLat) AND isInNYC(endLon, endLat))
        |  GROUP BY cell, isStart, HOP(eventTime, INTERVAL '5' MINUTE, INTERVAL '15' MINUTE))
        |WHERE popCnt > 20
        |""".stripMargin)

    // convert Table into an append stream and print it
    tEnv.toAppendStream[Row](popPlaces).print

    // execute query
    env.execute
  }

}
