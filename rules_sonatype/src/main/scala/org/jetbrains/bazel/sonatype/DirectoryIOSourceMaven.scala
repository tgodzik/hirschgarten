package org.jetbrains.bazel.sonatype

import org.sonatype.spice.zapper.fs.DirectoryIOSource
import org.sonatype.spice.zapper.{Path, ZFile}

import java.io.File
import java.nio.file.{Files, Paths}
import java.util
import java.math.BigInteger
import java.nio.charset.{Charset, StandardCharsets}
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import scala.jdk.CollectionConverters._
import java.io.IOException
import java.io.BufferedWriter
import java.io.OutputStreamWriter

class DirectoryIOSourceMaven(filesPaths: List[Path]) extends DirectoryIOSource(new File("").getCanonicalFile) {

  def processFiles(filesPaths: List[Path]): List[Path] =
    filesPaths
      .map(file => Paths.get(file.stringValue()))
      .flatMap(file => {
        val toHash = Files.readAllBytes(file)
        val md5    = Files.createFile(Paths.get(s"${file.getFileName.toString}.md5"))
        val sha1   = Files.createFile(Paths.get(s"${file.getFileName.toString}.sha1"))
        Files.write(md5, toMd5(toHash).getBytes(StandardCharsets.UTF_8))
        Files.write(sha1, toSha1(toHash).getBytes(StandardCharsets.UTF_8))
        List(file, md5, sha1)
      })
      .flatMap(file => {
        val signed = Paths.get(s"${file.getFileName.toString}.asc")
        sign(file, signed)
        List(file, signed)
      })
      .map(file => new Path(file.getFileName.toString))

  override def scanDirectory(dir: File, zfiles: util.List[ZFile]): Int = {
    val processedFiles = processFiles(filesPaths)
    val files = processedFiles.map { path =>
      {
        createZFile(path)
      }
    }.asJava

    zfiles.addAll(files)
    0
  }

  private def toSha1(toHash: Array[Byte]) = toHexS("%040x", "SHA-1", toHash)

  private def toMd5(toHash: Array[Byte]) = toHexS("%032x", "MD5", toHash)

  private def toHexS(fmt: String, algorithm: String, toHash: Array[Byte]) = try {
    val digest = MessageDigest.getInstance(algorithm)
    digest.update(toHash)
    String.format(fmt, new BigInteger(1, digest.digest))
  } catch {
    case e: NoSuchAlgorithmException =>
      throw new RuntimeException(e)
  }

  @throws[IOException]
  @throws[InterruptedException]
  private def sign(toSign: java.nio.file.Path, signed: java.nio.file.Path): Unit = {
    // Ideally, we'd use BouncyCastle for this, but for now brute force by assuming
    // the gpg binary is on the path

    val gpg = new ProcessBuilder(
      "gpg",
      "--use-agent",
      "--armor",
      "--detach-sign",
      "--batch",
      "--passphrase-fd",
      "0",
      "--no-tty",
      "-o",
      signed.toAbsolutePath.toString,
      toSign.toAbsolutePath.toString
    ).start

    val writer = new BufferedWriter(new OutputStreamWriter(gpg.getOutputStream))
    writer.write(sys.env.getOrElse("PGP_PASSPHRASE", "''"))
    writer.flush()
    writer.close()
    gpg.waitFor
    if (gpg.exitValue != 0) throw new IllegalStateException("Unable to sign: " + toSign)

    // Verify the signature
    val verify = new ProcessBuilder(
      "gpg",
      "--verify",
      "--verbose",
      "--verbose",
      signed.toAbsolutePath.toString,
      toSign.toAbsolutePath.toString
    ).inheritIO.start
    verify.waitFor
    if (verify.exitValue != 0) throw new IllegalStateException("Unable to verify signature of " + toSign)
  }
}
