package com.chatdemo.common.service

case class ExposedImageModel(
  provider: String,
  modelName: String
)

trait ImageModelAccessPolicy {

  def premiumImageModel: ExposedImageModel

  def freeImageModel: ExposedImageModel
}
