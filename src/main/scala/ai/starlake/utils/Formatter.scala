package ai.starlake.utils

import ai.starlake.config.Settings

import java.util.regex.Pattern
import scala.collection.mutable.ListBuffer
import scala.util.matching.Regex
object Formatter extends Formatter

trait Formatter {

  /** Split a String into a Map
    * @param str
    *   : the string to be splitted
    * @return
    */
  implicit class RichFormatter(str: String) {
    def richFormat(
      activeEnv: Map[String, Any],
      extraEnvVars: Map[String, Any]
    )(implicit settings: Settings): String = {
      if (settings.comet.internal.forall(_.substituteVars))
        (activeEnv ++ extraEnvVars).foldLeft(str) { case (res, (key, value)) =>
          res
            .replaceAll(
              settings.comet.sqlParameterPattern.format(key),
              Regex.quoteReplacement(value.toString)
            ) // new syntax
            .replaceAll(
              "\\{\\{\\s*%s\\s*\\}\\}".format(key),
              Regex.quoteReplacement(value.toString)
            ) // old syntax
        }
      else
        str
    }

    def extractVars()(implicit settings: Settings): Set[String] = {
      val oldPattern = Pattern.compile("\\{\\{\\s*([a-zA-Z_0-9]+)\\s*\\}\\}").matcher(str)
      val newPattern = Pattern.compile("\\$\\{\\s*([a-zA-Z_0-9]+)\\s*\\}").matcher(str)

      val result = ListBuffer[String]()
      while (oldPattern.find())
        result.append(oldPattern.group(1))
      while (newPattern.find())
        result.append(newPattern.group(1))

      result.toSet
    }
  }
}
