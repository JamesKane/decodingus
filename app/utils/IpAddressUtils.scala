package utils

import java.security.MessageDigest

object IpAddressUtils {
  def hashIpAddress(ip: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(ip.getBytes("UTF-8"))
    hash.map("%02x".format(_)).mkString
  }
}
