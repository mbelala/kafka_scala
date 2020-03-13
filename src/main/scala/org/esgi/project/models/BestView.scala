package org.esgi.project.models

import play.api.libs.json.{Json, OFormat}

case class BestView(
                  title: String,
                  views: Int
                )

object BestView {
  implicit val format: OFormat[BestView] = Json.format[BestView]
}
