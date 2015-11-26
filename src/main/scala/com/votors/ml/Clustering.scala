package com.votors.ml

import java.util
import java.util.concurrent.atomic.AtomicInteger

import com.votors.aqi.Train
import com.votors.common.SqlUtils
import org.apache.log4j.{Level, Logger}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.sql.SQLContext
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.rdd.RDD

import scala.collection.mutable.ArrayBuffer
import java.io._
import java.nio.charset.CodingErrorAction
import java.util.regex.Pattern
import java.util.{Date, Properties}

import opennlp.tools.sentdetect.{SentenceDetectorME, SentenceModel}

import scala.collection.JavaConversions.asScalaIterator
import scala.collection.immutable.{List, Range}
import scala.collection.mutable
import scala.collection.mutable.{ListBuffer, ArrayBuffer}
import scala.io.Source
import scala.io.Codec

import org.apache.commons.lang3.StringUtils
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.snowball.SnowballFilter
import org.apache.lucene.util.Version
import org.apache.solr.client.solrj.SolrRequest
import org.apache.solr.client.solrj.impl.HttpSolrServer
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest
import org.apache.solr.common.SolrDocumentList
import org.apache.solr.common.params.CommonParams
import org.apache.solr.common.params.ModifiableSolrParams
import org.apache.solr.common.util.ContentStreamBase
import com.votors.common.Utils._
import com.votors.common.Utils.Trace._

import opennlp.tools.cmdline.BasicCmdLineTool
import opennlp.tools.cmdline.CLI
import opennlp.tools.cmdline.PerformanceMonitor
import opennlp.tools.postag.POSModel
import opennlp.tools.postag.POSSample
import opennlp.tools.postag.POSTaggerME
import opennlp.tools.stemmer.PorterStemmer
import opennlp.tools.stemmer.snowball.englishStemmer
import opennlp.tools.tokenize.WhitespaceTokenizer
import opennlp.tools.util.ObjectStream
import opennlp.tools.util.PlainTextByLineStream
import java.sql.{Statement, Connection, DriverManager, ResultSet}
import org.apache.spark.mllib.linalg.{Vector, Vectors}

import org.apache.spark.mllib.clustering._

import com.votors.common._


/**
 * Created by Jason on 2015/11/9 0009.
 */

class Clustering (sc: SparkContext) {
  val dbUrl = Conf.prop.get("blogDbUrl").toString
  val blogTbl = Conf.prop.get("blogTbl").toString
  val blogIdCol = Conf.prop.get("blogIdCol").toString
  val blogTextCol = Conf.prop.get("blogTextCol").toString
  val blogLimit = Conf.prop.get("blogLimit").toString.toInt
  var docsNum = 0L

  if (dbUrl.length==0 || blogTbl.length==0 || blogIdCol.length==0 || blogTextCol.length==0) {
    trace(ERROR, "Some database config not exist.")
    sys.exit(1)
  }

  def getBlogIdRdd(parallelism: Int): RDD[Int] = {
  val limit = blogLimit
  val sqlUtil = new SqlUtils(dbUrl.toString)
    val ret = sqlUtil.execQuery(s"select ${blogIdCol.toString} as blogId from ${blogTbl.toString} limit ${limit}")
    val blogIds = new ArrayBuffer[Int]()
    while (ret.next()) blogIds.append(ret.getInt(1))
    sqlUtil.jdbcClose()
    docsNum = blogIds.size
    sc.parallelize(blogIds, parallelism)
  }

  def getBlogTextRdd(rdd: RDD[Int]): RDD[(Int, String)] = {
    val url = dbUrl
    val textCol = blogTextCol
    val tbl = blogTbl
    val idCol = blogIdCol
    rdd.mapPartitions(iter=> {
      println(s"getBlogTextRdd ***")
      val sqlUtil = new SqlUtils(url)
//      val sqlUtil = new SqlUtils("jdbc:mysql://localhost:3306/ytex?user=root&password=root")
      val texts = for (id <- iter) yield {
        val ret = sqlUtil.execQuery(s"select ${textCol} as blogText from ${tbl} where ${idCol}=${id} limit 1")
//        val ret = sqlUtil.execQuery(s"select chosenanswer as blogText from tmp_org_yahoo limit 1")
        ret.next()
        (id, ret.getString(1))
      }
      sqlUtil.jdbcClose()
      texts
    }).map(text=>Nlp.textPreprocess(text._1,text._2))
  }

  def getSentRdd(textRdd: RDD[(Int, String)])  = {
    val ret = textRdd.mapPartitions(itr => {
      println(s"getSentRdd ***")
      val sents = for (blog <- itr) yield Nlp.generateSentence(blog._1,blog._2,null)
      sents
    })
    ret
  }

  def getNgramRdd(sentRdd: RDD[Array[Sentence]], tfFilterInPartition:Int=3, firstStageNgram: Broadcast[mutable.HashMap[String, Ngram]]=null): RDD[Ngram]= {
    val ret = sentRdd.mapPartitions(itr => {
      println(s"getNgramRdd ***")
      val hNgrams = new mutable.LinkedHashMap[String,Ngram]()
      val gramId = new AtomicInteger()
      itr.foreach(sents => {
        gramId.set(0)
        if (firstStageNgram == null) {
          Nlp.generateNgram(sents, gramId, hNgrams)
        }else{
          Nlp.generateNgramStage2(sents,gramId,hNgrams,firstStageNgram)
        }
      })
      val sNgrams = hNgrams.values.toSeq.filter(_.tfAll>tfFilterInPartition)
      trace(INFO,s"number of ngram after filter > ${tfFilterInPartition} is ${sNgrams.size}")
      sNgrams.foreach(_.getNestInfo(sNgrams))
      sNgrams.iterator
    })
    ret
  }

  /**
   *
   * @param rddNgram
   * @return
   */
  def getVectorRdd(rddNgram: RDD[Ngram]): RDD[(Ngram,Vector)] = {
    rddNgram.map(gram => {
      val feature = new ArrayBuffer[Double]()
      feature.append(gram.tfdf)     // tfdf
      feature.append(log2(gram.cvalue+1)) // c-value, applied a log function
      feature.append(gram.umlsScore._1/100) // simple similarity to umls
      feature.append(gram.umlsScore._2/100) //simple similarity to chv
      feature.append(bool2Double(gram.isPosNN))
      feature.append(bool2Double(gram.isPosAN))
      feature.append(bool2Double(gram.isPosPN))
      feature.append(bool2Double(gram.isPosANPN))
      feature.append(bool2Double(gram.isContainInUmls))
      feature.append(bool2Double(gram.isContainInChv))
      feature.append(log2(gram.context.win_umlsCnt+1))
      feature.append(log2(gram.context.win_chvCnt+1))
      feature.append(log2(gram.context.sent_umlsCnt+1))
      feature.append(log2(gram.context.sent_chvCnt+1))
      feature.append(log2(gram.context.umlsDist+1))
      feature.append(log2(gram.context.chvDist+1))

      (gram,Vectors.dense(feature.toArray))
    })
  }
}

object Clustering {
  def main (args: Array[String]): Unit = {

//    if (args.length <= 2){
//      println("You should input option: original-file aqi-file!")
//      return
//    }

    // init spark
    val startTime = new Date()
    val sc = new SparkContext(new SparkConf()
      .setAppName("NLP")
      .setMaster("local")
    )

    val rootLogger = Logger.getRootLogger();
    rootLogger.setLevel(Level.WARN);

    // printf more debug info of the gram that match the filter
    Trace.filter = "type 1 aaaa"


    //val sqlContext = new SQLContext(sc)
    val clustering = new Clustering(sc)
    val rdd = clustering.getBlogIdRdd(2)
    val docsNum = rdd.count()
    val rddText = clustering.getBlogTextRdd(rdd)
    val rddSent = clustering.getSentRdd(rddText).persist()
    val docNumber = clustering.docsNum
    val rddNgram = clustering.getNgramRdd(rddSent,Conf.partitionTfFilter)
      .map(gram=>(gram.text, gram))
      .reduceByKey(_+_)
      .sortByKey()
      .map(_._2)
      .filter(_.tfAll>Conf.stag1TfFilter)
      .mapPartitions(itr =>Ngram.updateAfterReduce(itr,docNumber))
      .filter(_.cvalue > Conf.stag1CvalueFilter)
      //.persist()

    //rddNgram.foreach(gram => println(f"${gram.tfdf}%.2f\t${log2(gram.cvalue+1)}%.2f\t${gram}"))
    //println(s"number of gram is ${rddNgram.count}")

    val firstStageRet = new mutable.HashMap[String,Ngram]()
    if (Conf.topTfNgram>0)
      firstStageRet ++= rddNgram.sortBy(_.tfAll * -1).take(Conf.topTfNgram).map(gram=>(gram.text,gram))
    else if (Conf.topTfdfNgram>0)
      firstStageRet ++= rddNgram.sortBy(_.tfdf * -1).take(Conf.topTfdfNgram).map(gram=>(gram.text,gram))
    else if (Conf.topCvalueNgram>0)
      firstStageRet ++= rddNgram.sortBy(_.cvalue * -1).take(Conf.topCvalueNgram).map(gram=>(gram.text,gram))
    else
      firstStageRet ++= rddNgram.collect().map(gram=>(gram.text,gram))

    //firstStageRet.foreach(println)
    val firstStageNgram = sc.broadcast(firstStageRet)

    val rddNgram2 = clustering.getNgramRdd(rddSent,0,firstStageNgram)
      .map(gram=>(gram.text, gram))
      .reduceByKey(_+_)
      .sortByKey()
      .map(_._2)
      .filter(_.tfAll>Conf.stag1TfFilter)
      .mapPartitions(itr =>Ngram.updateAfterReduce(itr,docNumber,true))
      .filter(_.cvalue > Conf.stag2CvalueFilter)
      .persist()

    //writeObjectToFile(Conf.fistStagResultFile, firstStageRet)
    rddNgram2.foreach(gram => println(f"${gram.tfdf}%.2f\t${log2(gram.cvalue+1)}%.2f\t${gram}"))
    println(s"number of gram2 is ${rddNgram2.count}")

    val rddVector = clustering.getVectorRdd(rddNgram2.filter(ngram=>ngram.umlsScore._2 > 0.1)).persist()
    rddVector.foreach(v => println(f"${v._1.text}%-15s\t${v._2.toArray.map(f => f"${f}%.2f").mkString("\t")}"))

    val model = KMeans.train(rddVector.map(_._2),10,10000,10)
    val ret = rddVector.sortBy(kv => kv._1.tfdf * -1).take(200).map(kv => (model.predict(kv._2), kv._1)).groupBy(_._1)
    ret.foreach(kv =>{
      println(s"clust ####${kv._1}#####")
      kv._2.foreach(kkvv => println(kkvv._2))
    })
    //rddSent.collect().take(10).foreach(a => println(a.mkString("\n")))
    //println(rddNgram.collect().take(10).mkString("\n"))
    //println(rddNgram.collect().filter(_.text.equals("diabet")).mkString("\n"))

//    val rddNgramMerged = rddNgram.map(gram => (gram.text,gram)).reduceByKey((a,b) =>a+b).map(_._2)
//    val rddNgramTfdf = rddNgramMerged.map(gram => gram.procTfdf(docsNum))
//
//
//    println(rddNgramTfdf.sortBy(_.tfAll * -1).take(10).mkString("\n"))
//    println(rddNgramTfdf.sortBy(_.tfdf * -1).take(10).mkString("\n"))

//    println(s" rdd ${rdd.count}")
//    println(s" rddText ${rddText.count}")
//    println(s" rddSent ${rddSent.count}")
//    println(s" rddNgram ${rddNgram.count}")
//    println(s" rddNgramMerged ${rddNgramMerged.count}")

    println("*******result is ******************")
    val endTime = new Date()
    System.out.println("### used time: "+(endTime.getTime()-startTime.getTime())+" ###")
  }
}