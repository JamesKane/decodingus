package utils

import models.domain.support.MessageStatus

object CuratorViewUtils {
  def actionBadgeClass(action: String): String = {
    action match {
      case "create" => "bg-success"
      case "update" => "bg-warning text-dark"
      case "delete" => "bg-danger"
      case _ => "bg-secondary"
    }
  }

  def changeTypeBadgeClass(changeType: String): String = {
    changeType match {
      case "add" => "bg-success"
      case "remove" => "bg-danger"
      case "update" => "bg-warning text-dark"
      case _ => "bg-secondary"
    }
  }

  def statusBadgeClass(status: MessageStatus): String = {
    status match {
      case MessageStatus.New => "bg-primary"
      case MessageStatus.Read => "bg-info"
      case MessageStatus.Replied => "bg-success"
      case MessageStatus.Closed => "bg-secondary"
    }
  }
}
