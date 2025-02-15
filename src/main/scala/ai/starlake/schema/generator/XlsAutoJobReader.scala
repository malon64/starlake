package ai.starlake.schema.generator

import ai.starlake.schema.model._
import org.apache.poi.ss.usermodel._

import java.io.File

class XlsAutoJobReader(input: Input, policyInput: Option[Input]) extends XlsModel {

  private val workbook: Workbook = input match {
    case InputPath(s)  => WorkbookFactory.create(new File(s))
    case InputFile(in) => WorkbookFactory.create(in)
  }

  private lazy val policies = new XlsPolicyReader(policyInput.getOrElse(input)).policies

  lazy val autoJobsDesc: List[AutoJobDesc] = {

    val sheetSchema = workbook.getSheet("schemas")
    val (rowsSchema, headerMapSchema) =
      getColsOrder(sheetSchema, allSchemaJobHeaders.map { case (name, _) => name })
    rowsSchema.map { row =>
      val jobNameOpt: Option[String] =
        Option(row.getCell(headerMapSchema("_job"), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL))
          .flatMap(formatter.formatCellValue)
      val domainOpt: Option[String] =
        Option(row.getCell(headerMapSchema("_domain"), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL))
          .flatMap(formatter.formatCellValue)
      val schemaOpt =
        Option(row.getCell(headerMapSchema("_name"), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL))
          .flatMap(formatter.formatCellValue)
      val writeOpt =
        Option(row.getCell(headerMapSchema("_write"), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL))
          .flatMap(formatter.formatCellValue)
          .map(WriteMode.fromString)
      val sinkOpt =
        Option(
          row.getCell(headerMapSchema("_partition"), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)
        )
          .flatMap(formatter.formatCellValue)
      val policiesOpt =
        Option(row.getCell(headerMapSchema("_policy"), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL))
          .flatMap(formatter.formatCellValue)
          .map(_.split(",").map(_.trim))
      val commentOpt =
        Option(
          row.getCell(headerMapSchema("_description"), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)
        )
          .flatMap(formatter.formatCellValue)

      val databaseOpt =
        Option(
          row.getCell(headerMapSchema("_database"), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)
        )
          .flatMap(formatter.formatCellValue)

      val tablePolicies = policiesOpt
        .map { tablePolicies =>
          policies.filter(p => tablePolicies.contains(p.name))
        }
        .getOrElse(Nil)

      val (withoutPredicate, withPredicate) = tablePolicies
        .partition(_.predicate.toUpperCase() == "TRUE")

      val acl = withoutPredicate.map(rls =>
        AccessControlEntry("roles/bigquery.dataViewer", rls.grants.toList, rls.name)
      )
      val rls = withPredicate

      val isPartition = sinkOpt.nonEmpty

      AutoJobDesc(
        name = jobNameOpt.getOrElse(""),
        engine = if (isPartition) None else Some(Engine.BQ),
        tasks = List(
          AutoTaskDesc(
            name = "",
            sql = None,
            database = databaseOpt,
            domain = domainOpt.getOrElse("Default"),
            table = schemaOpt.getOrElse("Table"),
            write = writeOpt.getOrElse(WriteMode.OVERWRITE),
            sink = Some(sinkOpt match {
              case Some(sink) =>
                BigQuerySink(timestamp = Some(sink), requirePartitionFilter = Some(true))
              case _ => BigQuerySink()
            }),
            rls = rls,
            engine = if (isPartition) Some(Engine.BQ) else None,
            acl = acl,
            comment = commentOpt,
            attributesDesc = {
              schemaOpt
                .flatMap(schema => Option(workbook.getSheet(schema)))
                .map(sheet => {
                  val (rowsAttributes, headerMapAttributes) =
                    getColsOrder(sheet, allAttributeJobHeaders.map { case (k, _) => k })
                  rowsAttributes.flatMap { row =>
                    val nameOpt =
                      Option(
                        row.getCell(
                          headerMapAttributes("_name"),
                          Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
                        )
                      )
                        .flatMap(formatter.formatCellValue)
                    val descriptionOpt =
                      Option(
                        row.getCell(
                          headerMapAttributes("_description"),
                          Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
                        )
                      )
                        .flatMap(formatter.formatCellValue)

                    (nameOpt, descriptionOpt) match {
                      case (Some(name), Some(description)) if description.trim.nonEmpty =>
                        Some(
                          AttributeDesc(
                            name,
                            comment = descriptionOpt.getOrElse("")
                          )
                        )
                      case _ => None
                    }
                  }.toList
                })
            }.getOrElse(Nil),
            python = None,
            merge = None
          )
        )
      )
    }.toList
  }
}
