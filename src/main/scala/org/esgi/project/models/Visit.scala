package org.esgi.project.models

import play.api.libs.json.{Json, OFormat}

case class Visit(
                  id: String,
                  sourceIp: String,
                  url: String,
                  timestamp: String
                )

object Visit {
  implicit val format: OFormat[Visit] = Json.format[Visit]
}
