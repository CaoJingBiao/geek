package org.seekloud.geek.client.component

import com.jfoenix.animation.alert.JFXAlertAnimation
import com.jfoenix.controls.{JFXAlert, JFXButton, JFXDialogLayout}
import javafx.scene.control.Label
import javafx.stage.{Modality, Stage}

/**
  * User: hewro
  * Date: 2020/2/28
  * Time: 14:11
  * Description: 确认对话框
  */
case class ConfirmDialog(
  stage: Stage,
  title: String,
  content:String,
  confirmAction:()=>Unit,
  refuseAction: ()=>Unit
){
  def show() = {

    val layout = new JFXDialogLayout

    layout.setHeading(new Label(title))
    layout.setBody(new Label(content))

    val alert = new JFXAlert[Void](stage)
    alert.setOverlayClose(true)
    alert.setAnimation(JFXAlertAnimation.CENTER_ANIMATION)
    alert.setContent(layout)
    alert.initModality(Modality.NONE)


    val confirmButton = new JFXButton("同意")
    val refuseButton = new JFXButton("拒绝")

    confirmButton.getStyleClass.add("dialog-accept")
    refuseButton.getStyleClass.add("dialog-accept")

    confirmButton.setOnAction(_ => {
      confirmAction()
      alert.hideWithAnimation()})
    refuseButton.setOnAction(_ => {
      refuseAction()
      alert.hideWithAnimation()
    })
    layout.setActions(confirmButton, refuseButton)

    alert.setContent(layout)
    alert.show()
  }

}