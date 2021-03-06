package org.seekloud.geek.client.core.collector

import java.util.concurrent.{ScheduledFuture, ScheduledThreadPoolExecutor, TimeUnit}

import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import javafx.scene.canvas.GraphicsContext
import javafx.scene.image.Image
import org.seekloud.geek.capture.core.CaptureManager
import org.seekloud.geek.capture.protocol.Messages
import org.seekloud.geek.capture.protocol.Messages._
import org.seekloud.geek.capture.sdk.MediaCapture
import org.seekloud.geek.client.Boot
import org.seekloud.geek.client.common.Constants
import org.seekloud.geek.client.core.RmManager
import org.seekloud.geek.client.core.stream.LiveManager.ChangeCaptureOption
import org.seekloud.geek.player.util.GCUtil
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * User: TangYaruo
  * Date: 2019/9/4
  * Time: 14:04
  * Description: rmManager -->> CaptureActor -->> captureManager
  */
object ClientCaptureActor {

  private val log = LoggerFactory.getLogger(this.getClass)

  type CaptureCommand = ReplyToCommand

  final case class StartEncode(rtmp: String) extends CaptureCommand

  final case class StopEncode(encoderType: EncoderType.Value) extends CaptureCommand

  final case object StopCapture extends CaptureCommand

  final case class GetMediaCapture(mediaCapture: MediaCapture) extends CaptureCommand

  /*drawer*/
  trait DrawCommand

  final case class DrawImage(image: Image) extends DrawCommand

  final case object PauseImage extends DrawCommand
  //切换
  final case class SwitchMode(isJoin: Boolean, reset: () => Unit) extends DrawCommand with CaptureCommand

  //重置是否需要声音
  final case class ReSet(isNeedImage: Boolean, noImgReset: () => Unit) extends DrawCommand

  final case object StopDraw extends DrawCommand

  private object ENCODE_RETRY_TIMER_KEY


  def create(frameRate: Int, gc: GraphicsContext, isJoin: Boolean, callBackFunc: Option[() => Unit] = None): Behavior[CaptureCommand] =
    Behaviors.setup[CaptureCommand] { ctx =>
      log.info("CaptureActor is starting...")
      Behaviors.withTimers[CaptureCommand] { implicit timer =>
        idle(frameRate, gc, isJoin, callBackFunc)
      }
    }

  private def idle(
    frameRate: Int,
    gc: GraphicsContext,
    isJoin: Boolean = false,
    callBackFunc: Option[() => Unit] = None,
    resetFunc: Option[() => Unit] = None,
    mediaCapture: Option[MediaCapture] = None,
    reqActor: Option[ActorRef[Messages.ReqCommand]] = None,
    loopExecutor: Option[ScheduledThreadPoolExecutor] = None,
    imageLoop: Option[ScheduledFuture[_]] = None,
    drawActor: Option[ActorRef[DrawCommand]] = None
  )(
    implicit timer: TimerScheduler[CaptureCommand]
  ): Behavior[CaptureCommand] =
    Behaviors.receive[CaptureCommand] { (ctx, msg) =>
      msg match {
        case msg: GetMediaCapture =>
          idle(frameRate, gc, isJoin, callBackFunc, resetFunc, Some(msg.mediaCapture), reqActor, loopExecutor, imageLoop, drawActor)

        case msg: CaptureStartSuccess =>
          log.info(s"MediaCapture start success!")
          CaptureManager.setLatestFrame()
          val drawActor = ctx.spawn(drawer(gc, isJoin), s"CaptureDrawer-${System.currentTimeMillis()}")
          val executor = new ScheduledThreadPoolExecutor(1)
          val askImageLoop = executor.scheduleAtFixedRate(
            () => {
              msg.manager ! Messages.AskImage
            },
            0,
            ((1000.0 / frameRate) * 1000).toLong,
            TimeUnit.MICROSECONDS
          )
          callBackFunc.foreach(func => func())
          idle(frameRate, gc, isJoin, callBackFunc, resetFunc, mediaCapture, Some(msg.manager), Some(executor), Some(askImageLoop), Some(drawActor))

        case msg: CannotAccessSound =>
          log.info(s"Sound unavailable.")
          Behaviors.same

        case msg: CannotAccessImage =>
          log.info(s"Image unavailable.")
          Behaviors.same

        case CaptureStartFailed =>
          log.info(s"Media capture start failed. Review your settings.")
          Behaviors.same

        case msg: ChangeCaptureOption =>
          //只处理图片就可以了，声音由player关闭就可以了
          drawActor.foreach(_!ReSet(msg.needImage,
            ()=>{//不需要图片的时候的摄像头的位置的图像处理
              val user = RmManager.roomInfo.get.userList.find(_.userId == RmManager.userInfo.get.userId).get
              if (RmManager.isStart){
                log.info("清理画布1")
                GCUtil.draw(gc,new Image(Constants.getAvatarSrc(user.headImgUrl)),user.position,center = true,isNeedClear = true)
              }else{
                //没有开启会议则画满整个屏幕
                GCUtil.draw(gc,new Image(Constants.getAvatarSrc(user.headImgUrl)),-1,center = true,isNeedClear = true)

              }
            }))

          Behaviors.same

        case ManagerStopped =>
          log.info(s"Capture Manager stopped.")
          if (resetFunc.nonEmpty) {
            resetFunc.foreach(func => func())
            mediaCapture.foreach(_.start())
          }
          idle(frameRate, gc, isJoin, callBackFunc, None, mediaCapture, reqActor, loopExecutor, imageLoop, drawActor)

        case StreamCannotBeEncoded =>
          log.info(s"Stream cannot be encoded to mpegts.")
          Behaviors.same

        case CannotSaveToFile =>
          log.info(s"Stream cannot be save to file.")
          Behaviors.same

        case msg: ImageRsp =>
          drawActor.foreach(_ ! DrawImage(msg.latestImage.image))
          Behaviors.same

        case msg: SoundRsp => //no need yet
          Behaviors.same

        case NoImage =>
//          log.info(s"No images yet, try later.")
          Behaviors.same

        case NoSamples =>
          log.info(s"No sound yet, try later.")
          Behaviors.same

        case msg: StartEncode =>
          log.info("StartEncode")
//          msg.output match {
//            case Right(outputStream) =>
//              if (reqActor.nonEmpty) {
//                reqActor.foreach(_ ! StartEncodeStream(outputStream))
//              } else {
//                timer.startSingleTimer(ENCODE_RETRY_TIMER_KEY, msg, 500.millis)
//              }
//            case Left(file) =>
//              if (reqActor.nonEmpty) {
//                reqActor.foreach(_ ! StartEncodeFile(file))
//              } else {
//                timer.startSingleTimer(ENCODE_RETRY_TIMER_KEY, msg, 500.millis)
//              }
//          }
          if (reqActor.nonEmpty) {
            reqActor.foreach(_ ! StartEncodeRtmp(msg.rtmp))
          } else {
            timer.startSingleTimer(ENCODE_RETRY_TIMER_KEY, msg, 500.millis)
          }

          Behaviors.same

        case msg: StopEncode =>
          log.info("StopEncode")
//          msg.encoderType match {
//            case EncoderType.STREAM => reqActor.foreach(_ ! StopEncodeStream)
//            case EncoderType.FILE => reqActor.foreach(_ ! StopEncodeFile)
//          }

          reqActor.foreach(_ ! StopEncodeRtmp)
          Behaviors.same

        case msg: SwitchMode =>
          log.info("SwitchMode")
          drawActor.foreach(t =>t ! msg)
          //todo 关掉摄像头的drawer
//          drawActor.foreach(_ ! StopDraw)
          Behaviors.same

        case StopCapture =>
          log.info(s"Media capture is stopping...")
          imageLoop.foreach(_.cancel(false))
          loopExecutor.foreach(_.shutdown())
          reqActor.foreach(_ ! StopMediaCapture)
          drawActor.foreach(_ ! StopDraw)
          Behaviors.stopped

        case x =>
          log.warn(s"unknown msg in idle: $x")
          Behaviors.unhandled
      }
    }


  private def drawer(
    gc: GraphicsContext,
    isJoin: Boolean,
    needImage: Boolean = true
  ): Behavior[DrawCommand] =
    Behaviors.receive[DrawCommand] { (ctx, msg) =>
      msg match {
        case msg: DrawImage =>

//          log.info(s"needImage: $needImage,RmManager.isStart: ${RmManager.isStart}")
          if (needImage) {
            if (!RmManager.isStart){
              GCUtil.draw(gc,msg.image,-1)
            }else{
              //开启会议的时候，根据自由模式还是发言模式和用户身份，决定当前用户画在什么位置上
              Boot.addToPlatform {
                val position = RmManager.roomInfo.get.userList.find(_.userId == RmManager.userInfo.get.userId).get.position
                GCUtil.draw(gc,msg.image,position)
              }
            }
          }


          Behaviors.same


        case msg: SwitchMode =>
          log.debug(s"Capture Drawer switch mode.")
          CaptureManager.setLatestFrame()
          Boot.addToPlatform (msg.reset())
//          Behaviors.same
          drawer(gc, msg.isJoin, needImage)


        case msg: ReSet =>
          log.info("drawer reset")
          if (!msg.isNeedImage){
            Boot.addToPlatform{
              msg.noImgReset()
            }
          }
          drawer(gc, isJoin, msg.isNeedImage)

        case StopDraw =>
          log.info(s"Capture Drawer stopped.")
          Behaviors.stopped

        case x =>
          log.warn(s"unknown msg in drawer: $x")
          Behaviors.unhandled
      }
    }



}
