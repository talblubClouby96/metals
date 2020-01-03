package scala.meta.internal.semver

object SemVer {

  def isCompatibleVersion(minimumVersion: String, version: String): Boolean = {

    def splitVersion(v: String) =
      v.replaceAll("(-|\\+).+$", "").split('.')

    (splitVersion(minimumVersion), splitVersion(version)) match {
      case (Array(minMajor, minMinor, minPatch), Array(major, minor, patch)) =>
        (major > minMajor) ||
          (major == minMajor && minor > minMinor) ||
          (major == minMajor && minor == minMinor && patch >= minPatch)
      case _ => false
    }
  }
}
