package ai.starlake.extract

import ai.starlake.TestHelper
import ai.starlake.config.Settings
import ai.starlake.schema.handlers.SchemaHandler
import ai.starlake.schema.model.{Domain, Metadata, Mode, Schema}
import ai.starlake.utils.YamlSerializer
import better.files.File

import java.sql.DriverManager
import scala.util.{Failure, Success}

class ExtractSpec extends TestHelper {
  "JDBC2Yml of all tables" should "should generated all the table schemas in a YML file" in {
    new WithSettings() {
      val metadata = Metadata(
        mode = Some(Mode.STREAM),
        quote = Some("::"),
        directory = Some("/{{domain}}/{{schema}}")
      )
      val domainTemplate = Domain(name = "CUSTOM_NAME", metadata = Some(metadata))
      testSchemaExtraction(
        JDBCSchema(None, "PUBLIC", pattern = Some("{{schema}}-{{table}}.*"))
          .fillWithDefaultValues(),
        settings.comet.connections("test-h2").options,
        Some(domainTemplate)
      ) { case (domain, _, _) =>
        assert(domain.metadata.flatMap(_.quote).getOrElse("") == "::")
        assert(domain.metadata.flatMap(_.mode).getOrElse(Mode.FILE) == Mode.STREAM)
        assert(domain.metadata.flatMap(_.directory).isDefined)
      }
    }
  }

  it should "should generated all the table schemas in a YML file without domain template" in {
    new WithSettings() {
      testSchemaExtraction(
        JDBCSchema(None, "PUBLIC", pattern = Some("{{schema}}-{{table}}.*"))
          .fillWithDefaultValues(),
        settings.comet.connections("test-h2").options,
        None
      ) { case (domain, _, _) =>
        assert(domain.metadata.isEmpty)
      }
    }
  }

  private def testSchemaExtraction(
    jdbcSchema: JDBCSchema,
    connectionOptions: Map[String, String],
    domainTemplate: Option[Domain]
  )(assertOutput: (Domain, Schema, Schema) => Unit) // Domain, Table definition and View definition
  (implicit settings: Settings) = {
    val jdbcOptions = settings.comet.connections("test-h2")
    val conn = DriverManager.getConnection(
      jdbcOptions.options("url"),
      jdbcOptions.options("user"),
      jdbcOptions.options("password")
    )
    val sql: String =
      """
        |drop table if exists test_table1;
        |create table test_table1(ID INT PRIMARY KEY,NAME VARCHAR(500));
        |create view test_view1 AS SELECT NAME FROM test_table1;
        |insert into test_table1 values (1,'A');""".stripMargin
    val st = conn.createStatement()
    st.execute(sql)
    val rs = st.executeQuery("select * from test_table1")
    rs.next
    val row1InsertionCheck = (1 == rs.getInt("ID")) && ("A" == rs.getString("NAME"))
    assert(row1InsertionCheck, "Data not inserted")
    val outputDir: File = File(s"$cometTestRoot/extract-without-template")
    new ExtractJDBCSchema(new SchemaHandler(settings.storageHandler)).extractSchema(
      jdbcSchema,
      connectionOptions,
      outputDir,
      domainTemplate,
      None
    )
    val publicOutputDir = outputDir / "PUBLIC"
    val publicPath = publicOutputDir / "PUBLIC.comet.yml"
    val domain =
      YamlSerializer.deserializeDomain(
        publicPath.contentAsString,
        publicPath.pathAsString
      ) match {
        case Success(domain) => domain
        case Failure(e)      => throw e
      }
    assert(domain.name == "PUBLIC")
    assert(domain.tableRefs.size == 2)

    val tableFile = publicOutputDir / "_TEST_TABLE1.comet.yml"
    val table =
      YamlSerializer
        .deserializeSchemaRefs(tableFile.contentAsString, tableFile.pathAsString)
        .tables
        .head
    domain.tableRefs should contain theSameElementsAs Set(
      "_TEST_TABLE1",
      "_TEST_VIEW1"
    )
    table.attributes.map(a => a.name -> a.`type`).toSet should contain theSameElementsAs Set(
      "ID"   -> "long",
      "NAME" -> "string"
    )
    table.primaryKey should contain("ID")
    table.pattern.pattern() shouldBe "\\QPUBLIC\\E-\\QTEST_TABLE1\\E.*"
    val viewFile = publicOutputDir / "_TEST_VIEW1.comet.yml"
    val view =
      YamlSerializer
        .deserializeSchemaRefs(viewFile.contentAsString, viewFile.pathAsString)
        .tables
        .head
    view.pattern.pattern() shouldBe "\\QPUBLIC\\E-\\QTEST_VIEW1\\E.*"
    assertOutput(domain, table, view)
  }

  "JDBCSchemas" should "deserialize correctly" in {
    new WithSettings() {
      val input =
        """
          |extract:
          |  connectionRef: "test-h2" # Connection name as defined in the connections section of the application.conf file
          |  jdbcSchemas:
          |    - catalog: "business" # Optional catalog name in the target database
          |      schema: "public" # Database schema where tables are located
          |      tables:
          |        - name: "user"
          |          columns: # optional list of columns, if not present all columns should be exported.
          |            - id
          |            - email
          |        - name: product # All columns should be exported
          |        - name: "*" # Ignore any other table spec. Just export all tables
          |      tableTypes: # One or many of the types below
          |        - "TABLE"
          |        - "VIEW"
          |        - "SYSTEM TABLE"
          |        - "GLOBAL TEMPORARY"
          |        - "LOCAL TEMPORARY"
          |        - "ALIAS"
          |        - "SYNONYM"
          |      template: "/my-templates/domain-template.yml" # Metadata to use for the generated YML file.
          |      pattern: "{{schema}}-{{table}}.*"
          |""".stripMargin
      val jdbcMapping = File.newTemporaryFile()
      jdbcMapping.overwrite(input)

      val jdbcSchemas =
        YamlSerializer.deserializeJDBCSchemas(jdbcMapping.contentAsString, jdbcMapping.pathAsString)
      assert(jdbcSchemas.jdbcSchemas.nonEmpty)
      jdbcSchemas shouldBe JDBCSchemas(
        connectionRef = Some("test-h2"),
        jdbcSchemas = List(
          JDBCSchema(
            catalog = Some("business"),
            schema = "public",
            tables = List(
              JDBCTable("user", List("id", "email"), None, None, Map.empty, None, None),
              JDBCTable("product", Nil, None, None, Map.empty, None, None),
              JDBCTable("*", Nil, None, None, Map.empty, None, None)
            ),
            tableTypes = List(
              "TABLE",
              "VIEW",
              "SYSTEM TABLE",
              "GLOBAL TEMPORARY",
              "LOCAL TEMPORARY",
              "ALIAS",
              "SYNONYM"
            ),
            template = Some("/my-templates/domain-template.yml"),
            pattern = Some("{{schema}}-{{table}}.*")
          )
        ),
        globalJdbcSchema = None,
        connection = Map.empty
      )
    }
  }

  "JDBCSchemas" should "deserialize with global jdbc schema override" in {
    new WithSettings() {
      val input =
        """
          |extract:
          |  connectionRef: "test-h2" # Connection name as defined in the connections section of the application.conf file
          |  globalJdbcSchema:
          |    catalog: "business" # Optional catalog name in the target database
          |    schema: "public" # Database schema where tables are located
          |    tableTypes: # One or many of the types below
          |      - "TABLE"
          |      - "VIEW"
          |    template: "/my-templates/domain-template.yml" # Metadata to use for the generated YML file.
          |    pattern: "{{schema}}-{{table}}.*"
          |  jdbcSchemas:
          |    - tables:
          |        - name: "user"
          |          columns: # optional list of columns, if not present all columns should be exported.
          |            - id
          |            - email
          |        - name: product # All columns should be exported
          |        - name: "*" # Ignore any other table spec. Just export all tables
          |""".stripMargin
      val jdbcMapping = File.newTemporaryFile()
      jdbcMapping.overwrite(input)

      val jdbcSchemas =
        YamlSerializer.deserializeJDBCSchemas(jdbcMapping.contentAsString, jdbcMapping.pathAsString)
      assert(jdbcSchemas.jdbcSchemas.nonEmpty)
      jdbcSchemas shouldBe JDBCSchemas(
        connectionRef = Some("test-h2"),
        jdbcSchemas = List(
          JDBCSchema(
            catalog = Some("business"),
            schema = "public",
            tables = List(
              JDBCTable("user", List("id", "email"), None, None, Map.empty, None, None),
              JDBCTable("product", Nil, None, None, Map.empty, None, None),
              JDBCTable("*", Nil, None, None, Map.empty, None, None)
            ),
            tableTypes = List(
              "TABLE",
              "VIEW"
            ),
            template = Some("/my-templates/domain-template.yml"),
            pattern = Some("{{schema}}-{{table}}.*"),
            fullExport = Some(false)
          )
        ),
        globalJdbcSchema = Some(
          JDBCSchema(
            catalog = Some("business"),
            schema = "public",
            tables = Nil,
            tableTypes = List(
              "TABLE",
              "VIEW"
            ),
            template = Some("/my-templates/domain-template.yml"),
            pattern = Some("{{schema}}-{{table}}.*")
          )
        ),
        connection = Map.empty
      )
    }
  }

  "JDBCSchemas" should "deserialize with default value" in {
    new WithSettings() {
      val input =
        """
          |extract:
          |  connectionRef: "test-h2" # Connection name as defined in the connections section of the application.conf file
          |  globalJdbcSchema:
          |    catalog: "business" # Optional catalog name in the target database
          |    template: "/my-templates/domain-template.yml" # Metadata to use for the generated YML file.
          |    pattern: "{{schema}}-{{table}}.*"
          |  jdbcSchemas:
          |    - tables:
          |        - name: "user"
          |          columns: # optional list of columns, if not present all columns should be exported.
          |            - id
          |            - email
          |        - name: product # All columns should be exported
          |        - name: "*" # Ignore any other table spec. Just export all tables
          |""".stripMargin
      val jdbcMapping = File.newTemporaryFile()
      jdbcMapping.overwrite(input)

      val jdbcSchemas =
        YamlSerializer.deserializeJDBCSchemas(jdbcMapping.contentAsString, jdbcMapping.pathAsString)
      assert(jdbcSchemas.jdbcSchemas.nonEmpty)
      jdbcSchemas shouldBe JDBCSchemas(
        connectionRef = Some("test-h2"),
        jdbcSchemas = List(
          JDBCSchema(
            catalog = Some("business"),
            tables = List(
              JDBCTable("user", List("id", "email"), None, None, Map.empty, None, None),
              JDBCTable("product", Nil, None, None, Map.empty, None, None),
              JDBCTable("*", Nil, None, None, Map.empty, None, None)
            ),
            tableTypes = List(
              "TABLE",
              "VIEW",
              "SYSTEM TABLE",
              "GLOBAL TEMPORARY",
              "LOCAL TEMPORARY",
              "ALIAS",
              "SYNONYM"
            ),
            template = Some("/my-templates/domain-template.yml"),
            pattern = Some("{{schema}}-{{table}}.*"),
            fullExport = Some(false)
          )
        ),
        globalJdbcSchema = Some(
          JDBCSchema(
            catalog = Some("business"),
            tables = Nil,
            tableTypes = Nil,
            template = Some("/my-templates/domain-template.yml"),
            pattern = Some("{{schema}}-{{table}}.*")
          )
        ),
        connection = Map.empty
      )
    }
  }

  "JDBC2Yml of some columns" should "should generate only the tables and columns requested" in {
    new WithSettings() {
      val jdbcOptions = settings.comet.connections("test-h2")
      val conn = DriverManager.getConnection(
        jdbcOptions.options("url"),
        jdbcOptions.options("user"),
        jdbcOptions.options("password")
      )
      val sql: String =
        """
          |drop view if exists test_view1;
          |drop table if exists test_table1;
          |create table test_table1(ID INT PRIMARY KEY,NAME VARCHAR(500));
          |insert into test_table1 values (1,'A');""".stripMargin
      val st = conn.createStatement()
      st.execute(sql)
      val rs = st.executeQuery("select * from test_table1")
      rs.next
      val row1InsertionCheck = (1 == rs.getInt("ID")) && ("A" == rs.getString("NAME"))
      assert(row1InsertionCheck, "Data not inserted")

      new ExtractJDBCSchema(new SchemaHandler(settings.storageHandler)).extractSchema(
        JDBCSchema(
          None,
          "PUBLIC",
          None,
          None,
          List(JDBCTable("TEST_TABLE1", List("ID"), None, None, Map.empty, None, None))
        ).fillWithDefaultValues(),
        settings.comet.connections("test-h2").options,
        File("/tmp"),
        None,
        None
      )
      val publicPath = File("/tmp/PUBLIC/PUBLIC.comet.yml")
      val domain =
        YamlSerializer.deserializeDomain(
          publicPath.contentAsString,
          publicPath.pathAsString
        ) match {
          case Success(domain) => domain
          case Failure(e)      => throw e
        }
      assert(domain.name == "PUBLIC")
      assert(domain.tableRefs.size == 1)
      assert(domain.tableRefs.head == "_TEST_TABLE1")
      val tableFile = File("/tmp/PUBLIC", "_TEST_TABLE1.comet.yml")
      val table =
        YamlSerializer
          .deserializeSchemaRefs(tableFile.contentAsString, tableFile.pathAsString)
          .tables
          .head
      table.attributes
        .map(_.name) should contain theSameElementsAs Set("ID")
      table.attributes.map(_.`type`) should contain theSameElementsAs Set("long")
      table.primaryKey should contain("ID")
    }
  }

  "JDBC2Yml with foreign keys" should "detect the foreign keys" in {
    new WithSettings() {
      val jdbcOptions = settings.comet.connections("test-h2")
      val conn = DriverManager.getConnection(
        jdbcOptions.options("url"),
        jdbcOptions.options("user"),
        jdbcOptions.options("password")
      )
      val sql: String =
        """
          |drop view if exists test_view1;
          |drop table if exists test_table1;
          |drop table if exists test_table2;
          |create table test_table1(ID INT PRIMARY KEY,NAME VARCHAR(500));
          |create table test_table2(ID INT PRIMARY KEY,TABLE1_ID INT,foreign key (TABLE1_ID) references test_table1(ID));
          |insert into test_table1 values (1,'A');
          |insert into test_table2 values (1,1);
          |insert into test_table2 values (2,1);""".stripMargin
      val st = conn.createStatement()
      st.execute(sql)
      val rs = st.executeQuery("select * from test_table1")
      rs.next
      val row1InsertionCheck = (1 == rs.getInt("ID")) && ("A" == rs.getString("NAME"))
      assert(row1InsertionCheck, "Data not inserted")

      new ExtractJDBCSchema(new SchemaHandler(settings.storageHandler)).extractSchema(
        JDBCSchema(
          None,
          "PUBLIC",
          None,
          None,
          List(JDBCTable("TEST_TABLE2", Nil, None, None, Map.empty, None, None))
        ).fillWithDefaultValues(),
        settings.comet.connections("test-h2").options,
        File("/tmp"),
        None,
        None
      )
      val publicPath = File("/tmp/PUBLIC/PUBLIC.comet.yml")
      val domain =
        YamlSerializer.deserializeDomain(
          publicPath.contentAsString,
          publicPath.pathAsString
        ) match {
          case Success(domain) => domain
          case Failure(e)      => throw e
        }
      assert(domain.name == "PUBLIC")
      assert(domain.tableRefs.size == 1)
      assert(domain.tableRefs.head == "_TEST_TABLE2")
      val tableFile = File("/tmp/PUBLIC", "_TEST_TABLE2.comet.yml")
      val table =
        YamlSerializer
          .deserializeSchemaRefs(tableFile.contentAsString, tableFile.pathAsString)
          .tables
          .head
      table.attributes
        .find(_.name == "TABLE1_ID")
        .get
        .foreignKey
        .getOrElse("") should be("PUBLIC.TEST_TABLE1.ID")
    }
  }

  "ExtractData Config" should "work" in {
    val rendered = ExtractDataConfig.usage()
    println(rendered)
    val expected =
      s"""
        |Usage: starlake extract-data [options]
        |
        |
        |Extract data from any database defined in mapping file.
        |
        |Extraction is done in parallel by default and use all the available processors. It can be changed using `parallelism` CLI config.
        |Extraction of a table can be divided in smaller chunk and fetched in parallel by defining partitionColumn and its numPartitions.
        |
        |Examples
        |========
        |
        |Objective: Extract data and customize timestamp to have higher precision.
        |
        |  starlake.sh extract-data --config my-config --output-dir $$PWD/output --timestampPattern "yyyy-MM-dd HH:mm:ss.SSSSSS"
        |
        |Objective: Plan to fetch all data but with different scheduling (once a day for all and twice a day for some) with failure recovery like behavior.
        |  starlake.sh extract-data --config my-config --output-dir $$PWD/output --includeSchemas aSchema
        |         --includeTables table1RefreshedTwiceADay,table2RefreshedTwiceADay --ifExtractedBefore "2023-04-21 12:00:00"
        |         --clean
        |
        |
        |  --mapping <value>        Deprecated. Use config instead
        |  --config <value>         Database tables & connection info
        |  --limit <value>          Limit number of records
        |  --numPartitions <value>  parallelism level regarding partitionned tables
        |  --parallelism <value>    parallelism level of the extraction process. By default equals to the available cores: ${Runtime
          .getRuntime()
          .availableProcessors()}
        |  --separator <value>      Column separator
        |  --clean                  Clean all files of table only when it is extracted.
        |  --output-dir <value>     Where to output csv files
        |  --fullExport             Force full export to all tables
        |  --datePattern <value>    Pattern used to format date during CSV writing
        |  --timestampPattern <value>
        |                           Pattern used to format timestamp during CSV writing
        |  --ifExtractedBefore <value>
        |                           DateTime to compare with the last beginning extraction dateTime. If it is before that date, extraction is done else skipped.
        |  --cleanOnExtract         Deprecated. Use --clean instead.
        |  --includeSchemas schema1,schema2
        |                           Domains to include during extraction.
        |  --excludeSchemas schema1,schema2...
        |                           Domains to exclude during extraction. if `include-domains` is defined, this config is ignored.
        |  --includeTables table1,table2,table3...
        |                           Schemas to include during extraction.
        |  --excludeTables table1,table2,table3...
        |                           Schemas to exclude during extraction. if `include-schemas` is defined, this config is ignored.
        |""".stripMargin
    println(expected)
    rendered.substring(rendered.indexOf("Usage:")).replaceAll("\\s", "") shouldEqual expected
      .replaceAll("\\s", "")
  }

  "ExtractSchema Config" should "work" in {
    val rendered = ExtractSchemaConfig.usage()
    println(rendered)
    val expected =
      """
        |Usage: starlake extract-schema [options]
        |  --mapping <value>     Deprecated. Use config instead
        |  --config <value>        Database tables & connection info
        |  --output-dir <value>    Where to output YML files
        |""".stripMargin
    rendered.substring(rendered.indexOf("Usage:")).replaceAll("\\s", "") shouldEqual expected
      .replaceAll("\\s", "")
  }
}
