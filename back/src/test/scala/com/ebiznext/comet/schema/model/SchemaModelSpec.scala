package com.ebiznext.comet.schema.model

import com.ebiznext.comet.sample.SampleData
import com.ebiznext.comet.schema.model.SchemaModel.Domain
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.scalatest._

case class XMode(mode: SchemaModel.Mode)

class SchemaModelSpec extends FlatSpec with Matchers with SampleData {
  val mapper: ObjectMapper = new ObjectMapper(new YAMLFactory())
  // provides all of the Scala goodiness
  mapper.registerModule(DefaultScalaModule)

  "Case Object" should "serialize as a simple string" in {
    println(mapper.writeValueAsString(domain))
    assert(1 == 1)
  }

//  "json case object" should "deserialize as case olass" in {
//    val jsdomain = mapper.readValue(domainStr, classOf[Domain])
//    assert(jsdomain == domain)
//  }

}