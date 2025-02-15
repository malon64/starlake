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

package ai.starlake.workflow

import ai.starlake.config.{DatasetArea, Settings}
import ai.starlake.job.atlas.{AtlasConfig, AtlasJob}
import ai.starlake.job.infer.{InferSchema, InferSchemaConfig}
import ai.starlake.job.ingest._
import ai.starlake.job.load.LoadStrategy
import ai.starlake.job.metrics.{MetricsConfig, MetricsJob}
import ai.starlake.job.sink.bigquery.{
  BigQueryJobBase,
  BigQueryJobResult,
  BigQueryLoadConfig,
  BigQuerySparkJob
}
import ai.starlake.job.sink.es.{ESLoadConfig, ESLoadJob}
import ai.starlake.job.sink.jdbc.{ConnectionLoadConfig, ConnectionLoadJob}
import ai.starlake.job.sink.kafka.{KafkaJob, KafkaJobConfig}
import ai.starlake.job.transform.{AutoTask, TransformConfig}
import ai.starlake.schema.generator.{Yml2DDLConfig, Yml2DDLJob}
import ai.starlake.schema.handlers.{
  LaunchHandler,
  LocalStorageHandler,
  SchemaHandler,
  StorageHandler
}
import ai.starlake.schema.model.Engine.BQ
import ai.starlake.schema.model.Mode.{FILE, STREAM}
import ai.starlake.schema.model._
import ai.starlake.utils._
import better.files.File
import com.google.cloud.bigquery.JobInfo.{CreateDisposition, WriteDisposition}
import com.google.cloud.bigquery.{Schema => BQSchema}
import com.typesafe.scalalogging.StrictLogging
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.catalyst.SQLConfHelper
import org.apache.spark.sql.execution.datasources.jdbc.JdbcUtils
import org.apache.spark.sql.jdbc.{JdbcDialect, JdbcType}
import org.apache.spark.sql.types.{BooleanType, DataType, TimestampType}

import java.nio.file.{FileSystems, ProviderNotFoundException}
import java.util.Collections
import java.util.concurrent.ForkJoinPool
import scala.annotation.nowarn
import scala.collection.GenSeq
import scala.collection.parallel.ForkJoinTaskSupport
import scala.util.{Failure, Success, Try}

private case object SnowflakeDialect extends JdbcDialect with SQLConfHelper {
  override def canHandle(url: String): Boolean = url.toLowerCase.startsWith("jdbc:snowflake:")
  // override def quoteIdentifier(column: String): String = column
  override def getJDBCType(dt: DataType): Option[JdbcType] = dt match {
    case BooleanType => Some(JdbcType("BOOLEAN", java.sql.Types.BOOLEAN))
    case TimestampType =>
      Some(JdbcType(sys.env.get("SF_TIMEZONE").getOrElse("TIMESTAMP"), java.sql.Types.BOOLEAN))
    case _ => JdbcUtils.getCommonJDBCType(dt)
  }
}

/** The whole worklfow works as follow :
  *   - loadLanding : Zipped files are uncompressed or raw files extracted from the local
  *     filesystem.
  * -loadPending : files recognized with filename patterns are stored in the ingesting area and
  * submitted for ingestion files with unrecognized filename patterns are stored in the unresolved
  * area
  *   - ingest : files are finally ingested and saved as parquet/orc/... files and hive tables
  *
  * @param storageHandler
  *   : Minimum set of features required for the underlying filesystem
  * @param schemaHandler
  *   : Schema interface
  * @param launchHandler
  *   : Cron Manager interface
  */
class IngestionWorkflow(
  storageHandler: StorageHandler,
  schemaHandler: SchemaHandler,
  launchHandler: LaunchHandler
)(implicit settings: Settings)
    extends StrictLogging {

  import org.apache.spark.sql.jdbc.JdbcDialects

  JdbcDialects.registerDialect(SnowflakeDialect)

  var domains: List[Domain] = schemaHandler.domains()

  /** Move the files from the landing area to the pending area. files are loaded one domain at a
    * time each domain has its own directory and is specified in the "directory" key of Domain YML
    * file compressed files are uncompressed if a corresponding ack file exist. Compressed files are
    * recognized by their extension which should be one of .tgz, .zip, .gz. raw file should also
    * have a corresponding ack file before moving the files to the pending area, the ack files are
    * deleted To import files without ack specify an empty "ack" key (aka ack:"") in the domain YML
    * file. "ack" is the default ack extension searched for but you may specify a different one in
    * the domain YML file.
    */
  def loadLanding(config: ImportConfig): Unit = {
    val filteredDomains = config.includes match {
      case Nil => domains
      case _   => domains.filter { d => config.includes.contains(d.name) }
    }
    logger.info(
      s"Loading files from Landing Zone for domains : ${filteredDomains.map(_.name).mkString(",")}"
    )
    filteredDomains.foreach { domain =>
      val storageHandler = settings.storageHandler
      val inputDir = new Path(domain.resolveDirectory())
      if (storageHandler.exists(inputDir)) {
        logger.info(s"Scanning $inputDir")
        storageHandler
          .list(inputDir, domain.getAck(), recursive = false)
          .filterNot(_.getName().startsWith(".")) // ignore files starting with '.' aka .DS_Store
          .foreach { path =>
            val (filesToLoad, tmpDir) = {
              val fileName = path.getName
              val pathWithoutLastExt = new Path(
                path.getParent,
                (domain.getAck(), fileName.lastIndexOf('.')) match {
                  case ("", -1) => fileName
                  case ("", i)  => fileName.substring(0, i)
                  case (ack, _) => fileName.stripSuffix(ack)
                }
              )

              val zipExtensions = List(".gz", ".tgz", ".zip")
              val (existingRawFile, existingArchiveFile) = {
                val findPathWithExt = if (domain.getAck().isEmpty) {
                  // the file is always the one being iterated over, we just need to check the extension
                  (extensions: List[String]) => extensions.find(fileName.endsWith).map(_ => path)
                } else {
                  // for each extension, look if there exists a file near the .ack one
                  (extensions: List[String]) =>
                    extensions.map(pathWithoutLastExt.suffix).find(storageHandler.exists)
                }
                (
                  findPathWithExt(
                    domain.getExtensions(
                      settings.comet.defaultFileExtensions,
                      settings.comet.forceFileExtensions
                    )
                  ),
                  findPathWithExt(zipExtensions)
                )
              }

              if (domain.getAck().nonEmpty)
                storageHandler.delete(path)

              (existingArchiveFile, existingRawFile, storageHandler.getScheme()) match {
                case (Some(zipPath), _, "file") =>
                  logger.info(s"Found compressed file $zipPath")
                  storageHandler.mkdirs(pathWithoutLastExt)

                  // We use Java nio to handle archive files

                  // Java nio FileSystems are only registered based on the system the JVM is based on
                  // To handle external FileSystems (ex: GCS from a linux OS), we need to manually install them
                  // As long as the user provide this app classpath with the appropriate FileSystem implementations,
                  // we can virtually handle anything
                  def asBetterFile(path: Path): File = {
                    Try {
                      LocalStorageHandler.localFile(path) // try once
                    } match {
                      case Success(file) => file
                      // There is no FileSystem registered that can handle this URI
                      case Failure(_: ProviderNotFoundException) =>
                        FileSystems
                          .newFileSystem(
                            path.toUri,
                            Collections.emptyMap[String, Any](),
                            getClass.getClassLoader
                          )
                        // Try to install one from the classpath
                        File(path.toUri) // retry
                      case Failure(exception) => throw exception
                    }
                  }

                  // File is a proxy to java.nio.Path / working with Uri
                  val tmpDir = asBetterFile(pathWithoutLastExt)
                  val zipFile = asBetterFile(zipPath)
                  zipFile.extension() match {
                    case Some(".tgz") => Unpacker.unpack(zipFile, tmpDir)
                    case Some(".gz")  => zipFile.unGzipTo(tmpDir / pathWithoutLastExt.getName)
                    case Some(".zip") => zipFile.unzipTo(tmpDir)
                    case _            => logger.error(s"Unsupported archive type for $zipFile")
                  }
                  storageHandler.delete(zipPath)

                  (
                    storageHandler
                      .list(pathWithoutLastExt, recursive = false)
                      .filter(path =>
                        domain
                          .getExtensions(
                            settings.comet.defaultFileExtensions,
                            settings.comet.forceFileExtensions
                          )
                          .exists(path.getName.endsWith)
                      ),
                    Some(pathWithoutLastExt)
                  )
                case (_, Some(filePath), _) =>
                  logger.info(s"Found raw file $filePath")
                  (List(filePath), None)
                case (_, _, _) =>
                  logger.info(s"Ignoring file for path $path")
                  (List.empty, None)
              }
            }

            val destFolder = DatasetArea.pending(domain.name)
            filesToLoad.foreach { file =>
              logger.info(s"Importing $file")
              val destFile = new Path(destFolder, file.getName)
              storageHandler.moveFromLocal(file, destFile)
            }

            tmpDir.foreach(storageHandler.delete)
          }
      } else {
        logger.error(s"Input path : $inputDir not found, ${domain.name} Domain is ignored")
      }
    }
  }

  /** Split files into resolved and unresolved datasets. A file is unresolved if a corresponding
    * schema is not found. Schema matching is based on the dataset filename pattern
    *
    * @param config
    *   : includes Load pending dataset of these domain only excludes : Do not load datasets of
    *   these domains if both lists are empty, all domains are included
    */
  @nowarn
  def loadPending(config: WatchConfig = WatchConfig()): Boolean = {
    val includedDomains = domainsToWatch(config)

    val result: List[Boolean] = includedDomains.flatMap { domain =>
      logger.info(s"Watch Domain: ${domain.name}")
      val (resolved, unresolved) = pending(domain.name, config.schemas.toList)
      unresolved.foreach { case (_, path) =>
        val targetPath =
          new Path(DatasetArea.unresolved(domain.name), path.getName)
        logger.info(s"Unresolved file : ${path.getName}")
        storageHandler.move(path, targetPath)
      }

      val filteredResolved = if (settings.comet.privacyOnly) {
        val (withPrivacy, noPrivacy) =
          resolved.partition { case (schema, _) =>
            schema.exists(_.attributes.map(_.getPrivacy()).exists(!PrivacyLevel.None.equals(_)))
          }
        // files for schemas without any privacy attributes are moved directly to accepted area
        noPrivacy.foreach {
          case (Some(schema), path) =>
            storageHandler.move(
              path,
              new Path(new Path(DatasetArea.accepted(domain.name), schema.name), path.getName)
            )
          case (None, _) => throw new Exception("Should never happen")
        }
        withPrivacy
      } else {
        resolved
      }

      // We group files with the same schema to ingest them together in a single step.
      val groupedResolved: Map[Schema, Iterable[Path]] = filteredResolved.map {
        case (Some(schema), path) => (schema, path)
        case (None, _)            => throw new Exception("Should never happen")
      } groupBy { case (schema, _) => schema } mapValues (it => it.map { case (_, path) => path })

      case class JobContext(
        domain: Domain,
        schema: Schema,
        paths: List[Path],
        options: Map[String, String]
      )
      groupedResolved.map { case (schema, pendingPaths) =>
        logger.info(s"""Ingest resolved file : ${pendingPaths
            .map(_.getName)
            .mkString(",")} with schema ${schema.name}""")

        // We group by groupedMax to avoid rateLimit exceeded when the number of grouped files is too big for some cloud storage rate limitations.
        val groupedPendingPathsIterator =
          pendingPaths.grouped(settings.comet.groupedMax)
        groupedPendingPathsIterator.map { pendingPaths =>
          val ingestingPaths = pendingPaths.map { pendingPath =>
            val ingestingPath = new Path(DatasetArea.ingesting(domain.name), pendingPath.getName)
            if (!storageHandler.move(pendingPath, ingestingPath)) {
              logger.error(s"Could not move $pendingPath to $ingestingPath")
            }
            ingestingPath
          }
          val jobs = if (settings.comet.grouped) {
            JobContext(domain, schema, ingestingPaths.toList, config.options) :: Nil
          } else {
            // We ingest all the files but return false if one of them fails.
            ingestingPaths.map { path =>
              JobContext(domain, schema, path :: Nil, config.options)
            }
          }
          val (parJobs, forkJoinPool) = makeParallel(jobs.toList, settings.comet.scheduling.maxJobs)
          val res = parJobs.map { jobContext =>
            launchHandler.ingest(
              this,
              jobContext.domain,
              jobContext.schema,
              jobContext.paths,
              jobContext.options
            ) match {
              case Failure(e) =>
                e.printStackTrace()
                false
              case Success(r) => true
            }
          }.toList
          forkJoinPool.foreach(_.shutdown())
          res.forall(_ == true)
        }
      }
    }.flatten
    result.forall(_ == true)
  }

  private def domainsToWatch(config: WatchConfig) = {
    val includedDomains = (config.includes, config.excludes) match {
      case (Nil, Nil) =>
        domains
      case (_, Nil) =>
        domains.filter(domain => config.includes.contains(domain.name))
      case (Nil, _) =>
        domains.filter(domain => !config.excludes.contains(domain.name))
      case (_, _) => throw new Exception("Should never happen ")
    }
    logger.info(s"Domains that will be watched: ${includedDomains.map(_.name).mkString(",")}")
    includedDomains
  }

  /** @param domainName
    *   : Domaine name
    * @return
    *   resolved && unresolved schemas / path
    */
  private def pending(
    domainName: String,
    schemasName: List[String]
  ): (Iterable[(Option[Schema], Path)], Iterable[(Option[Schema], Path)]) = {
    val pendingArea = DatasetArea.pending(domainName)
    logger.info(s"List files in $pendingArea")
    val files = Utils
      .loadInstance[LoadStrategy](settings.comet.loadStrategyClass)
      .list(settings.storageHandler, pendingArea, recursive = false)
    if (files.nonEmpty)
      logger.info(s"Found ${files.mkString(",")}")
    else
      logger.info(s"No Files Found.")
    val domain = schemaHandler.getDomain(domainName)

    val filteredFiles = (dom: Domain) =>
      if (schemasName.nonEmpty) {
        logger.info(
          s"We will only watch files that match the schemas name:" +
          s" $schemasName for the Domain: ${dom.name}"
        )
        files.filter(f => predicate(dom, schemasName, f))
      } else {
        logger.info(
          s"We will watch all the files for the Domain:" +
          s" ${dom.name}"
        )
        files
      }

    val schemas = for {
      dom <- domain.toList
      (schema, path) <- filteredFiles(dom).map { file =>
        (dom.findSchema(file.getName), file)
      }
    } yield {
      logger.info(
        s"Found Schema ${schema.map(_.name).getOrElse("None")} for file $path"
      )
      (schema, path)
    }
    if (schemas.isEmpty) {
      logger.info(s"No Files found to ingest.")
    }
    schemas.partition { case (schema, _) => schema.isDefined }
  }

  private def predicate(domain: Domain, schemasName: List[String], file: Path): Boolean = {
    schemasName.exists { schemaName =>
      val schema = domain.tables.find(_.name.equals(schemaName))
      schema.exists(_.pattern.matcher(file.getName).matches())
    }
  }

  /** Ingest the file (called by the cron manager at ingestion time for a specific dataset
    */
  def load(config: LoadConfig): Boolean = {
    if (config.domain.isEmpty || config.schema.isEmpty) {
      val domainToWatch = if (config.domain.nonEmpty) List(config.domain) else Nil
      val schemasToWatch = if (config.schema.nonEmpty) List(config.schema) else Nil
      loadPending(
        WatchConfig(
          includes = domainToWatch,
          schemas = schemasToWatch,
          options = config.options
        )
      )
    } else {
      val lockPath =
        new Path(settings.comet.lock.path, s"${config.domain}_${config.schema}.lock")
      val locker = new FileLock(lockPath, storageHandler)
      val waitTimeMillis = settings.comet.lock.timeout

      locker.doExclusively(waitTimeMillis) {
        val domainName = config.domain
        val schemaName = config.schema
        val ingestingPaths = config.paths
        val result = for {
          domain <- domains.find(_.name == domainName)
          schema <- domain.tables.find(_.name == schemaName)
        } yield ingest(domain, schema, ingestingPaths, config.options)
        result match {
          case None | Some(Success(_)) => true
          case Some(Failure(exception)) =>
            Utils.logException(logger, exception)
            false
        }
      }
    }
  }

  @nowarn
  def ingest(
    domain: Domain,
    schema: Schema,
    ingestingPath: List[Path],
    options: Map[String, String]
  ): Try[JobResult] = {
    logger.info(
      s"Start Ingestion on domain: ${domain.name} with schema: ${schema.name} on file: $ingestingPath"
    )
    val metadata = domain.metadata
      .getOrElse(Metadata())
      .merge(schema.metadata.getOrElse(Metadata()))
    logger.info(
      s"Ingesting domain: ${domain.name} with schema: ${schema.name} on file: $ingestingPath with metadata $metadata"
    )

    val ingestionResult = Try {
      val optionsAndEnvVars = schemaHandler.activeEnvVars() ++ options
      metadata.getFormat() match {
        case Format.PARQUET =>
          new ParquetIngestionJob(
            domain,
            schema,
            schemaHandler.types(),
            ingestingPath,
            storageHandler,
            schemaHandler,
            optionsAndEnvVars
          ).run()
        case Format.GENERIC =>
          new GenericIngestionJob(
            domain,
            schema,
            schemaHandler.types(),
            ingestingPath,
            storageHandler,
            schemaHandler,
            optionsAndEnvVars
          ).run()
        case Format.DSV =>
          new DsvIngestionJob(
            domain,
            schema,
            schemaHandler.types(),
            ingestingPath,
            storageHandler,
            schemaHandler,
            optionsAndEnvVars
          ).run()
        case Format.SIMPLE_JSON =>
          new SimpleJsonIngestionJob(
            domain,
            schema,
            schemaHandler.types(),
            ingestingPath,
            storageHandler,
            schemaHandler,
            optionsAndEnvVars
          ).run()
        case Format.JSON =>
          new JsonIngestionJob(
            domain,
            schema,
            schemaHandler.types(),
            ingestingPath,
            storageHandler,
            schemaHandler,
            optionsAndEnvVars
          ).run()
        case Format.XML =>
          new XmlIngestionJob(
            domain,
            schema,
            schemaHandler.types(),
            ingestingPath,
            storageHandler,
            schemaHandler,
            optionsAndEnvVars
          ).run()
        case Format.TEXT_XML =>
          new XmlSimplePrivacyJob(
            domain,
            schema,
            schemaHandler.types(),
            ingestingPath,
            storageHandler,
            schemaHandler,
            optionsAndEnvVars
          ).run()
        case Format.POSITION =>
          new PositionIngestionJob(
            domain,
            schema,
            schemaHandler.types(),
            ingestingPath,
            storageHandler,
            schemaHandler,
            optionsAndEnvVars
          ).run()
        case Format.KAFKA =>
          new KafkaIngestionJob(
            domain,
            schema,
            schemaHandler.types(),
            ingestingPath,
            storageHandler,
            schemaHandler,
            optionsAndEnvVars,
            FILE
          ).run()
        case Format.KAFKASTREAM =>
          new KafkaIngestionJob(
            domain,
            schema,
            schemaHandler.types(),
            ingestingPath,
            storageHandler,
            schemaHandler,
            optionsAndEnvVars,
            STREAM
          ).run()
        case _ =>
          throw new Exception("Should never happen")
      }
    }
    ingestionResult match {
      case Success(Success(jobResult)) =>
        if (settings.comet.archive) {
          val (parIngests, forkJoinPool) = makeParallel(ingestingPath, settings.comet.maxParCopy)
          parIngests.foreach { ingestingPath =>
            val archivePath =
              new Path(DatasetArea.archive(domain.name), ingestingPath.getName)
            logger.info(s"Backing up file $ingestingPath to $archivePath")
            val _ = storageHandler.move(ingestingPath, archivePath)
          }
          forkJoinPool.foreach(_.shutdown())
        } else {
          logger.info(s"Deleting file $ingestingPath")
          ingestingPath.foreach(storageHandler.delete)
        }
        Success(jobResult)
      case Success(Failure(exception)) =>
        Utils.logException(logger, exception)
        Failure(exception)
      case Failure(exception) =>
        Utils.logException(logger, exception)
        Failure(exception)
    }
  }

  @nowarn
  private def makeParallel[T](
    collection: List[T],
    maxPar: Int
  ): (GenSeq[T], Option[ForkJoinPool]) = {
    maxPar match {
      case 1 => (collection, None)
      case _ =>
        val parCollection = collection.par
        val forkJoinPool =
          new scala.concurrent.forkjoin.ForkJoinPool(maxPar)
        parCollection.tasksupport = new ForkJoinTaskSupport(forkJoinPool)
        (parCollection, Some(forkJoinPool))
    }
  }

  def inferSchema(config: InferSchemaConfig): Try[File] = {
    val result = new InferSchema(
      config.domainName,
      config.schemaName,
      config.inputPath,
      config.outputDir,
      config.withHeader,
      config.format
    ).run()
    Utils.logFailure(result, logger)
    result
  }

  def inferDDL(config: Yml2DDLConfig): Try[Unit] = {
    val result = new Yml2DDLJob(config, schemaHandler).run()
    Utils.logFailure(result, logger)
  }

  private def buildTasks(
    config: TransformConfig
  ): Seq[AutoTask] = {
    val jobDesc =
      schemaHandler
        .job(config.name)
        .getOrElse(throw new Exception(s"Invalid job name ${config.name}"))
    logger.info(jobDesc.toString)
    AutoTask.tasks(
      jobDesc,
      config.options,
      config.interactive,
      config.authInfo
    )(
      settings,
      storageHandler,
      schemaHandler
    )
  }

  def compileAutoJob(config: TransformConfig): Seq[String] = {
    val job = schemaHandler.jobs(config.reload)
    logger.info(job.toString)
    val result = buildTasks(config).map { action =>
      val engine = action.engine
      logger.info(s"running with -> $engine engine")
      // TODO Interactive compilation should check table existence
      val (_, mainSQL, _) = action.buildAllSQLQueries(false)
      mainSQL
    }
    result.foreach { sql =>
      val output = settings.comet.rootServe.map(rootServe => File(File(rootServe), "compile.log"))
      output.foreach(_.overwrite(s"""START COMPILE SQL $sql END COMPILE SQL"""))
      logger.info(s"""START COMPILE SQL $sql END COMPILE SQL""")
    }
    result
  }

  /** Successively run each task of a job
    *
    * @param transformConfig
    *   : job name as defined in the YML file and sql parameters to pass to SQL statements.
    */
  // scalastyle:off println
  def autoJob(transformConfig: TransformConfig): Boolean = {
    val job = schemaHandler.jobs(transformConfig.reload)(transformConfig.name)
    logger.info(job.toString)
    val result: Seq[Boolean] =
      buildTasks(transformConfig).map { action =>
        val engine = action.engine
        logger.info(s"running with config $transformConfig")

        engine match {
          case BQ =>
            logger.info(s"Entering $engine engine")
            val result = action.runBQ(transformConfig.drop)
            transformConfig.interactive match {
              case None =>
                val sink = action.taskDesc.sink
                logger.info(s"BQ Job succeeded. sinking data to $sink")
                sink match {
                  case Some(sink) if sink.getType() == SinkType.BQ =>
                    logger.info("Sinking to BQ done")
                  case _ =>
                    // TODO Sinking not supported
                    logger.info(s"Sinking defaulted to BQ.")
                }
              case Some(format) =>
                result.map { result =>
                  val bqJobResult = result.asInstanceOf[BigQueryJobResult]
                  logger.info("START INTERACTIVE SQL")
                  bqJobResult.show(format, settings.comet.rootServe)
                  logger.info("END INTERACTIVE SQL")
                }
              // No sink on interactive queries. Results displayed in console output
            }
            Utils.logFailure(result, logger)
            result match {
              case Success(res) =>
              case Failure(e) =>
                val output =
                  settings.comet.rootServe.map(rootServe => File(File(rootServe), "transform.log"))
                output.foreach(_.overwrite(Utils.exceptionAsString(e)))
            }

            result.isSuccess
          case custom =>
            logger.info(s"Entering $custom engine")
            (action.runSpark(transformConfig.drop), transformConfig.interactive) match {
              case (Success((SparkJobResult(None), _)), _) =>
                true
              case (Success((SparkJobResult(Some(dataFrame)), _)), Some(_)) =>
                // For interactive display. Used by the VSCode plugin
                logger.info("""START QUERY SQL""")
                dataFrame.show(false)
                logger.info("""END QUERY SQL""")
                true
              case (Success((SparkJobResult(maybeDataFrame), sqlSource)), None) =>
                val sinkOption = action.sink
                logger.info(s"Spark Job succeeded. sinking data to $sinkOption")
                sinkOption match {
                  case Some(sink) =>
                    sink match {
                      case _: EsSink if settings.comet.elasticsearch.active =>
                        saveToES(action)
                      case fsSink: FsSink if !settings.comet.sinkToFile =>
                        maybeDataFrame.exists(dataframe => action.sinkToFS(dataframe, fsSink))

                      case bqSink: BigQuerySink =>
                        val source = maybeDataFrame
                          .map(df => Right(Utils.setNullableStateOfColumn(df, nullable = true)))
                          .getOrElse(Left(action.taskDesc.getTargetPath().toString))
                        val (createDisposition, writeDisposition) = {
                          Utils.getDBDisposition(action.taskDesc.write, hasMergeKeyDefined = false)
                        }
                        val bqLoadConfig =
                          BigQueryLoadConfig(
                            transformConfig.authInfo.get("gcpProjectId"),
                            transformConfig.authInfo.get("gcpSAJsonKey"),
                            source = source,
                            outputTableId = Some(
                              BigQueryJobBase.extractProjectDatasetAndTable(
                                action.taskDesc.database,
                                action.taskDesc.domain,
                                action.taskDesc.table
                              )
                            ),
                            sourceFormat = settings.comet.defaultFormat,
                            createDisposition = createDisposition,
                            writeDisposition = writeDisposition,
                            location = bqSink.location,
                            outputPartition = bqSink.timestamp,
                            outputClustering = bqSink.clustering.getOrElse(Nil),
                            days = bqSink.days,
                            requirePartitionFilter = bqSink.requirePartitionFilter.getOrElse(false),
                            rls = action.taskDesc.rls,
                            options = bqSink.getOptions,
                            acl = action.taskDesc.acl,
                            starlakeSchema = Some(Schema.fromTaskDesc(action.taskDesc)),
                            // outputTableDesc = action.taskDesc.comment.getOrElse(""),
                            sqlSource = Some(sqlSource),
                            attributesDesc = action.taskDesc.attributesDesc,
                            outputDatabase = action.taskDesc.database
                          )
                        val result =
                          new BigQuerySparkJob(bqLoadConfig, None, action.taskDesc.comment).run()
                        result.isSuccess

                      case jdbcSink: JdbcSink =>
                        val jdbcName = jdbcSink.connection
                        val source = maybeDataFrame
                          .map(df => Right(df))
                          .getOrElse(Left(action.taskDesc.getTargetPath().toString))
                        val (createDisposition, writeDisposition) = {
                          Utils.getDBDisposition(action.taskDesc.write, hasMergeKeyDefined = false)
                        }
                        val jdbcConfig = ConnectionLoadConfig.fromComet(
                          jdbcName,
                          settings.comet,
                          source,
                          outputTable = action.taskDesc.domain + "." + action.taskDesc.table,
                          createDisposition = CreateDisposition.valueOf(createDisposition),
                          writeDisposition = WriteDisposition.valueOf(writeDisposition),
                          createTableIfAbsent = false,
                          sinkOptions = jdbcSink.getOptions
                        )

                        val res = new ConnectionLoadJob(jdbcConfig).run()
                        res match {
                          case Success(_) => true
                          case Failure(e) => logger.error("JDBCLoad Failed", e); false
                        }
                      case _: NoneSink =>
                        maybeDataFrame.foreach { dataframe =>
                          dataframe.write.format("console").save()
                        }
                        true
                      case _ =>
                        logger.warn(s"No supported Sink is activated for this job $sink")
                        true
                    }
                  case None =>
                    logger.warn("Sink is not activated for this job")
                    true
                }
              case (Failure(exception), _) =>
                val output =
                  settings.comet.rootServe.map(rootServe => File(File(rootServe), "run.log"))
                output.foreach(_.overwrite(Utils.exceptionAsString(exception)))
                exception.printStackTrace()
                false
            }
        }
      }
    result.forall(_ == true)
  }

  private def saveToES(action: AutoTask): Boolean = {
    val targetPath =
      new Path(DatasetArea.path(action.taskDesc.domain), action.taskDesc.table)
    val sink: EsSink = action.taskDesc.sink
      .map(_.asInstanceOf[EsSink])
      .getOrElse(
        throw new Exception("Sink of type ES must be specified when loading data to ES !!!")
      )
    launchHandler.esLoad(
      this,
      ESLoadConfig(
        timestamp = sink.timestamp,
        id = sink.id,
        format = settings.comet.defaultFormat,
        domain = action.taskDesc.domain,
        schema = action.taskDesc.table,
        dataset = Some(Left(targetPath)),
        options = sink.getOptions
      )
    )
  }

  def esLoad(config: ESLoadConfig): Try[JobResult] = {
    val res = new ESLoadJob(config, storageHandler, schemaHandler).run()
    Utils.logFailure(res, logger)
  }

  def bqload(
    config: BigQueryLoadConfig,
    maybeSchema: Option[BQSchema] = None,
    maybeTableDescription: Option[String] = None
  ): Try[JobResult] = {
    new BigQuerySparkJob(config, maybeSchema, maybeTableDescription).run()
  }

  def kafkaload(config: KafkaJobConfig): Try[JobResult] = {
    val res = new KafkaJob(config).run()
    Utils.logFailure(res, logger)
  }

  def jdbcload(config: ConnectionLoadConfig): Try[JobResult] = {
    val loadJob = new ConnectionLoadJob(config)
    val res = loadJob.run()
    Utils.logFailure(res, logger)
  }

  def atlas(config: AtlasConfig): Boolean = {
    new AtlasJob(config, storageHandler).run()
  }

  /** Runs the metrics job
    *
    * @param cliConfig
    *   : Client's configuration for metrics computing
    */
  def metric(cliConfig: MetricsConfig): Try[JobResult] = {
    // Lookup for the domain given as prompt arguments, if is found then find the given schema in this domain
    val cmdArgs = for {
      domain <- schemaHandler.getDomain(cliConfig.domain)
      schema <- domain.tables.find(_.name == cliConfig.schema)
    } yield (domain, schema)

    cmdArgs match {
      case Some((domain: Domain, schema: Schema)) =>
        val stage: Stage = cliConfig.stage.getOrElse(Stage.UNIT)
        val result = new MetricsJob(
          domain,
          schema,
          stage,
          storageHandler,
          schemaHandler
        ).run()
        Utils.logFailure(result, logger)
      case None =>
        logger.error("The domain or schema you specified doesn't exist! ")
        Failure(new Exception("The domain or schema you specified doesn't exist! "))
    }
  }
  def applyIamPolicies(): Boolean = {
    val ignore = BigQueryLoadConfig(
      None,
      None,
      outputDatabase = None
    )
    schemaHandler
      .iamPolicyTags()
      .exists { iamPolicyTags =>
        val res = new BigQuerySparkJob(ignore).applyIamPolicyTags(iamPolicyTags)
        res.recover { case e =>
          Utils.logException(logger, e)
          throw e
        }
        res.isSuccess
      }
  }

  def secure(config: WatchConfig): Boolean = {
    val includedDomains = domainsToWatch(config)
    val result = includedDomains.flatMap { domain =>
      domain.tables.map { schema =>
        val metadata = schema.mergedMetadata(domain.metadata)
        val dummyIngestionJob = new DummyIngestionJob(
          domain,
          schema,
          schemaHandler.types(),
          Nil,
          storageHandler,
          schemaHandler,
          Map.empty
        )
        if (settings.comet.isHiveCompatible()) {
          dummyIngestionJob.applyHiveTableAcl()
        } else {
          val sink = metadata.sink
            .getOrElse(throw new Exception("Sink required"))
          val isJdbcSink =
            sink.isInstanceOf[JdbcSink]
          if (isJdbcSink) {
            val connectionName = sink.asInstanceOf[JdbcSink].connection
            val connection = settings.comet.connections(connectionName)
            if (connection.isSnowflake)
              dummyIngestionJob.applySnowflakeTableAcl()
            else
              Success(true) // ignore other jdbc connection types
          } else if (metadata.sink.exists(_.isInstanceOf[BigQuerySink])) {
            val database = schemaHandler.getDatabase(domain, schema.finalName)
            val config = BigQueryLoadConfig(
              None,
              None,
              outputTableId = Some(
                BigQueryJobBase
                  .extractProjectDatasetAndTable(database, domain.name, schema.finalName)
              ),
              sourceFormat = settings.comet.defaultFormat,
              rls = schema.rls,
              acl = schema.acl,
              starlakeSchema = Some(schema),
              outputDatabase = database
            )
            val res = new BigQuerySparkJob(config).applyRLSAndCLS(forceApply = true)
            res.recover { case e =>
              Utils.logException(logger, e)
              throw e
            }

          } else {
            Success(true) // unknown sink, just ignore.
          }
        }
      }
    }
    !result.exists(_.isFailure)
  }
}
