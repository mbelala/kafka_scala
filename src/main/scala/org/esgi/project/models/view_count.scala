package org.esgi.project.models

import play.api.libs.json.{Json, OFormat}


case class view_count(
                 _id: Int,
                 title: String,
                 view_count: Int
               )


object view_count {
  implicit val format: OFormat[view_count] = Json.format[view_count]
}
