/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one or more
 *  * contributor license agreements.  See the NOTICE file distributed with
 *  * this work for additional information regarding copyright ownership.
 *  * The ASF licenses this file to You under the Apache License, Version 2.0
 *  * (the "License"); you may not use this file except in compliance with
 *  * the License.  You may obtain a copy of the License at
 *  *
 *  *    http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 *
 */

package com.ebiznext.comet.schema.generator

import com.ebiznext.comet.config.Settings
import com.ebiznext.comet.schema.generator.DDLUtils.{Columns, PrimaryKeys, TableRemarks}
import com.ebiznext.comet.schema.handlers.SchemaHandler
import com.ebiznext.comet.schema.model.{Domain, Schema}
import org.fusesource.scalate.{TemplateEngine, TemplateSource}

import scala.util.Try

/** * Infers the schema of a given datapath, domain name, schema name.
  */
class Yml2DDLJob(config: Yml2DDLConfig, schemaHandler: SchemaHandler)(implicit
  settings: Settings
) {
  val engine: TemplateEngine = new TemplateEngine

  def name: String = "InferDDL"

  /** Just to force any spark job to implement its entry point using within the "run" method
    *
    * @return
    *   : Spark Session used for the job
    */
  def run(): Try[Unit] =
    Try {
      val domains = config.domain match {
        case None => schemaHandler.domains
        case Some(domain) =>
          val res = schemaHandler.domains
            .find(_.name.toLowerCase() == domain)
            .getOrElse(throw new Exception(s"Domain ${domain} not found"))
          List(res)
      }
      domains.map { domain =>
        val schemas = config.schemas match {
          case Some(schemas) =>
            schemas.flatMap(schema =>
              domain.schemas.find(_.name.toLowerCase() == schema.toLowerCase)
            )
          case None =>
            domain.schemas
        }
        val existingTables = config.connection match {
          case Some(connection) =>
            DDLUtils.extractJDBCTables(
              JDBCSchema(
                connection,
                config.catalog,
                domain.name,
                Nil,
                List("TABLE"),
                None
              )
            )
          case None => Map.empty[String, (TableRemarks, Columns, PrimaryKeys)]
        }
        val oldTables = existingTables.keys.filter(table =>
          schemas.map(_.name.toLowerCase()).contains(table.toLowerCase())
        )
        oldTables.foreach { table =>
          val schema = schemas
            .find(_.name.toLowerCase() == table.toLowerCase())
            .getOrElse(throw new Exception("Should never happen"))
          val ddlType = "drop"
          val dropParamMap = Map(
            "attributes"              -> List.empty[Map[String, Any]],
            "newAttributes"           -> Nil,
            "alterAttributes"         -> Nil,
            "alterCommentAttributes"  -> Nil,
            "alterDataTypeAttributes" -> Nil,
            "alterRequiredAttributes" -> Nil,
            "droppedAttributes"       -> Nil,
            "domain"                  -> domain.name,
            "schema"                  -> schema.name,
            "partitions"              -> Nil,
            "clustered"               -> Nil,
            "primaryKeys"             -> Nil,
            "comment"                 -> "",
            "domainComment"           -> ""
          )
          println(s"Dropping table $table")
          val result = applyTemplate(domain, schema, ddlType, dropParamMap)
          println(result)
        }
        schemas.map { schema =>
          val ddlFields = schema.ddlMapping(config.datawarehouse, schemaHandler)

          val mergedMetadata = schema.mergedMetadata(domain.metadata)
          val isNew = !existingTables.contains(schema.name.toUpperCase())
          isNew match {
            case false =>
              val (_, existingColumns, _) =
                existingTables(schema.name.toUpperCase())
              val addColumns =
                schema.attributes.filter(attr =>
                  !existingColumns.map(_.name.toLowerCase()).contains(attr.name.toLowerCase())
                )
              val dropColumns =
                existingColumns.filter(attr =>
                  !schema.attributes.map(_.name.toLowerCase()).contains(attr.name.toLowerCase())
                )
              val alterColumns =
                schema.attributes.filter { attr =>
                  existingColumns.exists(existingAttr =>
                    existingAttr.name.toLowerCase() == attr.name.toLowerCase() &&
                    (existingAttr.required != attr.required ||
                    existingAttr.`type` != attr.`type` ||
                    existingAttr.comment != attr.comment)
                  )
                }
              val alterDataTypeColumns =
                schema.attributes.filter { attr =>
                  existingColumns.exists(existingAttr =>
                    existingAttr.name.toLowerCase() == attr.name.toLowerCase() &&
                    existingAttr.`type` != attr.`type`
                  )
                }
              val alterDescriptionColumns =
                schema.attributes.filter { attr =>
                  existingColumns.exists(existingAttr =>
                    existingAttr.name.toLowerCase() == attr.name.toLowerCase() &&
                    existingAttr.comment != attr.comment
                  )
                }
              val alterRequiredColumns =
                schema.attributes.filter { attr =>
                  existingColumns.exists(existingAttr =>
                    existingAttr.name.toLowerCase() == attr.name.toLowerCase() &&
                    existingAttr.required != attr.required
                  )
                }
              val ddlType = "alter"
              val alterParamMap = Map(
                "attributes" -> Nil,
                "newAttributes" -> addColumns.map(
                  _.ddlMapping(false, config.datawarehouse, schemaHandler).toMap()
                ),
                "alterAttributes" -> alterColumns.map(
                  _.ddlMapping(false, config.datawarehouse, schemaHandler).toMap()
                ),
                "alterCommentAttributes" -> alterDescriptionColumns.map(
                  _.ddlMapping(false, config.datawarehouse, schemaHandler).toMap()
                ),
                "alterDataTypeAttributes" -> alterDataTypeColumns.map(
                  _.ddlMapping(false, config.datawarehouse, schemaHandler).toMap()
                ),
                "alterRequiredAttributes" -> alterRequiredColumns.map(
                  _.ddlMapping(false, config.datawarehouse, schemaHandler).toMap()
                ),
                "droppedAttributes" -> dropColumns.map(
                  _.ddlMapping(false, config.datawarehouse, schemaHandler).toMap()
                ),
                "domain"        -> domain.name,
                "schema"        -> schema.name,
                "partitions"    -> Nil,
                "clustered"     -> Nil,
                "primaryKeys"   -> Nil,
                "comment"       -> schema.comment.getOrElse(""),
                "domainComment" -> domain.comment.getOrElse("")
              )
              val result = applyTemplate(domain, schema, ddlType, alterParamMap)

            case true =>
              val createParamMap = Map(
                "attributes"              -> ddlFields.map(_.toMap()),
                "newAttributes"           -> Nil,
                "alterAttributes"         -> Nil,
                "alterCommentAttributes"  -> Nil,
                "alterDataTypeAttributes" -> Nil,
                "alterRequiredAttributes" -> Nil,
                "droppedAttributes"       -> Nil,
                "domain"                  -> domain.name,
                "schema"                  -> schema.name,
                "partitions"    -> mergedMetadata.partition.map(_.getAttributes()).getOrElse(Nil),
                "clustered"     -> mergedMetadata.clustering.getOrElse(Nil),
                "primaryKeys"   -> schema.primaryKey.getOrElse(Nil),
                "comment"       -> schema.comment.getOrElse(""),
                "domainComment" -> domain.comment.getOrElse("")
              )
              val ddlType = "create"
              println(s"Creating new table ${schema.name}")
              val result = applyTemplate(domain, schema, ddlType, createParamMap)
              println(result)
          }
        }
      }
    }

  private def applyTemplate(
    domain: Domain,
    schema: Schema,
    ddlType: TableRemarks,
    dropParamMap: Map[TableRemarks, Any]
  ): String = {
    val (templatePath, templateContent) =
      domain.ddlMapping(
        schema,
        config.datawarehouse,
        ddlType
      )
    engine.layout(
      TemplateSource.fromText(templatePath.toString, templateContent),
      dropParamMap
    )
  }
}