package com.chatdemo.common.service

import com.chatdemo.common.model.MessageAttachment

/**
 * Result returned after a chat exchange completes.
 */
case class ChatResult(responseAttachments: List[MessageAttachment], error: String)
