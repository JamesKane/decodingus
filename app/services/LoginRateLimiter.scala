package services

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton

/**
 * Simple in-memory rate limiter for login attempts.
 * Tracks failed attempts per IP and blocks after threshold.
 */
@Singleton
class LoginRateLimiter {

  private val MaxAttempts = 10
  private val WindowSeconds = 900L // 15 minutes
  private val attempts = new ConcurrentHashMap[String, (Int, Instant)]()

  /** Returns true if the IP is allowed to attempt login. */
  def isAllowed(ip: String): Boolean = {
    pruneExpired()
    val entry = attempts.get(ip)
    entry == null || entry._1 < MaxAttempts
  }

  /** Record a failed login attempt for the given IP. */
  def recordFailure(ip: String): Unit = {
    attempts.compute(ip, (_, existing) => {
      if (existing == null || existing._2.plusSeconds(WindowSeconds).isBefore(Instant.now())) {
        (1, Instant.now())
      } else {
        (existing._1 + 1, existing._2)
      }
    })
  }

  /** Clear attempts for an IP on successful login. */
  def recordSuccess(ip: String): Unit = {
    attempts.remove(ip)
  }

  private def pruneExpired(): Unit = {
    val cutoff = Instant.now().minusSeconds(WindowSeconds)
    attempts.entrySet().removeIf(e => e.getValue._2.isBefore(cutoff))
  }
}
