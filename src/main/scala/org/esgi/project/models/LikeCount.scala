package org.esgi.project.models

import play.api.libs.json.{Json, OFormat}

case class LikeCount(
                       id: Int,
                       count: Int,
                       somme: Double
                     )

object LikeCount {
  implicit val format: OFormat[LikeCount] = Json.format[LikeCount]
}

