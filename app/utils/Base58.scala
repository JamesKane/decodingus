package utils

object Base58 {
  private val Alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
  private val Base = BigInt(58)
  private val CharIndex: Map[Char, Int] = Alphabet.zipWithIndex.toMap

  def decode(input: String): Array[Byte] = {
    if (input.isEmpty) return Array.empty[Byte]

    var bi = BigInt(0)
    for (ch <- input) {
      bi = bi * Base + CharIndex.getOrElse(ch,
        throw new IllegalArgumentException(s"Invalid base58 character: $ch"))
    }

    val bytes = if (bi == BigInt(0)) Array.empty[Byte] else {
      val raw = bi.toByteArray
      if (raw.head == 0) raw.tail else raw
    }

    val leadingZeros = input.takeWhile(_ == '1').length
    Array.fill(leadingZeros)(0.toByte) ++ bytes
  }

  def encode(input: Array[Byte]): String = {
    if (input.isEmpty) return ""

    var bi = BigInt(1, input)
    val sb = new StringBuilder
    while (bi > 0) {
      val (div, mod) = bi /% Base
      sb.append(Alphabet(mod.toInt))
      bi = div
    }

    val leadingZeros = input.takeWhile(_ == 0).length
    ("1" * leadingZeros) + sb.reverse.toString()
  }
}
