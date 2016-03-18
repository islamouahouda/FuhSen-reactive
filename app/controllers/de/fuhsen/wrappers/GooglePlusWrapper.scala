/*
 * Copyright (C) 2016 EIS Uni-Bonn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package controllers.de.fuhsen.wrappers

import com.typesafe.config.ConfigFactory
import controllers.de.fuhsen.wrappers.dataintegration.{SilkTransformableTrait, SilkTransformationTask}
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import play.api.libs.ws._

import scala.concurrent.Future

/**
  * Wrapper around the GooglePlus API.
  */

case class Person(id: String, displayName: String)

class GooglePlusWrapper extends RestApiWrapperTrait with SilkTransformableTrait {
  override def apiUrl: String = ConfigFactory.load.getString("gplus.user.url")

  override def queryParams: Map[String, String] = Map(
    "key" -> ConfigFactory.load.getString("gplus.app.key")
  )

  /** Returns for a given query string the representation as query parameter. */
  override def searchQueryAsParam(queryString: String): Map[String, String] = Map(
    "query" -> queryString
  )

  /** The type of the transformation input. */
  override def datasetPluginType: DatasetPluginType = DatasetPluginType.JsonDatasetPlugin

  override def silkTransformationRequestTasks = Seq(
    SilkTransformationTask(
      transformationTaskId = ConfigFactory.load.getString("silk.transformation.task.gplus.person"),
      createSilkTransformationRequestBody(
        basePath = "",
        uriPattern = "http://vocab.cs.uni-bonn.de/fuhsen/search/entity/gplus/{id}"
      )
    ),
    SilkTransformationTask(
      transformationTaskId = ConfigFactory.load.getString("silk.transformation.task.gplus.organization"),
      createSilkTransformationRequestBody(
        basePath = "organizations",
        uriPattern = ""
      )
    )
  )

  /** The project id of the Silk project */
  override def projectId: String = ConfigFactory.load.getString("silk.socialApiProject.id")

  implicit val peopleReader = Json.reads[Person]

  // Returns a JSON array of person objects
  override def customResponseHandling(implicit ws: WSClient) = Some(apiResponse => {
    val people = (Json.parse(apiResponse) \ "items").as[List[Person]]
    for {
      results <- requestAllPeople(people)
    } yield {
      logIfBadRequestsExist(results)
      validResponsesToJSONString(results)
    }
  })

  private def requestAllPeople(people: List[Person])
                              (implicit ws: WSClient): Future[List[WSResponse]] = {
    Future.sequence(people.map { person =>
      //Google plus get person request
      val request = ws.url(apiUrl + "/" + person.id)
          .withQueryString(queryParams.toSeq: _*)
      request.get()
    })
  }

  private def validResponsesToJSONString(results: List[WSResponse]): String = {
    val validResults = results.filter(_.status == 200) flatMap { result =>
      if (result.status == 200) {
        Some(result.body)
      } else {
        None
      }
    }
    // Make JSON array
    validResults.mkString("[", ",", "]")
  }

  private def logIfBadRequestsExist(results: List[WSResponse]): Unit = {
    if (results.exists(_.status != 200)) {
      if (results.forall(_.status != 200)) {
        Logger.warn("All requests failed!")
      } else {
        logPartialRequestFailures(results)
      }
    }
  }

  private def logPartialRequestFailures(results: List[WSResponse]): Unit = {
    val failedRequests = results.filter(_.status != 200)
    val count = failedRequests.size
    val example = failedRequests.head
    Logger.warn(s"$count / ${results.size} requests failed. Example: Status Code: " + example.status + ", Body:" + example.body)
  }

  /**
    * Returns the globally unique URI String of the source that is wrapped. This is used to track provenance.
    */
  override def sourceLocalName: String = "gplus"
}