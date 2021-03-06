package com.votors.umls

import scala.collection.JavaConversions._
import java.io.{FileReader, FileWriter, PrintStream, PrintWriter}
import java.util.concurrent.atomic.AtomicInteger
import java.io._
import java.util

import com.votors.common.{Conf, TimeX}
import com.votors.common.Utils.Trace._
import com.votors.common.Utils._
import com.votors.ml.{Nlp, StanfordNLP}
import edu.stanford.nlp.util.IntPair
import gov.nih.nlm.nls.metamap.AcronymsAbbrevs
import gov.nih.nlm.nls.metamap.MetaMapApi
import gov.nih.nlm.nls.metamap.MetaMapApiImpl
import gov.nih.nlm.nls.metamap.Result

case class MMResult(cui:String, score:Int,orgStr:String,cuiStr:String,pfName:String, sent:String) {
  val sourceSet = new util.HashSet[String]
  val stySet = new util.HashSet[String]
  val span = new IntPair(-1,-1)
  var neg = -1
  var sentId = 0
  var termId = 0
  var matchType = 0; //match with our result: 1=same cui; 2= same orgStr; 3=1+2
  val matchDesc = new StringBuilder
  def shortDesc = {
    val sb: StringBuilder = new StringBuilder
    sb.append(cui + "|"
      + orgStr + "|"
      + score)
    sb.toString()
  }
  override def toString = {
    val sb: StringBuilder = new StringBuilder
    sb.append(cui + "|"
      + orgStr + "|"
      + cuiStr + "|"
      + score + "|"
      + span + "|"
      + stySet.mkString(" ") + "|"
      + sourceSet.mkString(" ") + "|"
      + sent)
    sb.toString()
  }
}

/**
  * Created by Jason on 2016/11/30 0030.
  */
object MMApi {
  var api: MetaMapApi = null

  /**
    * given a string (sentence), return the result from Metamap.
  */
  def process(terms: String, sentId:Int=0): Seq[MMResult] = {
    if (!Conf.MMenable) return Seq()
    init()
    // the character \031 will cause metamap dead.
    val resultList: util.List[Result] = api.processCitationsFromString(terms.replaceAll("[^\\p{Graph}\\x20\\t\\r\\n]",""))
    val mmRets = new util.ArrayList[MMResult]()
    for (result <- resultList) {
      /** write result as: cui|score|semtypes|sources|utterance */
      for (utterance <- result.getUtteranceList) {
        for (pcm <- utterance.getPCMList) {
          for (map <- pcm.getMappingList) {
            var termId = 0
            for (mapEv <- map.getEvList) {
              val mmRet = MMResult(mapEv.getConceptId, math.abs(mapEv.getScore), mapEv.getMatchedWords.mkString(" "), mapEv.getConceptName, mapEv.getPreferredName, terms)
              mmRet.sentId = sentId
              val sb: StringBuilder = new StringBuilder
              mmRet.sourceSet.addAll(mapEv.getSources.filter(sab => sab.matches(Conf.sabFilter)))
              mmRet.stySet.addAll(mapEv.getSemanticTypes.map(SemanticType.mapAbbr2sty.getOrElse(_,"None")).filter(sty => Conf.semanticType.indexOf(sty) >= 0))
              if (mmRet.sourceSet.size > 0
                && mmRet.stySet.size > 0
                && mmRet.score >= Conf.MMscoreThreshold
                && !Nlp.checkStopword(mmRet.orgStr,true)
                && !mmRet.orgStr.matches(Conf.cuiStringFilterRegex)
                && !mmRets.exists(mm=>mm.cui.equals(mmRet.cui) && mm.orgStr.equals(mmRet.orgStr) && mm.score==mmRet.score)
                //&& !mmRets.exists(mm=>mm.orgStr.toLowerCase.contains(mmRet.orgStr.toLowerCase))  // not exactly what we mean 'overlap'.
               ) {
                mmRets.add(mmRet)
                for (p <- mapEv.getPositionalInfo) {
                  if (mmRet.span.get(0) == -1 || p.getX < mmRet.span.get(0)) mmRet.span.set(0, p.getX)
                  if (mmRet.span.get(1) == -1 || p.getX + p.getY > mmRet.span.get(1)) mmRet.span.set(1, p.getX + p.getY)
                }
                mmRet.neg = mapEv.getNegationStatus
                termId += 1
                mmRet.termId = termId
                println(mmRet.toString)
              } else {
                println(s"filter by sty:${mmRet.stySet.size}, sab:${mmRet.sourceSet.size}, ${mmRet.score}, ${mmRet.cui}, ${mmRet.orgStr}, or already exists.")
              }
            }
          }
        }
      }
    }
    return mmRets.to[Seq]
  }

  private def init():Unit = {
    if (api != null) return
    api = new MetaMapApiImpl
    if (Conf.MMhost.trim.size > 0)api.setHost(Conf.MMhost)
    if (Conf.MMport.trim.size > 0)api.setPort(Conf.MMport.toInt)
    val options: String = Conf.MMoptions
    api.setOptions(options)
  }

  def main(args: Array[String]) {
    init()
    var startTime = System.currentTimeMillis()
    process("People who don\u0019t smoke but who breathe the smoke of others also have a higher risk of lung cancer.")
    println(System.currentTimeMillis() - startTime)
    startTime = System.currentTimeMillis()
    process("People who don\u0019t smoke but who breathe the smoke of others also have a higher risk of lung cancer.")
    println(System.currentTimeMillis() - startTime)
    startTime = System.currentTimeMillis()
    process("My focus is on the code in word2vec.c for training the skip-gram architecture with negative sampling, so for now I have ignored the CBOW and Hierarchical Softmax code. I also haven't looked much at the testing code.\n\nBecause the code supports both models and both training approaches, I highly recommended viewing the code in an editor which allows you to collapse code blocks. The training code is much more readable when you hide the implementations that you aren't interested in..")
    println(System.currentTimeMillis() - startTime)
  }
}
