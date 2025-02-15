package ai.starlake.schema.handlers

import ai.starlake.TestHelper
import ai.starlake.config.Settings
import ai.starlake.job.sink.bigquery.{BigQueryJobBase, BigQueryLoadConfig, BigQuerySparkJob}
import ai.starlake.job.transform.{AutoTask, TaskViewDependency, TransformConfig}
import ai.starlake.schema.model._
import ai.starlake.workflow.IngestionWorkflow
import com.google.cloud.hadoop.io.bigquery.BigQueryConfiguration
import org.apache.hadoop.fs.Path
import org.scalatest.BeforeAndAfterAll

class AutoJobHandlerSpec extends TestHelper with BeforeAndAfterAll {

  lazy val pathBusiness = new Path(cometMetadataPath + "/jobs/user.comet.yml")

  lazy val pathGraduateProgramBusiness = new Path(
    cometMetadataPath + "/jobs/graduateProgram.comet.yml"
  )

  lazy val pathGraduateDatasetProgramBusiness = new Path(
    cometDatasetsPath + "/business/graduateProgram/output"
  )

  lazy val pathUserDatasetBusiness = new Path(cometDatasetsPath + "/business/user/user")

  lazy val pathUserAccepted = new Path(cometDatasetsPath + "/accepted/user")

  lazy val pathGraduateProgramAccepted = new Path(cometDatasetsPath + "/accepted/graduateProgram")

  lazy val metadataPath = new Path(cometMetadataPath)

  override def beforeAll(): Unit = {
    sparkSession.read
      .option("inferSchema", "true")
      .json(getResPath("/expected/datasets/accepted/DOMAIN/User.json"))
      .write
      .mode("overwrite")
      .parquet(pathUserAccepted.toString)

    sparkSession.read
      .option("inferSchema", "true")
      .json(getResPath("/expected/datasets/accepted/DOMAIN/graduateProgram.json"))
      .write
      .mode("overwrite")
      .parquet(pathGraduateProgramAccepted.toString)
  }

  new WithSettings() {
    "trigger AutoJob by passing parameters on SQL statement" should "generate a dataset in business" in {

      val userView = s"${settings.comet.datasets}/accepted/user"
      val businessTask1 = AutoTaskDesc(
        name = "",
        sql = Some(
          s"with user_view as (select * from parquet.`$userView`) select firstname, lastname, age from user_view where age={{age}}"
        ),
        database = None,
        domain = "business/user",
        table = "user",
        write = WriteMode.OVERWRITE,
        python = None,
        merge = None
      )
      val businessJob =
        AutoJobDesc(
          "user",
          List(businessTask1),
          Nil,
          None,
          Some("parquet"),
          Some(false)
        )

      val businessJobDef = mapper
        .writer()
        .withAttribute(classOf[Settings], settings)
        .writeValueAsString(businessJob)
      storageHandler.write(businessJobDef, pathBusiness)

      val schemaHandler =
        new SchemaHandler(metadataStorageHandler, Map("age" -> "40"))

      val workflow =
        new IngestionWorkflow(storageHandler, schemaHandler, new SimpleLauncher())

      workflow.autoJob(TransformConfig("user"))

      val result = sparkSession.read
        .load(pathUserDatasetBusiness.toString)
        .select("firstname", "lastname", "age")
        .take(2)

      result.length shouldBe 2

      result
        .map(r => (r.getString(0), r.getString(1), r.getLong(2)))
        .toList should contain allElementsOf List(
        ("test3", "test4", 40),
        ("Elon", "Musk", 40)
      )
    }

    "Extract file and view dependencies" should "work" in {

      val userView = s"${settings.comet.datasets}/accepted/user"
      logger.info("************userView:" + userView)
      val businessTask1 = AutoTaskDesc(
        name = "",
        sql = Some(
          s"with user_view as (select * from parquet.`$userView`) select firstname, lastname, age from user_view where age={{age}} and lastname={{lastname}} and firstname={{firstname}}"
        ),
        database = None,
        domain = "user",
        table = "user",
        write = WriteMode.OVERWRITE,
        expectations = Map("uniqFirstname" -> "isUnique(firstname)"),
        python = None,
        merge = None
      )
      val businessJob =
        AutoJobDesc(
          "user",
          List(businessTask1),
          Nil,
          None,
          Some("parquet"),
          Some(false)
        )

      val businessJobDef = mapper
        .writer()
        .withAttribute(classOf[Settings], settings)
        .writeValueAsString(businessJob)
      storageHandler.write(businessJobDef, pathBusiness)

      val schemaHandler = new SchemaHandler(storageHandler)

      val tasks = AutoTask.unauthenticatedTasks(true)(settings, storageHandler, schemaHandler)
      val deps = TaskViewDependency.dependencies(tasks)(schemaHandler)
      deps.map(_.parentRef) should contain theSameElementsAs List(
        "parquet." + userView,
        "user_view"
      )
    }

    "trigger AutoJob by passing three parameters on SQL statement" should "generate a dataset in business" in {

      val userView = s"${settings.comet.datasets}/accepted/user"
      val businessTask1 = AutoTaskDesc(
        name = "",
        sql = Some(
          s"with user_view as (select * from parquet.`$userView`) select firstname, lastname, age from user_View where age={{age}} and lastname={{lastname}} and firstname={{firstname}}"
        ),
        database = None,
        domain = "business/user",
        table = "user",
        write = WriteMode.OVERWRITE,
        expectations = Map("uniqFirstname" -> "isUnique(firstname)"),
        python = None,
        merge = None
      )
      val businessJob =
        AutoJobDesc(
          "user",
          List(businessTask1),
          Nil,
          None,
          Some("parquet"),
          Some(false)
        )

      val businessJobDef = mapper
        .writer()
        .withAttribute(classOf[Settings], settings)
        .writeValueAsString(businessJob)
      storageHandler.write(businessJobDef, pathBusiness)

      val schemaHandler = new SchemaHandler(
        storageHandler,
        Map("age" -> "25", "lastname" -> "'Doe'", "firstname" -> "'John'")
      )

      val workflow =
        new IngestionWorkflow(storageHandler, schemaHandler, new SimpleLauncher())
      workflow.autoJob(
        TransformConfig("user")
      )

      val result = sparkSession.read
        .load(pathUserDatasetBusiness.toString)
        .select("firstname", "lastname", "age")
        .take(2)

      result.length shouldBe 1
      result
        .map(r => (r.getString(0), r.getString(1), r.getLong(2)))
        .toList should contain allElementsOf List(
        ("John", "Doe", 25)
      )
    }

    "trigger AutoJob with no parameters on SQL statement" should "generate a dataset in business" in {

      val userView = s"${settings.comet.datasets}/accepted/user"
      val businessTask1 = AutoTaskDesc(
        name = "",
        sql = Some(
          s"with user_view as (select * from parquet.`$userView`) select firstname, lastname, age from user_view"
        ),
        database = None,
        domain = "business/user",
        table = "user",
        write = WriteMode.OVERWRITE,
        python = None,
        merge = None
      )
      val businessJob =
        AutoJobDesc(
          "user",
          List(businessTask1),
          Nil,
          None,
          Some("parquet"),
          Some(false)
        )

      val businessJobDef = mapper
        .writer()
        .withAttribute(classOf[Settings], settings)
        .writeValueAsString(businessJob)
      storageHandler.write(businessJobDef, pathBusiness)

      val schemaHandler = new SchemaHandler(storageHandler)

      val workflow =
        new IngestionWorkflow(storageHandler, schemaHandler, new SimpleLauncher())

      workflow.autoJob(TransformConfig("user"))

      sparkSession.read
        .load(pathUserDatasetBusiness.toString)
        .select("firstname", "lastname", "age")
        .take(6)
        .map(r => (r.getString(0), r.getString(1), r.getLong(2)))
        .toList should contain allElementsOf List(
        ("John", "Doe", 25),
        ("fred", "abruzzi", 25),
        ("test3", "test4", 40)
      )
    }

    "trigger AutoJob using an UDF" should "generate a dataset in business" in {

      val userView = s"${settings.comet.datasets}/accepted/user"
      val businessTask1 = AutoTaskDesc(
        name = "",
        sql = Some(
          s"with user_view as (select * from parquet.`$userView`) select concatWithSpace(firstname, lastname) as fullName from user_View"
        ),
        database = None,
        domain = "business/user",
        table = "user",
        write = WriteMode.OVERWRITE,
        python = None,
        merge = None
      )
      val businessJob =
        AutoJobDesc(
          "user",
          List(businessTask1),
          Nil,
          None,
          Some("parquet"),
          Some(false),
          udf = Some("ai.starlake.udf.TestUdf")
        )

      val businessJobDef = mapper
        .writer()
        .withAttribute(classOf[Settings], settings)
        .writeValueAsString(businessJob)

      storageHandler.write(businessJobDef, pathBusiness)

      val schemaHandler = new SchemaHandler(storageHandler)

      val workflow =
        new IngestionWorkflow(storageHandler, schemaHandler, new SimpleLauncher())

      workflow.autoJob(TransformConfig("user"))

      sparkSession.read
        .load(pathUserDatasetBusiness.toString)
        .select("fullName")
        .take(7)
        .map(r => r.getString(0))
        .toList should contain allElementsOf List(
        "John Doe",
        "fred abruzzi",
        "test3 test4"
      )
    }

    "trigger AutoJob by passing parameters to presql statement" should "generate a dataset in business" in {
      val businessTask1 = AutoTaskDesc(
        name = "",
        sql = Some(
          s"SELECT * FROM graduate_agg_view"
        ),
        database = None,
        domain = "business/graduateProgram",
        table = "output",
        write = WriteMode.OVERWRITE,
        presql = List(
          s"""
            |create or replace temporary view graduate_agg_view as
            |      select degree, department,
            |      school
            |      from parquet.`${settings.comet.datasets}/accepted/graduateProgram`
            |      where school={{school}}
            |""".stripMargin
        ),
        python = None,
        merge = None
      )
      val businessJob =
        AutoJobDesc(
          "graduateProgram",
          List(businessTask1),
          Nil,
          None,
          Some("parquet"),
          Some(false)
        )

      val businessJobDef = mapper
        .writer()
        .withAttribute(classOf[Settings], settings)
        .writeValueAsString(businessJob)
      storageHandler.write(businessJobDef, pathGraduateProgramBusiness)

      val schemaHandler = new SchemaHandler(storageHandler, Map("school" -> "'UC_Berkeley'"))
      val workflow =
        new IngestionWorkflow(storageHandler, schemaHandler, new SimpleLauncher())

      workflow.autoJob(TransformConfig("graduateProgram"))

      val result = sparkSession.read
        .load(pathGraduateDatasetProgramBusiness.toString)
        .select("*")

      result
        .take(7)
        .map(r => (r.getString(0), r.getString(1), r.getString(2)))
        .toList should contain allElementsOf List(
        ("Masters", "School_of_Information", "UC_Berkeley"),
        ("Masters", "EECS", "UC_Berkeley"),
        ("Ph.D.", "EECS", "UC_Berkeley")
      )

    }

    "BQ Business Job Definition" should "Prepare correctly against BQ" in {
      val businessTask1 = AutoTaskDesc(
        name = "",
        sql = Some("select * from domain"),
        database = None,
        domain = "DOMAIN",
        table = "TABLE",
        write = WriteMode.OVERWRITE,
        partition = List("comet_year", "comet_month"),
        presql = Nil,
        postsql = Nil,
        None,
        rls = List(RowLevelSecurity("myrls", "TRUE", Set("user:hayssam.saleh@ebiznext.com"))),
        python = None,
        merge = None
      )

      val sink = businessTask1.sink.map(_.asInstanceOf[BigQuerySink])

      val config = BigQueryLoadConfig(
        None,
        None,
        outputTableId = Some(
          BigQueryJobBase.extractProjectDatasetAndTable(
            businessTask1.database,
            businessTask1.domain,
            businessTask1.table
          )
        ),
        sourceFormat = "parquet",
        createDisposition = "CREATE_IF_NEEDED",
        writeDisposition = "WRITE_TRUNCATE",
        location = sink.flatMap(_.location),
        outputPartition = sink.flatMap(_.timestamp),
        outputClustering = sink.flatMap(_.clustering).getOrElse(Nil),
        days = sink.flatMap(_.days),
        requirePartitionFilter = sink.flatMap(_.requirePartitionFilter).getOrElse(false),
        rls = businessTask1.rls,
        outputDatabase = None
      )
      val job = new BigQuerySparkJob(config)
      val conf = job.prepareConf()

      conf.get(
        BigQueryConfiguration.OUTPUT_TABLE_WRITE_DISPOSITION.getKey()
      ) shouldEqual "WRITE_TRUNCATE"
      conf.get(
        BigQueryConfiguration.OUTPUT_TABLE_CREATE_DISPOSITION.getKey()
      ) shouldEqual "CREATE_IF_NEEDED"

      val delStatement = "DROP ALL ROW ACCESS POLICIES ON `DOMAIN.TABLE`"
      val createStatement =
        """
          | CREATE ROW ACCESS POLICY
          |  myrls
          | ON
          |  `DOMAIN.TABLE`
          | GRANT TO
          |  ("user:hayssam.saleh@ebiznext.com")
          | FILTER USING
          |  (TRUE)
          |""".stripMargin
      job.prepareRLS() should contain theSameElementsInOrderAs List(delStatement, createStatement)
    }
  }
}
