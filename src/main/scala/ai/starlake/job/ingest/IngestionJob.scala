package ai.starlake.job.ingest

import ai.starlake.config.{CometColumns, DatasetArea, Settings, StorageArea}
import ai.starlake.job.metrics.{ExpectationJob, MetricsJob}
import ai.starlake.job.sink.bigquery.{
  BigQueryJobBase,
  BigQueryLoadConfig,
  BigQueryNativeJob,
  BigQuerySparkJob
}
import ai.starlake.job.sink.es.{ESLoadConfig, ESLoadJob}
import ai.starlake.job.sink.jdbc.{ConnectionLoadConfig, ConnectionLoadJob}
import ai.starlake.job.validator.{GenericRowValidator, ValidationResult}
import ai.starlake.privacy.PrivacyEngine
import ai.starlake.schema.handlers.{SchemaHandler, StorageHandler}
import ai.starlake.schema.model.Engine.SPARK
import ai.starlake.schema.model.Rejection.{ColInfo, ColResult}
import ai.starlake.schema.model.Stage.UNIT
import ai.starlake.schema.model.Trim.{BOTH, LEFT, NONE, RIGHT}
import ai.starlake.schema.model.WriteMode.APPEND
import ai.starlake.schema.model._
import ai.starlake.utils.Formatter._
import ai.starlake.utils._
import ai.starlake.utils.conversion.BigQueryUtils
import ai.starlake.utils.kafka.KafkaClient
import ai.starlake.utils.repackaged.BigQuerySchemaConverters
import com.google.cloud.bigquery.JobInfo.{CreateDisposition, WriteDisposition}
import com.google.cloud.bigquery.{
  Field,
  LegacySQLTypeName,
  Schema => BQSchema,
  StandardTableDefinition,
  Table
}
import org.apache.hadoop.fs.Path
import org.apache.spark.ml.feature.SQLTransformer
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{Metadata => _, _}

import java.sql.Timestamp
import java.time.{Instant, LocalDateTime}
import scala.annotation.nowarn
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

trait IngestionJob extends SparkJob {

  protected val treeRowValidator: GenericRowValidator = Utils
    .loadInstance[GenericRowValidator](
      mergedMetadata.validator.getOrElse(settings.comet.treeValidatorClass)
    )

  protected val flatRowValidator: GenericRowValidator = Utils
    .loadInstance[GenericRowValidator](
      mergedMetadata.validator.getOrElse(settings.comet.rowValidatorClass)
    )

  def domain: Domain

  def schema: Schema

  def storageHandler: StorageHandler

  def schemaHandler: SchemaHandler

  def types: List[Type]

  def path: List[Path]

  def options: Map[String, String]

  val now: Timestamp = java.sql.Timestamp.from(Instant.now)

  /** Merged metadata
    */
  lazy val mergedMetadata: Metadata = schema.mergedMetadata(domain.metadata)

  protected def loadDataSet(): Try[DataFrame]

  /** ingestion algorithm
    *
    * @param dataset
    */
  protected def ingest(dataset: DataFrame): (Dataset[String], Dataset[Row])

  protected def reorderTypes(orderedAttributes: List[Attribute]): (List[Type], StructType) = {
    val typeMap: Map[String, Type] = types.map(tpe => tpe.name -> tpe).toMap
    val (tpes, sparkFields) = orderedAttributes.map { attribute =>
      val tpe = typeMap(attribute.`type`)
      (tpe, tpe.sparkType(attribute.name, !attribute.required, attribute.comment))
    }.unzip
    (tpes, StructType(sparkFields))
  }

  /** @param datasetHeaders
    *   : Headers found in the dataset
    * @param schemaHeaders
    *   : Headers defined in the schema
    * @return
    *   two lists : One with thecolumns present in the schema and the dataset and another with the
    *   headers present in the dataset only
    */
  protected def intersectHeaders(
    datasetHeaders: List[String],
    schemaHeaders: List[String]
  ): (List[String], List[String]) = {
    datasetHeaders.partition(schemaHeaders.contains)
  }

  @nowarn
  protected def applyIgnore(dfIn: DataFrame): Dataset[Row] = {
    import session.implicits._
    mergedMetadata.ignore.map { ignore =>
      if (ignore.startsWith("udf:")) {
        dfIn.filter(
          !callUDF(ignore.substring("udf:".length), struct(dfIn.columns.map(dfIn(_)): _*))
        )
      } else {
        dfIn.filter(!($"value" rlike ignore))
      }
    } getOrElse dfIn
  }

  protected def saveRejected(
    errMessagesDS: Dataset[String],
    rejectedLinesDS: Dataset[String]
  ): Try[Path] = {
    logger.whenDebugEnabled {
      logger.debug(s"rejectedRDD SIZE ${errMessagesDS.count()}")
      errMessagesDS.take(100).foreach(rejected => logger.debug(rejected.replaceAll("\n", "|")))
    }
    val domainName = domain.name
    val schemaName = schema.name

    val start = Timestamp.from(Instant.now())
    val formattedDate = new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(start)

    if (settings.comet.sinkReplayToFile && !rejectedLinesDS.isEmpty) {
      val replayArea = DatasetArea.replay(domainName)
      val targetPath =
        new Path(replayArea, s"$domainName.$schemaName.$formattedDate.replay")
      rejectedLinesDS
        .repartition(1)
        .write
        .format("text")
        .save(targetPath.toString)
      storageHandler.moveSparkPartFile(
        targetPath,
        "0000" // When saving as text file, no extension is added.
      )
    }

    IngestionUtil.sinkRejected(session, errMessagesDS, domainName, schemaName, now) match {
      case Success((rejectedDF, rejectedPath)) =>
        // We sink to a file when running unit tests
        if (settings.comet.sinkToFile) {
          sinkToFile(
            rejectedDF,
            rejectedPath,
            APPEND,
            StorageArea.rejected,
            merge = false,
            settings.comet.defaultRejectedWriteFormat
          )
        } else {
          settings.comet.audit.sink match {
            case _: NoneSink | FsSink(_, _, _, _, _, _, _) =>
              sinkToFile(
                rejectedDF,
                rejectedPath,
                WriteMode.APPEND,
                StorageArea.rejected,
                merge = false,
                settings.comet.defaultRejectedWriteFormat
              )
            case _ => // do nothing
          }
        }
        val end = Timestamp.from(Instant.now())
        val log = AuditLog(
          applicationId(),
          rejectedPath.toString,
          domainName,
          schemaName,
          success = true,
          -1,
          -1,
          -1,
          start,
          end.getTime - start.getTime,
          "success",
          Step.SINK_REJECTED.toString,
          schemaHandler.getDatabase(domain, schema.finalName),
          settings.comet.tenant
        )
        AuditLog.sink(Map.empty, optionalAuditSession, log)
        Success(rejectedPath)
      case Failure(exception) =>
        logger.error("Failed to save Rejected", exception)
        val end = Timestamp.from(Instant.now())
        val log = AuditLog(
          applicationId(),
          new Path(DatasetArea.rejected(domainName), schemaName).toString,
          domainName,
          schemaName,
          success = false,
          -1,
          -1,
          -1,
          start,
          end.getTime - start.getTime,
          Utils.exceptionAsString(exception),
          Step.SINK_REJECTED.toString,
          schemaHandler.getDatabase(domain, schema.finalName),
          settings.comet.tenant
        )
        AuditLog.sink(Map.empty, optionalAuditSession, log)
        Failure(exception)
    }
  }

  private def getWriteMode(): WriteMode =
    schema.merge
      .map(_ => WriteMode.OVERWRITE)
      .getOrElse(mergedMetadata.getWrite())

  lazy val (format, extension) = mergedMetadata.sink
    .map {
      case sink: FsSink =>
        (sink.format.getOrElse(""), sink.extension.getOrElse(""))
      case _ =>
        ("", "")
    }
    .getOrElse(("", ""))

  private def csvOutput(): Boolean =
    (settings.comet.csvOutput || format == "csv") &&
    !settings.comet.grouped &&
    mergedMetadata.partition.isEmpty && path.nonEmpty

  private def csvOutputExtension(): String =
    if (settings.comet.csvOutputExt.nonEmpty)
      settings.comet.csvOutputExt
    else
      extension

  private def runExpectations(acceptedDF: DataFrame) = {
    if (settings.comet.expectations.active) {
      new ExpectationJob(
        Map.empty, // Auth Info from env var since run from spark submit only
        this.domain.finalName,
        this.schema.finalName,
        this.schema.expectations,
        UNIT,
        storageHandler,
        schemaHandler,
        Some(acceptedDF),
        SPARK,
        sql => session.sql(sql).count()
      ).run().getOrElse(throw new Exception("Should never happen"))
    }
  }

  private def runMetrics(acceptedDF: DataFrame) = {
    if (settings.comet.metrics.active) {
      new MetricsJob(
        this.domain,
        this.schema,
        Stage.UNIT,
        this.storageHandler,
        this.schemaHandler
      )
        .run(acceptedDF, System.currentTimeMillis())
    }
  }

  private def dfWithAttributesRenamed(acceptedDF: DataFrame): DataFrame = {
    val renamedAttributes = schema.renamedAttributes().toMap
    logger.whenInfoEnabled {
      renamedAttributes.foreach { case (name, rename) =>
        logger.info(s"renaming column $name to $rename")
      }
    }
    val finalDF =
      renamedAttributes.foldLeft(acceptedDF) { case (acc, (name, rename)) =>
        acc.withColumnRenamed(existingName = name, newName = rename)
      }
    finalDF
  }

  /** Merge new and existing dataset if required Save using overwrite / Append mode
    *
    * @param validationResult
    */
  protected def saveAccepted(
    validationResult: ValidationResult
  ): (DataFrame, Path) = {
    if (!settings.comet.rejectAllOnError || validationResult.rejected.isEmpty) {
      val start = Timestamp.from(Instant.now())
      logger.whenDebugEnabled {
        logger.debug(s"acceptedRDD SIZE ${validationResult.accepted.count()}")
        logger.debug(validationResult.accepted.showString(1000))
      }

      val acceptedPath =
        new Path(DatasetArea.accepted(domain.finalName), schema.finalName)
      val acceptedRenamedFields = dfWithAttributesRenamed(validationResult.accepted)

      val acceptedDfWithScriptFields: DataFrame = computeScriptedAttributes(
        acceptedRenamedFields
      )

      val acceptedDfWithScriptAndTransformedFields: DataFrame = computeTransformedAttributes(
        acceptedDfWithScriptFields
      )
      val acceptedDfFiltered = filterData(acceptedDfWithScriptAndTransformedFields)
      val acceptedDfWithoutIgnoredFields: DataFrame = removeIgnoredAttributes(
        acceptedDfFiltered
      )
      val acceptedDF = acceptedDfWithoutIgnoredFields.drop(CometColumns.cometInputFileNameColumn)
      val finalAcceptedDF: DataFrame =
        computeFinalSchema(acceptedDF).persist(settings.comet.cacheStorageLevel)
      runExpectations(finalAcceptedDF)
      runMetrics(finalAcceptedDF)
      val (mergedDF, partitionsToUpdate) = applyMerge(acceptedPath, finalAcceptedDF)

      val finalMergedDf: DataFrame = runPostSQL(mergedDF)

      logger.whenInfoEnabled {
        logger.info("Final Dataframe Schema")
        logger.info(finalMergedDf.schemaString())
      }

      // When sinkToFile is set, it means that we want to save to files even if we save somewhere else.
      val savedInFileDataset =
        if (settings.comet.sinkToFile)
          sinkToFile(
            finalMergedDf,
            acceptedPath,
            getWriteMode(),
            StorageArea.accepted,
            schema.merge.isDefined,
            settings.comet.defaultFormat
          )
        else
          finalMergedDf

      val sinkType = mergedMetadata.getSink().map(_.getType())
      val savedDataset = sinkType.getOrElse(SinkType.None) match {
        case SinkType.None if !settings.comet.sinkToFile =>
          // TODO do this inside the sink function below
          sinkToFile(
            finalMergedDf,
            acceptedPath,
            getWriteMode(),
            StorageArea.accepted,
            schema.merge.isDefined,
            settings.comet.defaultFormat
          )
        case _ =>
          savedInFileDataset
      }
      logger.whenInfoEnabled {
        logger.info("Saved Dataset Schema")
        logger.info(savedDataset.schemaString())
      }

      val sinkedDF = sinkAccepted(finalMergedDf, partitionsToUpdate) match {
        case Success(sinkedDF) =>
          val end = Timestamp.from(Instant.now())
          val log = AuditLog(
            applicationId(),
            acceptedPath.toString,
            domain.name,
            schema.name,
            success = true,
            -1,
            -1,
            -1,
            start,
            end.getTime - start.getTime,
            "success",
            Step.SINK_ACCEPTED.toString,
            schemaHandler.getDatabase(domain, schema.finalName),
            settings.comet.tenant
          )
          AuditLog.sink(Map.empty, optionalAuditSession, log)
          sinkedDF
        case Failure(exception) =>
          Utils.logException(logger, exception)
          val end = Timestamp.from(Instant.now())
          val log = AuditLog(
            applicationId(),
            acceptedPath.toString,
            domain.name,
            schema.name,
            success = false,
            -1,
            -1,
            -1,
            start,
            end.getTime - start.getTime,
            Utils.exceptionAsString(exception),
            Step.SINK_ACCEPTED.toString,
            schemaHandler.getDatabase(domain, schema.finalName),
            settings.comet.tenant
          )
          AuditLog.sink(Map.empty, optionalAuditSession, log)
          throw exception
      }
      (sinkedDF, acceptedPath)
    } else {
      (session.emptyDataFrame, new Path("invalid-path"))
    }
  }

  private def filterData(acceptedDfWithScriptAndTransformedFields: DataFrame): Dataset[Row] = {
    schema.filter
      .map { filterExpr =>
        logger.info(s"Applying data filter: $filterExpr")
        acceptedDfWithScriptAndTransformedFields.filter(filterExpr)
      }
      .getOrElse(acceptedDfWithScriptAndTransformedFields)
  }

  private def applyMerge(
    acceptedPath: Path,
    finalAcceptedDF: DataFrame
  ): (DataFrame, List[String]) = {
    val (mergedDF, partitionsToUpdate) =
      schema.merge.fold((finalAcceptedDF, List.empty[String])) { mergeOptions =>
        mergedMetadata.getSink() match {
          case Some(sink: BigQuerySink) => mergeFromBQ(finalAcceptedDF, mergeOptions, sink)
          case _ => mergeFromParquet(acceptedPath, finalAcceptedDF, mergeOptions)
        }
      }

    if (settings.comet.mergeForceDistinct) (mergedDF.distinct(), partitionsToUpdate)
    else (mergedDF, partitionsToUpdate)
  }

  private def computeFinalSchema(acceptedDfWithoutIgnoredFields: DataFrame) = {
    val finalAcceptedDF: DataFrame = if (schema.attributes.exists(_.script.isDefined)) {
      logger.whenDebugEnabled {
        logger.debug("Accepted Dataframe schema right after adding computed columns")
        logger.debug(acceptedDfWithoutIgnoredFields.schemaString())
      }
      // adding computed columns can change the order of columns, we must force the order defined in the schema
      val cols = schema.finalAttributeNames().map(col)
      val orderedWithScriptFieldsDF = acceptedDfWithoutIgnoredFields.select(cols: _*)
      logger.whenDebugEnabled {
        logger.debug("Accepted Dataframe schema after applying the defined schema")
        logger.debug(orderedWithScriptFieldsDF.schemaString())
      }
      orderedWithScriptFieldsDF
    } else {
      acceptedDfWithoutIgnoredFields
    }
    finalAcceptedDF
  }

  private def removeIgnoredAttributes(
    acceptedDfWithScriptAndTransformedFields: DataFrame
  ): DataFrame = {
    val ignoredAttributes = schema.attributes.filter(_.isIgnore()).map(_.getFinalName())
    val acceptedDfWithoutIgnoredFields =
      acceptedDfWithScriptAndTransformedFields.drop(ignoredAttributes: _*)
    acceptedDfWithoutIgnoredFields
  }

  private def computeTransformedAttributes(acceptedDfWithScriptFields: DataFrame): DataFrame = {
    val sqlAttributes = schema.attributes.filter(_.getPrivacy().sql).filter(_.transform.isDefined)
    sqlAttributes.foldLeft(acceptedDfWithScriptFields) { case (df, attr) =>
      df.withColumn(
        attr.getFinalName(),
        expr(
          attr.transform
            .getOrElse(throw new Exception("Should never happen"))
            .richFormat(schemaHandler.activeEnvVars(), options)
        )
          .cast(attr.primitiveSparkType(schemaHandler))
      )
    }
  }

  private def computeScriptedAttributes(acceptedDF: DataFrame): DataFrame = {
    schema.attributes
      .filter(_.script.isDefined)
      .map(attr => (attr.getFinalName(), attr.sparkType(schemaHandler), attr.script))
      .foldLeft(acceptedDF) { case (df, (name, sparkType, script)) =>
        df.withColumn(
          name,
          expr(script.getOrElse("").richFormat(schemaHandler.activeEnvVars(), options))
            .cast(sparkType)
        )
      }
  }

  private def sinkAccepted(
    mergedDF: DataFrame,
    partitionsToUpdate: List[String]
  ): Try[DataFrame] = {
    Try {
      val sinkType = mergedMetadata.getSink().map(_.getType())
      sinkType.getOrElse(SinkType.None) match {
        case SinkType.ES if settings.comet.elasticsearch.active =>
          esSink(mergedDF)
        case SinkType.ES if !settings.comet.elasticsearch.active =>
          logger.warn("Indexing to ES requested but elasticsearch not active in conf file")
          mergedDF
        case SinkType.BQ =>
          bqSink(mergedDF, partitionsToUpdate)
        case SinkType.KAFKA =>
          kafkaSink(mergedDF)
        case SinkType.JDBC | SinkType.SNOWFLAKE =>
          genericSink(mergedDF)

        case SinkType.FS if !settings.comet.sinkToFile =>
          val acceptedPath =
            new Path(DatasetArea.accepted(domain.finalName), schema.finalName)
          val sinkedDF = sinkToFile(
            mergedDF,
            acceptedPath,
            getWriteMode(),
            StorageArea.accepted,
            schema.merge.isDefined,
            settings.comet.defaultFormat
          )
          sinkedDF
        case SinkType.None | SinkType.FS =>
          // Done in the caller
          logger.trace("not producing an index, as requested (no sink or sink at None explicitly)")
          mergedDF
      }
    }
  }

  private def kafkaSink(mergedDF: DataFrame): DataFrame = {
    Utils.withResources(new KafkaClient(settings.comet.kafka)) { kafkaClient =>
      kafkaClient.sinkToTopic(settings.comet.kafka.topics(schema.finalName), mergedDF)
    }
    mergedDF
  }

  private def genericSink(mergedDF: DataFrame): DataFrame = {
    val (createDisposition: CreateDisposition, writeDisposition: WriteDisposition) = {

      val (cd, wd) = Utils.getDBDisposition(
        mergedMetadata.getWrite(),
        schema.merge.exists(_.key.nonEmpty)
      )
      (CreateDisposition.valueOf(cd), WriteDisposition.valueOf(wd))
    }
    val sink = mergedMetadata.getSink().map(_.asInstanceOf[JdbcSink])
    sink.foreach { sink =>
      val jdbcConfig = ConnectionLoadConfig.fromComet(
        sink.connection,
        settings.comet,
        Right(mergedDF),
        outputTable = domain.finalName + "." + schema.finalName,
        createDisposition = createDisposition,
        writeDisposition = writeDisposition,
        sinkOptions = sink.getOptions
      )

      val res = new ConnectionLoadJob(jdbcConfig).run()
      res match {
        case Success(_) => ;
        case Failure(e) =>
          throw e
      }
    }
    mergedDF
  }

  private def bqSink(mergedDF: DataFrame, partitionsToUpdate: List[String]): DataFrame = {
    val sink = mergedMetadata.getSink().map(_.asInstanceOf[BigQuerySink])
    val (createDisposition: String, writeDisposition: String) = Utils.getDBDisposition(
      mergedMetadata.getWrite(),
      schema.merge.exists(_.key.nonEmpty)
    )

    /* We load the schema from the postsql returned dataframe if any */
    val tableSchema = schema.postsql match {
      case Nil => Some(schema.bqSchema(schemaHandler))
      case _   => Some(BigQueryUtils.bqSchema(mergedDF.schema))
    }
    val config = BigQueryLoadConfig(
      None,
      None,
      source = Right(mergedDF),
      outputTableId = Some(
        BigQueryJobBase
          .extractProjectDatasetAndTable(
            schemaHandler.getDatabase(domain, schema.finalName),
            domain.finalName,
            schema.finalName
          )
      ),
      sourceFormat = settings.comet.defaultFormat,
      createDisposition = createDisposition,
      writeDisposition = writeDisposition,
      location = sink.flatMap(_.location),
      outputPartition = sink.flatMap(_.timestamp),
      outputClustering = sink.flatMap(_.clustering).getOrElse(Nil),
      days = sink.flatMap(_.days),
      requirePartitionFilter = sink.flatMap(_.requirePartitionFilter).getOrElse(false),
      rls = schema.rls,
      options = sink.map(_.getOptions).getOrElse(Map.empty),
      partitionsToUpdate = partitionsToUpdate,
      starlakeSchema = Some(schema),
      domainTags = domain.tags,
      domainDescription = domain.comment,
      outputDatabase = schemaHandler.getDatabase(domain, schema.finalName)
    )
    val res = new BigQuerySparkJob(
      config,
      tableSchema,
      schema.comment
    ).run()
    res match {
      case Success(_) => ;
      case Failure(e) =>
        throw e
    }
    mergedDF
  }

  private def esSink(mergedDF: DataFrame): DataFrame = {
    val sink = mergedMetadata.getSink().map(_.asInstanceOf[EsSink])
    val config = ESLoadConfig(
      timestamp = sink.flatMap(_.timestamp),
      id = sink.flatMap(_.id),
      format = settings.comet.defaultFormat,
      domain = domain.name,
      schema = schema.name,
      dataset = Some(Right(mergedDF)),
      options = sink.map(_.getOptions).getOrElse(Map.empty)
    )
    new ESLoadJob(config, storageHandler, schemaHandler).run()
    mergedDF
  }

  private def extractHiveTableAcl(): List[String] = {
    if (settings.comet.isHiveCompatible()) {
      schema.acl.flatMap { ace =>
        if (Utils.isRunningInDatabricks()) {
          /*
        GRANT
          privilege_type [, privilege_type ] ...
          ON (CATALOG | DATABASE <database-name> | TABLE <table-name> | VIEW <view-name> | FUNCTION <function-name> | ANONYMOUS FUNCTION | ANY FILE)
          TO principal

        privilege_type
          : SELECT | CREATE | MODIFY | READ_METADATA | CREATE_NAMED_FUNCTION | ALL PRIVILEGES
           */
          ace.grants.map { grant =>
            val principal =
              if (grant.indexOf('@') > 0 && !grant.startsWith("`")) s"`$grant`" else grant
            s"GRANT ${ace.role} ON TABLE ${domain.finalName}.${schema.finalName} TO $principal"
          }
        } else { // Hive
          ace.grants.map { grant =>
            val principal =
              if (grant.startsWith("user:"))
                s"USER ${grant.substring("user:".length)}"
              else if (grant.startsWith("group:") || grant.startsWith("role:"))
                s"ROLE ${grant.substring("group:".length)}"
            s"GRANT ${ace.role} ON TABLE ${domain.finalName}.${schema.finalName} TO $principal"
          }
        }
      }
    } else {
      Nil
    }
  }

  def applyHiveTableAcl(forceApply: Boolean = false): Try[Unit] =
    Try {
      if (forceApply || settings.comet.accessPolicies.apply)
        applySql(extractHiveTableAcl())
    }

  private def applySql(sqls: List[String]): Unit = sqls.foreach { sql =>
    logger.info(sql)
    session.sql(sql)
  }

  private def extractSnowflakeTableAcl(): List[String] =
    schema.acl.flatMap { ace =>
      /*
        https://docs.snowflake.com/en/sql-reference/sql/grant-privilege
        https://hevodata.com/learn/snowflake-grant-role-to-user/
       */
      ace.grants.map { principal =>
        s"GRANT ${ace.role} ON TABLE ${domain.finalName}.${schema.finalName} TO ROLE $principal"
      }
    }

  def applySnowflakeTableAcl(forceApply: Boolean = false): Try[Unit] =
    Try {
      if (forceApply || settings.comet.accessPolicies.apply)
        applySql(extractSnowflakeTableAcl())
    }

  /** Save typed dataset in parquet. If hive support is active, also register it as a Hive Table and
    * if analyze is active, also compute basic statistics
    *
    * @param dataset
    *   : dataset to save
    * @param targetPath
    *   : absolute path
    * @param writeMode
    *   : Append or overwrite
    * @param area
    *   : accepted or rejected area
    */
  private def sinkToFile(
    dataset: DataFrame,
    targetPath: Path,
    writeMode: WriteMode,
    area: StorageArea,
    merge: Boolean,
    writeFormat: String
  ): DataFrame = {
    val resultDataFrame = if (dataset.columns.length > 0) {
      val saveMode = writeMode.toSaveMode
      val hiveDB = StorageArea.area(domain.finalName, Some(area))
      val tableName = schema.name
      val fullTableName = s"$hiveDB.$tableName"
      if (settings.comet.isHiveCompatible()) {
        val dbComment = domain.comment.getOrElse("")
        val tableTagPairs = Utils.extractTags(domain.tags) + ("comment" -> dbComment)
        val tagsAsString = tableTagPairs.map { case (k, v) => s"'$k'='$v'" }.mkString(",")
        session.sql(s"CREATE DATABASE IF NOT EXISTS $hiveDB WITH DBPROPERTIES($tagsAsString)")
        session.sql(s"use $hiveDB")
        Try {
          if (writeMode.toSaveMode == SaveMode.Overwrite)
            session.sql(s"DROP TABLE IF EXISTS $hiveDB.$tableName")
        } match {
          case Success(_) => ;
          case Failure(e) =>
            logger.warn("Ignore error when hdfs files not found")
            Utils.logException(logger, e)
        }
      }

      val (sinkPartition, nbPartitions, clustering, options) =
        mergedMetadata.getSink().getOrElse(NoneSink()) match {
          case _: NoneSink =>
            (
              mergedMetadata.partition,
              nbFsPartitions(
                dataset,
                writeFormat,
                targetPath,
                None
              ),
              mergedMetadata.clustering,
              Map.empty[String, String]
            )
          case s: FsSink =>
            val partitionColumns = s.partition.orElse(mergedMetadata.partition)
            (
              partitionColumns,
              nbFsPartitions(
                dataset,
                writeFormat,
                targetPath,
                partitionColumns
              ),
              s.clustering,
              s.options.getOrElse(Map.empty)
            )
          case _ if area == StorageArea.accepted => // sink-to-file is true in application.conf
            (
              mergedMetadata.partition,
              nbFsPartitions(
                dataset,
                writeFormat,
                targetPath,
                None
              ),
              mergedMetadata.clustering,
              Map.empty[String, String]
            )
          case _ if area == StorageArea.rejected =>
            logger.debug("saving in rejected")
            (
              None,
              1,
              None,
              Map.empty[String, String]
            )

        }

      // No need to apply partition on rejected dF
      val partitionedDFWriter =
        if (area == StorageArea.rejected)
          partitionedDatasetWriter(dataset.repartition(nbPartitions), Nil)
        else
          partitionedDatasetWriter(
            dataset.repartition(nbPartitions),
            sinkPartition.map(_.getAttributes()).getOrElse(Nil)
          )

      val clusteredDFWriter = clustering match {
        case None          => partitionedDFWriter
        case Some(columns) => partitionedDFWriter.sortBy(columns.head, columns.tail: _*)
      }

      val mergePath = s"${targetPath.toString}.merge"
      val (targetDatasetWriter, finalDataset) = if (merge && area != StorageArea.rejected) {
        logger.info(s"Saving Dataset to merge location $mergePath")
        clusteredDFWriter
          .mode(SaveMode.Overwrite)
          .format(writeFormat)
          .options(options)
          .option("path", mergePath)
          .save()
        logger.info(s"reading Dataset from merge location $mergePath")
        val mergedDataset = session.read.format(settings.comet.defaultFormat).load(mergePath)
        (
          partitionedDatasetWriter(
            mergedDataset,
            sinkPartition.map(_.getAttributes()).getOrElse(Nil)
          ),
          mergedDataset
        )
      } else
        (clusteredDFWriter, dataset)

      // We do not output empty datasets
      if (!finalDataset.isEmpty) {
        val finalTargetDatasetWriter =
          if (csvOutput() && area != StorageArea.rejected) {
            targetDatasetWriter
              .mode(saveMode)
              .format("csv")
              .option("ignoreLeadingWhiteSpace", value = false)
              .option("ignoreTrailingWhiteSpace", value = false)
              .option("header", mergedMetadata.withHeader.getOrElse(false))
              .option("delimiter", mergedMetadata.separator.getOrElse("µ"))
              .option("path", targetPath.toString)
          } else
            targetDatasetWriter
              .mode(saveMode)
              .format(writeFormat)
              .option("path", targetPath.toString)

        logger.info(s"Saving Dataset to final location $targetPath in $saveMode mode")

        if (settings.comet.isHiveCompatible()) {
          finalTargetDatasetWriter.saveAsTable(fullTableName)
          val tableComment = schema.comment.getOrElse("")
          val tableTagPairs = Utils.extractTags(schema.tags) + ("comment" -> tableComment)
          val tagsAsString = tableTagPairs.map { case (k, v) => s"'$k'='$v'" }.mkString(",")
          session.sql(
            s"ALTER TABLE $fullTableName SET TBLPROPERTIES($tagsAsString)"
          )
          analyze(fullTableName)
        } else {
          finalTargetDatasetWriter.save()
        }
        if (merge && area != StorageArea.rejected) {
          // Here we read the df from the targetPath and not the merged one since that on is gonna be removed
          // However, we keep the merged DF schema so we don't lose any metadata from reloading the final parquet (especially the nullables)
          val df = session.createDataFrame(
            session.read.format(settings.comet.defaultFormat).load(targetPath.toString).rdd,
            dataset.schema
          )
          storageHandler.delete(new Path(mergePath))
          logger.info(s"deleted merge file $mergePath")
          df
        } else
          finalDataset
      } else {
        finalDataset
      }
    } else {
      logger.warn("Empty dataset with no columns won't be saved")
      session.emptyDataFrame
    }
    if (csvOutput() && area != StorageArea.rejected) {
      val outputList = storageHandler
        .list(targetPath, ".csv", LocalDateTime.MIN, recursive = false)
        .filterNot(path => schema.pattern.matcher(path.getName).matches())
      if (outputList.nonEmpty) {
        val csvPath = outputList.head
        val finalCsvPath =
          if (csvOutputExtension().nonEmpty) {
            // Explicitily set extension
            val targetName = path.head.getName
            val index = targetName.lastIndexOf('.')
            val finalName = (if (index > 0) targetName.substring(0, index)
                             else targetName) + csvOutputExtension()
            new Path(targetPath, finalName)
          } else
            new Path(
              targetPath,
              path.head.getName
            )
        storageHandler.move(csvPath, finalCsvPath)
      }
    }
    // output file should have the same name as input file when applying privacy
    if (
      settings.comet.defaultFormat == "text" && settings.comet.privacyOnly && area != StorageArea.rejected
    ) {
      val pathsOutput = storageHandler
        .list(targetPath, ".txt", LocalDateTime.MIN, recursive = false)
        .filterNot(path => schema.pattern.matcher(path.getName).matches())
      if (pathsOutput.nonEmpty) {
        val txtPath = pathsOutput.head
        val finalTxtPath = new Path(
          targetPath,
          path.head.getName
        )
        storageHandler.move(txtPath, finalTxtPath)
      }
    }
    applyHiveTableAcl()
    resultDataFrame
  }

  private def nbFsPartitions(
    dataset: DataFrame,
    writeFormat: String,
    targetPath: Path,
    sinkPartition: Option[Partition]
  ): Int = {
    val tmpPath = new Path(s"${targetPath.toString}.tmp")
    val nbPartitions =
      sinkPartition.map(_.getSampling()).getOrElse(mergedMetadata.getSamplingStrategy()) match {
        case 0.0 => // default partitioning
          if (csvOutput() || dataset.rdd.getNumPartitions == 0) // avoid error for an empty dataset
            1
          else
            dataset.rdd.getNumPartitions
        case fraction if fraction > 0.0 && fraction < 1.0 =>
          // Use sample to determine partitioning
          val count = dataset.count()
          val minFraction =
            if (fraction * count >= 1) // Make sure we get at least on item in the dataset
              fraction
            else if (
              count > 0
            ) // We make sure we get at least 1 item which is 2 because of double imprecision for huge numbers.
              2 / count
            else
              0

          val sampledDataset = dataset.sample(withReplacement = false, minFraction)
          partitionedDatasetWriter(
            sampledDataset,
            sinkPartition.map(_.getAttributes()).getOrElse(mergedMetadata.getPartitionAttributes())
          )
            .mode(SaveMode.ErrorIfExists)
            .format(writeFormat)
            .option("path", tmpPath.toString)
            .save()
          val consumed = storageHandler.spaceConsumed(tmpPath) / fraction
          val blocksize = storageHandler.blockSize(tmpPath)
          storageHandler.delete(tmpPath)
          Math.max(consumed / blocksize, 1).toInt
        case count if count >= 1.0 =>
          count.toInt
      }
    nbPartitions
  }

  private def runPreSql(): Unit = {
    val bqConfig = BigQueryLoadConfig(
      None,
      None,
      outputDatabase = schemaHandler.getDatabase(domain, schema.finalName)
    )
    def bqNativeJob(sql: String) = new BigQueryNativeJob(bqConfig, sql, None)
    schema.presql.foreach { sql =>
      val compiledSql = sql.richFormat(schemaHandler.activeEnvVars(), options)
      mergedMetadata.getSink().getOrElse(NoneSink()).getType() match {
        case SinkType.BQ =>
          bqNativeJob(compiledSql).runInteractiveQuery()
        case _ =>
          session.sql(compiledSql)
      }
    }
  }

  private def runPostSQL(mergedDF: DataFrame): DataFrame = {
    schema.postsql.foldLeft(mergedDF) { (df, query) =>
      df.createOrReplaceTempView("SL_THIS")
      df.sparkSession.sql(query.richFormat(schemaHandler.activeEnvVars(), options))
    }
  }

  private def selectEngine(): Engine = {
    val sinkToBQ = mergedMetadata.getSink() match {
      case None =>
        false
      case Some(sink) =>
        sink.getType() == SinkType.BQ
    }
    val csvOrJsonLines =
      !mergedMetadata.isArray() && Set(Format.DSV, Format.JSON).contains(mergedMetadata.getFormat())

    val defaultValidator = mergedMetadata.getFormat() match {
      case Format.DSV | Format.POSITION | Format.GENERIC | Format.SIMPLE_JSON =>
        settings.comet.rowValidatorClass
      case Format.JSON | Format.PARQUET | Format.XML | Format.KAFKA | Format.KAFKASTREAM =>
        settings.comet.treeValidatorClass
      case _ => ""
    }

    val nativeValidator =
      mergedMetadata.validator.getOrElse(defaultValidator).contains("NativeValidator")
    if (csvOrJsonLines && sinkToBQ && nativeValidator) {
      logger.info("Using BQ as ingestion engine")
      Engine.BQ
    } else {
      logger.info("Using Spark as ingestion engine")
      Engine.SPARK
    }
  }

  def run(): Try[JobResult] = {
    selectEngine() match {
      case Engine.BQ =>
        runBQ()
      case Engine.SPARK =>
        runSpark()
      case _ =>
        throw new Exception("should never happen")
    }
  }

  def runBQ(): Try[JobResult] = {
    val tableSchema = schema.bqSchemaWithoutIgnoreAndScript(schemaHandler)

    val (createDisposition: String, writeDisposition: String) = Utils.getDBDisposition(
      mergedMetadata.getWrite(),
      schema.merge.exists(_.key.nonEmpty)
    )
    val config = BigQueryLoadConfig(
      None,
      None,
      source = Left(path.map(_.toString).mkString(",")),
      outputTableId = Some(
        BigQueryJobBase
          .extractProjectDatasetAndTable(
            schemaHandler.getDatabase(domain, schema.finalName),
            domain.finalName,
            schema.finalName
          )
      ),
      sourceFormat = settings.comet.defaultFormat,
      createDisposition = createDisposition,
      writeDisposition = writeDisposition,
      location = mergedMetadata.getSink().flatMap(_.asInstanceOf[BigQuerySink].location),
      outputPartition = mergedMetadata.getSink().flatMap(_.asInstanceOf[BigQuerySink].timestamp),
      outputClustering =
        mergedMetadata.getSink().flatMap(_.asInstanceOf[BigQuerySink].clustering).getOrElse(Nil),
      days = mergedMetadata.getSink().flatMap(_.asInstanceOf[BigQuerySink].days),
      requirePartitionFilter = mergedMetadata
        .getSink()
        .flatMap(_.asInstanceOf[BigQuerySink].requirePartitionFilter)
        .getOrElse(false),
      rls = schema.rls,
      options =
        mergedMetadata.getSink().map(_.asInstanceOf[BigQuerySink].getOptions).getOrElse(Map.empty),
      partitionsToUpdate = Nil,
      starlakeSchema = Some(schema.copy(metadata = Some(mergedMetadata))),
      domainTags = domain.tags,
      domainDescription = domain.comment,
      outputDatabase = schemaHandler.getDatabase(domain, schema.finalName)
    )
    val res = new BigQueryNativeJob(config, "", None).loadPathsToBQ(tableSchema)
    res
  }

  /** Main entry point as required by the Spark Job interface
    *
    * @return
    *   : Spark Session used for the job
    */
  def runSpark(): Try[JobResult] = {
    session.sparkContext.setLocalProperty(
      "spark.scheduler.pool",
      settings.comet.scheduling.poolName
    )

    val jobResult = domain.checkValidity(schemaHandler, directorySeverity = Disabled) match {
      case Left((errors, _)) =>
        val errs = errors.reduce { (errs, err) =>
          errs + "\n" + err
        }
        Failure(throw new Exception(s"-- Domain $name --\n" + errs))
      case Right(_) =>
        val start = Timestamp.from(Instant.now())
        runPreSql()
        val dataset = loadDataSet()
        val inputFiles = path.map(_.toString).mkString(",")

        def logFailureInAudit[T](exception: Throwable): Try[T] = {
          val end = Timestamp.from(Instant.now())
          val err = Utils.exceptionAsString(exception)
          Try {
            val log = AuditLog(
              applicationId(),
              inputFiles,
              domain.name,
              schema.name,
              success = false,
              0,
              0,
              0,
              start,
              end.getTime - start.getTime,
              err,
              Step.LOAD.toString,
              schemaHandler.getDatabase(domain, schema.finalName),
              settings.comet.tenant
            )
            AuditLog.sink(Map.empty, optionalAuditSession, log)
          }
          logger.error(err)
          Failure(exception)
        }

        dataset match {
          case Success(dataset) =>
            Try {
              val (rejectedDS, acceptedDS) = ingest(dataset)
              val inputCount = dataset.count()
              val acceptedCount = acceptedDS.count()
              val rejectedCount = rejectedDS.count()
              logger.info(
                s"ingestion-summary -> files: [$inputFiles], domain: ${domain.name}, schema: ${schema.name}, input: $inputCount, accepted: $acceptedCount, rejected:$rejectedCount"
              )
              val end = Timestamp.from(Instant.now())
              val success = !settings.comet.rejectAllOnError || rejectedCount == 0
              val log = AuditLog(
                applicationId(),
                inputFiles,
                domain.name,
                schema.name,
                success = success,
                inputCount,
                acceptedCount,
                rejectedCount,
                start,
                end.getTime - start.getTime,
                if (success) "success" else s"$rejectedCount invalid records",
                Step.LOAD.toString,
                schemaHandler.getDatabase(domain, schema.finalName),
                settings.comet.tenant
              )
              AuditLog.sink(Map.empty, optionalAuditSession, log)
              if (success) SparkJobResult(None)
              else throw new Exception("Fail on rejected count requested")
            }.recoverWith { case exception =>
              logFailureInAudit(exception)
            }
          case Failure(exception) =>
            logFailureInAudit(exception)
        }
    }
    // After each ingestionjob we explicitely clear the spark cache
    session.catalog.clearCache()
    jobResult
  }

  // /////////////////////////////////////////////////////////////////////////
  // region Merge between the target and the source Dataframe
  // /////////////////////////////////////////////////////////////////////////

  private def mergeFromParquet(
    acceptedPath: Path,
    withScriptFieldsDF: DataFrame,
    mergeOptions: MergeOptions
  ): (DataFrame, List[String]) = {
    val incomingSchema = schema.finalSparkSchema(schemaHandler)
    val existingDF =
      if (storageHandler.exists(new Path(acceptedPath, "_SUCCESS"))) {
        // Load from accepted area
        // We provide the accepted DF schema since partition columns types are inferred when parquet is loaded and might not match with the DF being ingested
        session.read
          .schema(
            MergeUtils.computeCompatibleSchema(
              session.read.format(settings.comet.defaultFormat).load(acceptedPath.toString).schema,
              incomingSchema
            )
          )
          .format(settings.comet.defaultFormat)
          .load(acceptedPath.toString)
      } else
        session.createDataFrame(session.sparkContext.emptyRDD[Row], withScriptFieldsDF.schema)

    val partitionedInputDF =
      partitionDataset(withScriptFieldsDF, mergedMetadata.getPartitionAttributes())
    logger.whenInfoEnabled {
      logger.info(s"partitionedInputDF field count=${partitionedInputDF.schema.fields.length}")
      logger.info(
        s"partitionedInputDF field list=${partitionedInputDF.schema.fieldNames.mkString(",")}"
      )
    }
    val (finalIncomingDF, mergedDF, _) =
      MergeUtils.computeToMergeAndToDeleteDF(existingDF, partitionedInputDF, mergeOptions)
    (mergedDF, Nil)
  }

  /** In the queryFilter, the user may now write something like this : `partitionField in last(3)`
    * this will be translated to partitionField between partitionStart and partitionEnd
    *
    * partitionEnd is the last partition in the dataset paritionStart is the 3rd last partition in
    * the dataset
    *
    * if partititionStart or partitionEnd does nos exist (aka empty dataset) they are replaced by
    * 19700101
    *
    * @param incomingDF
    * @param mergeOptions
    * @return
    */
  private def mergeFromBQ(
    incomingDF: DataFrame,
    mergeOptions: MergeOptions,
    sink: BigQuerySink
  ): (DataFrame, List[String]) = {
    // When merging to BigQuery, load existing DF from BigQuery
    val tableMetadata =
      BigQuerySparkJob.getTable(domain.finalName + "." + schema.finalName)
    val existingDF = tableMetadata.table
      .map { table =>
        val incomingSchema = BigQueryUtils.normalizeSchema(schema.finalSparkSchema(schemaHandler))
        val updatedTable = updateBqTableSchema(table, incomingSchema)
        val bqTable = s"${domain.finalName}.${schema.finalName}"
        // We provided the acceptedDF schema here since BQ lose the required / nullable information of the schema
        val existingBQDFWithoutFilter = session.read
          .schema(incomingSchema)
          .format("com.google.cloud.spark.bigquery")
          .option("table", bqTable)

        val existingBigQueryDFReader = (mergeOptions.queryFilter, sink.timestamp) match {
          case (Some(_), Some(_)) =>
            val partitions =
              tableMetadata.biqueryClient.listPartitions(updatedTable.getTableId).asScala.toList
            val filter =
              mergeOptions.buidlBQQuery(partitions, schemaHandler.activeEnvVars(), options)
            existingBQDFWithoutFilter
              .option("filter", filter.getOrElse(throw new Exception("should never happen")))
          case (_, _) =>
            existingBQDFWithoutFilter
        }
        existingBigQueryDFReader.load()
      }
      .getOrElse {
        session.createDataFrame(session.sparkContext.emptyRDD[Row], incomingDF.schema)
      }

    val (finalIncomingDF, mergedDF, toDeleteDF) =
      MergeUtils.computeToMergeAndToDeleteDF(existingDF, incomingDF, mergeOptions)

    val partitionOverwriteMode =
      session.conf.get("spark.sql.sources.partitionOverwriteMode", "static").toLowerCase()
    val partitionsToUpdate = (
      partitionOverwriteMode,
      sink.timestamp,
      settings.comet.mergeOptimizePartitionWrite
    ) match {
      // no need to apply optimization if existing dataset is empty
      case ("dynamic", Some(timestamp), true) if !existingDF.isEmpty =>
        logger.info(s"Computing partitions to update on date column $timestamp")
        val partitionsToUpdate =
          BigQueryUtils.computePartitionsToUpdateAfterMerge(finalIncomingDF, toDeleteDF, timestamp)
        logger.info(
          s"The following partitions will be updated ${partitionsToUpdate.mkString(",")}"
        )
        partitionsToUpdate
      case ("static", _, _) | ("dynamic", _, _) =>
        Nil
      case (_, _, _) =>
        throw new Exception("Should never happen")
    }

    (mergedDF, partitionsToUpdate)
  }

  // /////////////////////////////////////////////////////////////////////////
  // endregion
  // /////////////////////////////////////////////////////////////////////////

  def reorderAttributes(dataFrame: DataFrame): List[Attribute] = {
    val finalSchema = schema.attributesWithoutScriptedFields :+ Attribute(
      name = CometColumns.cometInputFileNameColumn
    )
    val attributesMap =
      finalSchema.map(attr => (attr.name, attr)).toMap
    dataFrame.columns.map(colName => attributesMap(colName)).toList
  }

  private def updateBqTableSchema(table: Table, incomingSchema: StructType): Table = {
    // This will raise an exception if schemas are not compatible.
    val existingSchema = BigQuerySchemaConverters.toSpark(
      table.getDefinition[StandardTableDefinition].getSchema
    )

    MergeUtils.computeCompatibleSchema(existingSchema, incomingSchema)
    val newBqSchema =
      BigQueryUtils.bqSchema(
        BigQueryUtils.normalizeSchema(schema.finalSparkSchema(schemaHandler))
      )
    val updatedTableDefinition =
      table.getDefinition[StandardTableDefinition].toBuilder.setSchema(newBqSchema).build()
    val updatedTable =
      table.toBuilder.setDefinition(updatedTableDefinition).build()
    updatedTable.update()
  }
}

object IngestionUtil {

  private val rejectedCols = List(
    ("jobid", LegacySQLTypeName.STRING, StringType),
    ("timestamp", LegacySQLTypeName.TIMESTAMP, TimestampType),
    ("domain", LegacySQLTypeName.STRING, StringType),
    ("schema", LegacySQLTypeName.STRING, StringType),
    ("error", LegacySQLTypeName.STRING, StringType),
    ("path", LegacySQLTypeName.STRING, StringType)
  )

  private def bigqueryRejectedSchema(): BQSchema = {
    val fields = rejectedCols map { case (attrName, attrLegacyType, attrStandardType) =>
      Field
        .newBuilder(attrName, attrLegacyType)
        .setMode(Field.Mode.NULLABLE)
        .setDescription("")
        .build()
    }
    BQSchema.of(fields: _*)
  }

  def sinkRejected(
    session: SparkSession,
    rejectedDS: Dataset[String],
    domainName: String,
    schemaName: String,
    now: Timestamp
  )(implicit settings: Settings): Try[(Dataset[Row], Path)] = {
    import session.implicits._
    val rejectedPath = new Path(DatasetArea.rejected(domainName), schemaName)
    val rejectedPathName = rejectedPath.toString
    // We need to save first the application ID
    // referencing it inside the worker (rdd.map) below would fail.
    val applicationId = session.sparkContext.applicationId
    val rejectedTypedDS = rejectedDS.map { err =>
      RejectedRecord(
        applicationId,
        now,
        domainName,
        schemaName,
        err,
        rejectedPathName
      )
    }
    val rejectedDF = rejectedTypedDS
      .limit(settings.comet.audit.maxErrors)
      .toDF(rejectedCols.map { case (attrName, _, _) => attrName }: _*)

    val res =
      settings.comet.audit.sink match {
        case sink: BigQuerySink =>
          val bqConfig = BigQueryLoadConfig(
            None,
            None,
            Right(rejectedDF),
            outputTableId = Some(
              BigQueryJobBase
                .extractProjectDatasetAndTable(
                  settings.comet.audit.database,
                  sink.name.getOrElse("audit"),
                  "rejected"
                )
            ),
            None,
            Nil,
            settings.comet.defaultFormat,
            "CREATE_IF_NEEDED",
            "WRITE_APPEND",
            None,
            None,
            options = sink.getOptions,
            outputDatabase = settings.comet.audit.database
          )
          new BigQuerySparkJob(
            bqConfig,
            Some(bigqueryRejectedSchema()),
            Some(
              "Contains all rejections occurred during ingestion phase in order to give more insight on how to fix data ingestion"
            )
          ).run()

        case sink: JdbcSink =>
          val jdbcConfig = ConnectionLoadConfig.fromComet(
            sink.connection,
            settings.comet,
            Right(rejectedDF),
            settings.comet.audit.sink.name.getOrElse("audit") + "." + "rejected",
            sinkOptions = sink.getOptions
          )

          new ConnectionLoadJob(jdbcConfig).run()

        case _: EsSink =>
          // TODO Sink Rejected Log to ES
          throw new Exception("Sinking Audit log to Elasticsearch not yet supported")
        case _: NoneSink | FsSink(_, _, _, _, _, _, _) =>
          // We save in the caller
          // TODO rewrite this one
          Success(())
      }
    res match {
      case Success(_) => Success(rejectedDF, rejectedPath)
      case Failure(e) => Failure(e)
    }
  }

  def validateCol(
    colRawValue: Option[String],
    colAttribute: Attribute,
    tpe: Type,
    colMap: => Map[String, Option[String]],
    allPrivacyLevels: Map[String, ((PrivacyEngine, List[String]), PrivacyLevel)],
    emptyIsNull: Boolean
  ): ColResult = {
    def ltrim(s: String) = s.replaceAll("^\\s+", "")

    def rtrim(s: String) = s.replaceAll("\\s+$", "")

    val trimmedColValue = colRawValue.map { colRawValue =>
      colAttribute.trim match {
        case Some(NONE) | None => colRawValue
        case Some(LEFT)        => ltrim(colRawValue)
        case Some(RIGHT)       => rtrim(colRawValue)
        case Some(BOTH)        => colRawValue.trim()
        case _                 => colRawValue
      }
    }

    val colValue = colAttribute.default match {
      case None =>
        trimmedColValue
      case Some(default) =>
        trimmedColValue
          .map(value => if (value.isEmpty) default else value)
          .orElse(colAttribute.default)
    }

    def colValueIsNullOrEmpty = colValue match {
      case None           => true
      case Some(colValue) => colValue.isEmpty
    }

    def colValueIsNull = colValue.isEmpty

    def optionalColIsEmpty = !colAttribute.required && colValueIsNullOrEmpty

    def requiredColIsEmpty = {
      if (emptyIsNull)
        colAttribute.required && colValueIsNullOrEmpty
      else
        colAttribute.required && colValueIsNull
    }

    def colPatternIsValid = colValue.exists(tpe.matches)

    val privacyLevel = colAttribute.getPrivacy()
    val colValueWithPrivacyApplied =
      if (privacyLevel == PrivacyLevel.None || privacyLevel.sql) {
        colValue
      } else {
        val ((privacyAlgo, privacyParams), _) = allPrivacyLevels(privacyLevel.value)
        colValue.map(colValue => privacyLevel.crypt(colValue, colMap, privacyAlgo, privacyParams))

      }

    val colPatternOK = !requiredColIsEmpty && (optionalColIsEmpty || colPatternIsValid)

    val (sparkValue, colParseOK) = {
      (colPatternOK, colValueWithPrivacyApplied) match {
        case (true, Some(colValueWithPrivacyApplied)) =>
          Try(tpe.sparkValue(colValueWithPrivacyApplied)) match {
            case Success(res) => (Some(res), true)
            case Failure(_)   => (None, false)
          }
        case (colPatternResult, _) =>
          (None, colPatternResult)
      }
    }
    ColResult(
      ColInfo(
        colValue,
        colAttribute.name,
        tpe.name,
        tpe.pattern,
        colPatternOK && colParseOK
      ),
      sparkValue.orNull
    )
  }
}

object ImprovedDataFrameContext {

  implicit class ImprovedDataFrame(df: org.apache.spark.sql.DataFrame) {

    def T(query: String): org.apache.spark.sql.DataFrame = {
      new SQLTransformer().setStatement(query).transform(df)
    }
  }

}
