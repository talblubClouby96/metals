package scala.meta.internal.metals

import com.google.common.hash.BloomFilter
import scala.meta.internal.metals.WorkspaceSymbolQuery.AlternativeQuery
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolInformation.Kind

/**
 * A query for workspace/symbol.
 *
 * @param query the user query itself.
 * @param alternatives all query alternatives for this query. For non-lowercase queries
 *                     this list has always length 1 but for all-lowercase queries we
 *                     have combinations of query with guesses for which characters in the query
 *                     should be capitalized.
 */
case class WorkspaceSymbolQuery(
    query: String,
    alternatives: Array[AlternativeQuery],
    isTrailingDot: Boolean
) {
  def matches(bloom: BloomFilter[CharSequence]): Boolean =
    alternatives.exists(_.matches(bloom))
  def matches(symbol: CharSequence): Boolean =
    alternatives.exists(_.matches(symbol, isTrailingDot))
  def matches(info: SymbolInformation): Boolean = {
    WorkspaceSymbolQuery.isRelevantKind(info.kind) &&
    this.matches(info.symbol)
  }
}

object WorkspaceSymbolQuery {
  def exact(query: String): WorkspaceSymbolQuery = {
    WorkspaceSymbolQuery(
      query,
      Array(AlternativeQuery(query)),
      isTrailingDot = false
    )
  }
  def fromTextQuery(query: String): WorkspaceSymbolQuery = {
    val isTrailingDot = query.endsWith(".")
    val actualQuery =
      if (isTrailingDot) query.stripSuffix(".")
      else query
    WorkspaceSymbolQuery(
      actualQuery,
      AlternativeQuery.all(actualQuery),
      isTrailingDot
    )
  }

  case class AlternativeQuery(
      query: String,
      bloomFilterQueries: Array[CharSequence]
  ) {
    def matches(bloom: BloomFilter[CharSequence]): Boolean =
      bloomFilterQueries.forall(bloom.mightContain)
    def matches(symbol: CharSequence, isTrailingDot: Boolean): Boolean =
      Fuzzy.matches(query, symbol, if (isTrailingDot) 1 else 0)
  }

  object AlternativeQuery {
    def apply(query: String): AlternativeQuery = {
      AlternativeQuery(
        query,
        Fuzzy.bloomFilterQueryStrings(query).toArray
      )
    }
    def all(query: String): Array[AlternativeQuery] = {
      val isAllLowercase = query.forall(_.isLower)
      if (isAllLowercase) {
        // We special handle lowercase queries by guessing alternative capitalized queries.
        // Benchmark in akka/akka show that we pay a manageable performance overhead from this:
        // - "actorref" with guessed capitalization responds in 270ms.
        // - "ActorRef" with 0 guesses responds in 190ms.
        val buf = Array.newBuilder[AlternativeQuery]
        // First, test the exact lowercase query.
        buf += AlternativeQuery(query)
        // Second, uppercase all characters, this makes "fsmp" match "FiniteStateMachineProvider".
        buf += AlternativeQuery(query.toUpperCase)
        // Third, uppercase only the first character, this makes "files" match "Files".
        buf += AlternativeQuery(query.capitalize)
        // Fourth, uppercase the first character and up to two other characters, this makes "actorref" match "ActorRef"
        // and also "wosypro" match "WorkspaceSymbolProvider".
        buf ++= TrigramSubstrings
          .uppercased(query)
          .map(AlternativeQuery(_))
        buf.result()
      } else {
        Array(AlternativeQuery(query))
      }
    }
  }
  def isRelevantKind(kind: Kind): Boolean = {
    kind match {
      case Kind.OBJECT | Kind.PACKAGE_OBJECT | Kind.CLASS | Kind.TRAIT |
          Kind.INTERFACE =>
        true
      case _ =>
        false
    }
  }
}
