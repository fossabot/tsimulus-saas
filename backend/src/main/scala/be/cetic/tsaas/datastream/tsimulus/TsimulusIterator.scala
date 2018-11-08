package be.cetic.tsaas.datastream.tsimulus

import be.cetic.tsaas.datastream.TimedIterator
import be.cetic.tsaas.datastream.tsimulus.TsimulusIterator.{InfiniteSpeed, Realtime, SpeedFactor}
import be.cetic.rtsgen.Utils
import be.cetic.rtsgen.config.Configuration
import be.cetic.tsaas.utils.templates.FreeMarkerParser
import org.joda.time.format.DateTimeFormat

import scala.concurrent.duration.{FiniteDuration, _}

object TsimulusIterator {

  trait Speed

  case class SpeedFactor(factor: Float) extends Speed

  case object InfiniteSpeed extends Speed

  case object Realtime extends Speed

  case class Template(template: String, timeVariable: String, nameVariable: String, valueVariable: String)

  val defaultTemplate = Template("<TIME>;<NAME>;<VALUE>", "<TIME>", "<NAME>", "<VALUE>")


  object TsimulusConfig {
    def apply(config: Configuration, speed: Speed, template: Template): TsimulusConfig = {
      val templateMap = config.series.map(_.name -> template).toMap
      TsimulusConfig(config, speed, templateMap)
    }

    def apply(configuration: Configuration, speed: Speed): TsimulusConfig = {
      val templateMap = configuration.series.map(_.name -> defaultTemplate).toMap
      TsimulusConfig(configuration, speed, templateMap)
    }
  }

  case class TsimulusConfig(config: Configuration, speed: Speed, template: Map[String, Template]) extends TimedIterator.Config {
    override val description: String = s"Tsimulus time series ${config.series.map(_.name).mkString(", ")} with speed factor $speed}"
  }

}

class TsimulusIterator(val config: TsimulusIterator.TsimulusConfig) extends TimedIterator[String] {

  private def makeIterator: Iterator[(Long, String)] = {
    val stream = Utils.generate(Utils.config2Results(config.config))

    stream.map { case (dateTime, name, value) =>
      val pattern = config.template(name)
      val freeMarkerTemplate = new FreeMarkerParser(pattern.template)
      val tValue =  dateTime.toDateTime.getMillis
      val dataModel = Map(pattern.timeVariable ->tValue, pattern.valueVariable->value, pattern.nameVariable->name)


      def result: String = freeMarkerTemplate.process(dataModel)

      (tValue, result)
    }.toIterator
  }

  override val dropBeforeNow: Boolean = config.speed == Realtime

  val iterator: Iterator[(Long, String)] = makeIterator


  override def computeNextDelay(current: Option[(Long, String)], next: Option[(Long, String)]): FiniteDuration = {
    val maybeDelay = if (config.speed == Realtime) {
      val now = System.currentTimeMillis()
      next.map(n => n._1 - now)
    } else {
      next.map(n => n._1 - current.get._1)
    }
    val delay = Seq(maybeDelay.getOrElse(0L), 0L).max millisecond


    config.speed match {
      case SpeedFactor(factor) => (delay.toNanos / factor) nanos
      case InfiniteSpeed => 0 seconds
      case Realtime => delay
    }
  }
}


