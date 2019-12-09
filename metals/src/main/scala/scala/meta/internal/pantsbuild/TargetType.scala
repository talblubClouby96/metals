package scala.meta.internal.pantsbuild

case class TargetType(value: String) {
  def isTest: Boolean = value == "TEST"
  def isTestResource: Boolean = value == "TEST_RESOURCE"
  def isResource: Boolean = value == "RESOURCE"
  def isAnyResource: Boolean = isResource || isTest
  def isSource: Boolean = value == "SOURCE"
}
