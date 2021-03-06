/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v3_2.commands

import org.neo4j.cypher.internal.compiler.v3_2.ExecutionContext
import org.neo4j.cypher.internal.compiler.v3_2.commands.expressions.{Expression, InequalitySeekRangeExpression, PrefixSeekRangeExpression}
import org.neo4j.cypher.internal.compiler.v3_2.helpers.IsList
import org.neo4j.cypher.internal.compiler.v3_2.mutation.{GraphElementPropertyFunctions, makeValueNeoSafe}
import org.neo4j.cypher.internal.compiler.v3_2.pipes.QueryState
import org.neo4j.cypher.internal.frontend.v3_2.helpers.SeqCombiner.combine
import org.neo4j.cypher.internal.frontend.v3_2.{CypherTypeException, InternalException}
import org.neo4j.graphdb.Node

import scala.collection.GenTraversableOnce

object indexQuery extends GraphElementPropertyFunctions {
  def apply(queryExpression: QueryExpression[Expression],
            m: ExecutionContext,
            state: QueryState,
            index: Seq[Any] => GenTraversableOnce[Node],
            labelName: String,
            propertyNames: Seq[String]): Iterator[Node] = queryExpression match {

    // Index exact value seek on single value
    case SingleQueryExpression(inner) =>
      val value = inner(m)(state)
      lookupNodes(Seq(value), index)

    // Index exact value seek on multiple values, by combining the results of multiple index seeks
    case ManyQueryExpression(inner) =>
      inner(m)(state) match {
        case IsList(coll) => coll.toSet.toIndexedSeq.flatMap {
          value: Any => lookupNodes(Seq(value), index)
        }.iterator
        case null => Iterator.empty
        case _ => throw new CypherTypeException(s"Expected the value for looking up :$labelName(${propertyNames.mkString(",")}) to be a collection but it was not.")
      }

    // Index exact value seek on multiple values, making use of a composite index over all values
    case CompositeQueryExpression(innerExpressions) =>
      assert(innerExpressions.size == propertyNames.size)
      val seekValues = innerExpressions.map(expressionValues(m, state))
      val combined = combine(seekValues)
      val results = combined map { values =>
        lookupNodes(values, index)
      }
      if (results.size == 1)
        results.head
      else
        new IteratorOfIterarors[Node](results)

    // Index range seek over range of values
    case RangeQueryExpression(rangeWrapper) =>
      val range = rangeWrapper match {
        case s: PrefixSeekRangeExpression =>
          s.range.map(expression => makeValueNeoSafe(expression(m)(state)))

        case InequalitySeekRangeExpression(innerRange) =>
          innerRange.mapBounds(expression => makeValueNeoSafe(expression(m)(state)))
      }
      index(Seq(range)).toIterator
  }

  private def lookupNodes(values: Seq[Any], index: Seq[Any] => GenTraversableOnce[Node]): Iterator[Node] = {
    // If any of the values we are searching for is null, the whole expression that this index seek represents
    // collapses into a null value, which will not match any nodes.
    if (values.contains(null))
      Iterator.empty
    else {
      val neoValues: Seq[Any] = values.map(makeValueNeoSafe)
      val index1 = index(neoValues)
      index1.toIterator
    }
  }

  private def expressionValues(m: ExecutionContext, state: QueryState)(queryExpression: QueryExpression[Expression]): Seq[Any] = {
    queryExpression match {

      case SingleQueryExpression(inner) =>
        Seq(inner(m)(state))

      case ManyQueryExpression(inner) =>
        inner(m)(state) match {
          case IsList(coll) => coll.toSeq
          case null => Seq.empty
          case _ => throw new CypherTypeException(s"Expected the value for $inner to be a collection but it was not.")
        }

      case CompositeQueryExpression(innerExpressions) =>
        throw new InternalException("A CompositeQueryExpression can't be nested in a CompositeQueryExpression")

      case RangeQueryExpression(rangeWrapper) =>
        throw new InternalException("Range queries on composite indexes not yet supported")
    }
  }
}

class IteratorOfIterarors[T](inner: Seq[Iterator[T]]) extends Iterator[T] {
  private var _buffer: Option[T] = None
  private var _innerIterator = inner.toIterator
  private var _current: Iterator[T] = Iterator.empty

  fillBuffer()

  override def hasNext: Boolean = _buffer.nonEmpty

  override def next(): T = {
    if (isEmpty) Iterator.empty.next()

    val res = _buffer.get
    fillBuffer()
    res
  }

  private def fillBuffer(): Unit = {

    while (_current.isEmpty && _innerIterator.nonEmpty) {
      _current = _innerIterator.next()
    }

    if (_current.isEmpty)
      _buffer = None
    else
      _buffer = Some(_current.next())
  }
}