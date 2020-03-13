package org.esgi.project.models

import play.api.libs.json.{Json, OFormat}

case class BestScore(
                     id: Int,
                     score: Double
                   )

object BestScore {
  implicit val format: OFormat[BestScore] = Json.format[BestScore]
}
