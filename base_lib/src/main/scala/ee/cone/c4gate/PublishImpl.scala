package ee.cone.c4gate

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Single, c4assemble}
import ee.cone.c4gate.HttpProtocol.{N_Header, S_HttpPublication}
import ee.cone.c4di.c4
import ee.cone.c4proto.ToByteString
import okio.ByteString
import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.jdk.CollectionConverters.IteratorHasAsScala

//todo un-publish

import scala.jdk.CollectionConverters.IterableHasAsScala

sealed trait LastPublishState extends Product
case object LastPublishStateKey extends TransientLens[LastPublishState](NotCheckedLastPublishState)
case object NotCheckedLastPublishState extends LastPublishState
case object NoFileLastPublishState extends LastPublishState
case class FileLastPublishState(content: ByteString) extends LastPublishState

@c4assemble("PublishingCompApp") class PublishingAssembleBase(publishing: Publishing){
  def join(
    srcId: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId,TxTransform)] =
    List(WithPK(PublishingTx("PublishingTx")(publishing)))
}

case class PublishingTx(srcId: SrcId)(publishing: Publishing) extends TxTransform {
  def transform(local: Context): Context = publishing.transform(local)
}

trait PublicDirProvider {
  def get: List[(String,Path)]
}

@c4("PublishingCompApp") class ModPublicDirProvider(config: ListConfig) extends PublicDirProvider {
  def get: List[(String,Path)] = {
    val Mod = """.+/mod\.([^/]+)\.(classes|jar)""".r
    val hasMod = (for {
      classpath <- config.get("CLASSPATH")
      Mod(m,_) <- classpath.split(":")
    } yield m).toSet
    // println(s"hasMod: $hasMod")
    val Line = """(\S+)\s+(\S+)\s+(\S+)""".r
    for {
      mainPublicPath <- "htdocs" :: config.get("C4GENERATOR_MAIN_PUBLIC_PATH")
      fName <- Option(Paths.get(mainPublicPath).resolve("c4gen.ht.links")).toList if Files.exists(fName)
      Line(mod,url,pf) <- Files.readAllLines(fName).asScala.toList if hasMod(mod)
    } yield (url,Paths.get(s"$mainPublicPath/$pf"))
  }
}

@c4("PublishingCompApp") class Publishing(
  idGenUtil: IdGenUtil,
  publishFromStringsProviders: List[PublishFromStringsProvider],
  mimeTypesProviders: List[PublishMimeTypesProvider],
  publicDirProviders: List[PublicDirProvider],
  publishFullCompressor: PublishFullCompressor,
)(
  mimeTypes: String=>Option[String] = mimeTypesProviders.flatMap(_.get).toMap.get,
  compressor: Compressor = publishFullCompressor.value
) extends LazyLogging {
  def transform(local: Context): Context = { //Seq[Observer[RichContext]]
    val fromPath = Paths.get("htdocs")
    val publishState =
      Option(fromPath.resolve("publish_time")).filter(Files.exists(_))
      .map(path=>FileLastPublishState(ToByteString(Files.readAllBytes(path))))
      .getOrElse(NoFileLastPublishState)
    if(LastPublishStateKey.of(local) == publishState)
      SleepUntilKey.set(Instant.ofEpochMilli(System.currentTimeMillis+1000))(local)
    else {
      logger.debug("publish started")
      val strEvents = for {
        publishFromStringsProvider <- publishFromStringsProviders
        (path,body) <- publishFromStringsProvider.get
        event <- publish(path,body.getBytes(UTF_8))(local)
      } yield event
      val fileEvents = for {
        publicDirProvider <- publicDirProviders
        (url,file) <- publicDirProvider.get
        event <- publish(url,Files.readAllBytes(file))(local)
      } yield event
      logger.debug("publish finishing")
      TxAdd(strEvents ++ fileEvents).andThen(LastPublishStateKey.set(publishState))(local)
    }
  }
  def publish(path: String, body: Array[Byte]): Context=>Seq[LEvent[Product]] = local => {
    //println(s"path: $path")
    val pointPos = path.lastIndexOf(".")
    val ext = if(pointPos<0) "" else path.substring(pointPos+1)
    val byteString = compressor.compress(ToByteString(body))
    val mimeType = mimeTypes(ext)
    val eTag = "v1-" +
      idGenUtil.srcIdFromSerialized(0,byteString) +
      idGenUtil.srcIdFromSerialized(0,ToByteString(s"${mimeType.getOrElse("")}:"))
    val headers =
      N_Header("etag", s""""$eTag"""") ::
      N_Header("content-encoding", compressor.name) ::
      mimeType.map(N_Header("content-type",_)).toList
    val publication = S_HttpPublication(path,headers,byteString,None)
    val existingPublications = ByPK(classOf[S_HttpPublication]).of(local)
    //println(s"${existingPublications.getOrElse(path,Nil).size}")
    if(existingPublications.get(path).contains(publication)) {
      logger.debug(s"$path (${byteString.size}) exists")
      Nil
    } else {
      logger.debug(s"$path (${byteString.size}) published")
      LEvent.update(publication)
    }
  }
}