package ai.starlake.schema.generator

import better.files.File
import ai.starlake.config.{DatasetArea, Settings}
import ai.starlake.schema.model._
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import ai.starlake.utils.YamlSerializer._

object Xls2Yml extends LazyLogging {

  /** Encryption of a data source is done by running a specific ingestion job that aims only to
    * apply Privacy rules on the concerned attributes. To apply the Encryption process on the data
    * sources of a given Domain, we need a corresponding "PreEncryption Domain". The PreEncryption
    * domain contains the same Schemas as the initial Domain but with less constraints on the
    * attributes, which speeds up the encryption process by limiting it to applying the Encryption
    * methods on columns with privacy attributes.
    *
    * @param domain
    */
  def genPreEncryptionDomain(domain: Domain, privacy: Seq[String]): Domain = {
    val preEncryptSchemas: List[Schema] = domain.tables.map { s =>
      val newAttributes =
        s.attributes
          .filter(_.script.isEmpty)
          .map { attr =>
            {
              val attribute = attr.copy(
                `type` = "string",
                required = false,
                rename = None,
                ignore = None,
                comment = None
              )
              if (
                privacy == Nil || privacy.contains(
                  attr.getPrivacy().toString
                )
              )
                attribute
              else
                attr.copy(privacy = PrivacyLevel.None)
            }
          }
      // pre-encryption YML should not contain any partition or sink elements.
      // Write mode is forced to APPEND since encryption output must not overwrite previous results
      val newMetaData: Option[Metadata] = s.metadata.map { m =>
        m.copy(partition = None, sink = None, write = Some(WriteMode.APPEND))
      }
      s.copy(
        attributes = newAttributes,
        metadata = newMetaData,
        merge = None,
        comment = None,
        rls = Nil,
        acl = Nil
      )
    }
    val preEncryptDomain = domain.copy(tables = preEncryptSchemas, comment = None)
    preEncryptDomain
  }

  /** build post encryption Domain => for each Position schema update its Metadata as follows
    *   - Format : DSV
    *   - With Header : False
    *   - Separator : µ
    * @param domain
    */
  def genPostEncryptionDomain(
    domain: Domain,
    delimiter: Option[String],
    encryptionPrivacyList: Seq[String]
  ): Domain = {

    /* Identify if a schema is not concerned by the encryption phase
     * either because none of its attributes has a defined Privacy Level
     * or because all the defined Privacy Levels are not applied during the encrption phase
     * @param s
     * @return true if the schema is not concerned by the encryption phase
     */
    def noPreEncryptPrivacy(s: Schema): Boolean = {
      s.attributes.forall(_.getPrivacy().equals(PrivacyLevel.None)) ||
      (encryptionPrivacyList.nonEmpty && s.attributes.map(_.getPrivacy()).distinct.forall { p =>
        !encryptionPrivacyList.contains(p.toString)
      })
    }
    val postEncryptSchemas: List[Schema] = domain.tables.map { schema =>
      if (noPreEncryptPrivacy(schema))
        schema
      else {
        val metadata = for {
          metadata <- schema.metadata
          format   <- metadata.format
        } yield {
          if (!List(Format.SIMPLE_JSON, Format.DSV, Format.POSITION).contains(format))
            throw new Exception("Not Implemented")
          metadata.copy(
            format = Some(Format.DSV),
            separator = delimiter.orElse(schema.metadata.flatMap(_.separator)).orElse(Some("µ")),
            withHeader = schema.metadata.flatMap(_.withHeader),
            encoding = Some("UTF-8")
          )
        }
        val attributes = schema.attributes.map { attr =>
          if (
            encryptionPrivacyList == Nil || encryptionPrivacyList.contains(
              attr.getPrivacy().toString
            )
          )
            attr.copy(privacy = PrivacyLevel.None)
          else
            attr
        }
        schema.copy(
          metadata = metadata,
          attributes = attributes
        )
      }
    }
    val postEncryptDomain = domain.copy(tables = postEncryptSchemas)
    postEncryptDomain
  }

  def generateSchema(inputPath: String, outputPath: Option[String] = None)(implicit
    settings: Settings
  ): Unit = {
    val reader = new XlsDomainReader(InputPath(inputPath))
    reader.getDomain().foreach { domain =>
      writeDomainYaml(domain, outputPath.getOrElse(DatasetArea.domains.toString), domain.name)
    }
  }

  def writeDomainYaml(domain: Domain, outputPath: String, fileName: String): Unit = {
    logger.info(s"""Generated schemas:
         |${serialize(domain)}""".stripMargin)
    serializeToFile(File(outputPath, s"${fileName}.comet.yml"), domain)
  }

  def writeIamPolicyTagsYaml(
    iamPolicyTags: IamPolicyTags,
    outputPath: String,
    fileName: String
  ): Unit = {
    logger.info(s"""Generated schemas:
         |${serialize(iamPolicyTags)}""".stripMargin)
    serializeToFile(File(outputPath, s"${fileName}.comet.yml"), iamPolicyTags)
  }

  def run(args: Array[String]): Boolean = {
    implicit val settings: Settings = Settings(ConfigFactory.load())
    val defaultOutputPath = DatasetArea.domains.toString
    Xls2YmlConfig.parse(args) match {
      case Some(config) =>
        config.job match {
          case false =>
            if (config.encryption) {
              for {
                file   <- config.files
                domain <- new XlsDomainReader(InputPath(file)).getDomain()
              } yield {
                val preEncrypt = genPreEncryptionDomain(domain, config.privacy)
                writeDomainYaml(
                  preEncrypt,
                  config.outputPath.getOrElse(defaultOutputPath),
                  "pre-encrypt-" + preEncrypt.name
                )
                val postEncrypt = genPostEncryptionDomain(domain, config.delimiter, config.privacy)
                writeDomainYaml(
                  postEncrypt,
                  config.outputPath.getOrElse(defaultOutputPath),
                  "post-encrypt-" + domain.name
                )
              }
            } else {
              config.files.foreach(generateSchema(_, config.outputPath))
            }
          case true =>
            config.files.foreach(
              Xls2YmlAutoJob.generateSchema(_, config.policyFile, config.outputPath)
            )
        }
        config.iamPolicyTagsFile.foreach { iamPolicyTagsPath =>
          val workbook = new XlsIamPolicyTagsReader(InputPath(iamPolicyTagsPath))
          val iamPolicyTags = IamPolicyTags(workbook.iamPolicyTags)
          writeIamPolicyTagsYaml(
            iamPolicyTags,
            config.outputPath.getOrElse(DatasetArea.metadata.toString),
            "iam-policy-tags"
          )
        }
        true
      case _ =>
        println(Xls2YmlConfig.usage())
        false
    }
  }

  def main(args: Array[String]): Unit = {
    val result = Xls2Yml.run(args)
    if (!result) throw new Exception("Xls2Yml failed!")
  }
}

object Main {

  def main(args: Array[String]): Unit = {
    println(
      "[deprecated]: Please use ai.starlake.schema.generator.Xls2Yml instead of ai.starlake.schema.generator.Main"
    )
    Xls2Yml.main(args)
  }
}
