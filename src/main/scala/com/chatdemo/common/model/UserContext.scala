package com.chatdemo.common.model

case class UserContext(isPremium: String, deviceId: String, signedInId: Option[String]) {
  def effectiveId: String = signedInId.getOrElse(deviceId)
}
