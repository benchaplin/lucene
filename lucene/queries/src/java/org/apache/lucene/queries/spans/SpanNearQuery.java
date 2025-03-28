/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.queries.spans;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermStates;
import org.apache.lucene.index.Terms;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.Weight;

/**
 * Matches spans which are near one another. One can specify <i>slop</i>, the maximum number of
 * intervening unmatched positions, as well as whether matches are required to be in-order.
 */
public class SpanNearQuery extends SpanQuery implements Cloneable {

  /** A builder for SpanNearQueries */
  public static class Builder {
    private final boolean ordered;
    private final String field;
    private final List<SpanQuery> clauses = new LinkedList<>();
    private int slop;

    /**
     * Construct a new builder
     *
     * @param field the field to search in
     * @param ordered whether or not clauses must be in-order to match
     */
    public Builder(String field, boolean ordered) {
      this.field = field;
      this.ordered = ordered;
    }

    /** Add a new clause */
    public Builder addClause(SpanQuery clause) {
      if (Objects.equals(clause.getField(), field) == false)
        throw new IllegalArgumentException(
            "Cannot add clause " + clause + " to SpanNearQuery for field " + field);
      this.clauses.add(clause);
      return this;
    }

    /** Add a gap after the previous clause of a defined width */
    public Builder addGap(int width) {
      if (!ordered)
        throw new IllegalArgumentException("Gaps can only be added to ordered near queries");
      this.clauses.add(new SpanGapQuery(field, width));
      return this;
    }

    /** Set the slop for this query */
    public Builder setSlop(int slop) {
      this.slop = slop;
      return this;
    }

    /** Build the query */
    public SpanNearQuery build() {
      return new SpanNearQuery(clauses.toArray(new SpanQuery[clauses.size()]), slop, ordered);
    }
  }

  /** Returns a {@link Builder} for an ordered query on a particular field */
  public static Builder newOrderedNearQuery(String field) {
    return new Builder(field, true);
  }

  /** Returns a {@link Builder} for an unordered query on a particular field */
  public static Builder newUnorderedNearQuery(String field) {
    return new Builder(field, false);
  }

  protected List<SpanQuery> clauses;
  protected int slop;
  protected boolean inOrder;

  protected String field;

  /**
   * Construct a SpanNearQuery. Matches spans matching a span from each clause, with up to <code>
   * slop</code> total unmatched positions between them. <br>
   * When <code>inOrder</code> is true, the spans from each clause must be in the same order as in
   * <code>clauses</code> and must be non-overlapping. <br>
   * When <code>inOrder</code> is false, the spans from each clause need not be ordered and may
   * overlap.
   *
   * @param clausesIn the clauses to find near each other, in the same field, at least 2.
   * @param slop The slop value
   * @param inOrder true if order is important
   */
  public SpanNearQuery(SpanQuery[] clausesIn, int slop, boolean inOrder) {
    this.clauses = new ArrayList<>(clausesIn.length);
    for (SpanQuery clause : clausesIn) {
      if (this.field == null) { // check field
        this.field = clause.getField();
      } else if (clause.getField() != null && !clause.getField().equals(field)) {
        throw new IllegalArgumentException("Clauses must have same field.");
      }
      this.clauses.add(clause);
    }
    this.slop = slop;
    this.inOrder = inOrder;
  }

  /** Return the clauses whose spans are matched. */
  public SpanQuery[] getClauses() {
    return clauses.toArray(new SpanQuery[clauses.size()]);
  }

  /** Return the maximum number of intervening unmatched positions permitted. */
  public int getSlop() {
    return slop;
  }

  /** Return true if matches are required to be in-order. */
  public boolean isInOrder() {
    return inOrder;
  }

  @Override
  public String getField() {
    return field;
  }

  @Override
  public String toString(String field) {
    StringBuilder buffer = new StringBuilder();
    buffer.append("spanNear([");
    Iterator<SpanQuery> i = clauses.iterator();
    while (i.hasNext()) {
      SpanQuery clause = i.next();
      buffer.append(clause.toString(field));
      if (i.hasNext()) {
        buffer.append(", ");
      }
    }
    buffer.append("], ");
    buffer.append(slop);
    buffer.append(", ");
    buffer.append(inOrder);
    buffer.append(")");
    return buffer.toString();
  }

  @Override
  public SpanWeight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)
      throws IOException {
    List<SpanWeight> subWeights = new ArrayList<>();
    for (SpanQuery q : clauses) {
      subWeights.add(q.createWeight(searcher, scoreMode, boost));
    }
    return new SpanNearWeight(
        subWeights, searcher, scoreMode.needsScores() ? getTermStates(subWeights) : null, boost);
  }

  /**
   * Creates SpanNearQuery scorer instances
   *
   * @lucene.internal
   */
  public class SpanNearWeight extends SpanWeight {

    final List<SpanWeight> subWeights;

    public SpanNearWeight(
        List<SpanWeight> subWeights,
        IndexSearcher searcher,
        Map<Term, TermStates> terms,
        float boost)
        throws IOException {
      super(SpanNearQuery.this, searcher, terms, boost);
      this.subWeights = subWeights;
    }

    @Override
    public void extractTermStates(Map<Term, TermStates> contexts) {
      for (SpanWeight w : subWeights) {
        w.extractTermStates(contexts);
      }
    }

    @Override
    public Spans getSpans(final LeafReaderContext context, Postings requiredPostings)
        throws IOException {

      Terms terms = context.reader().terms(field);
      if (terms == null) {
        return null; // field does not exist
      }

      ArrayList<Spans> subSpans = new ArrayList<>(clauses.size());
      for (SpanWeight w : subWeights) {
        Spans subSpan = w.getSpans(context, requiredPostings);
        if (subSpan != null) {
          subSpans.add(subSpan);
        } else {
          return null; // all required
        }
      }

      // all NearSpans require at least two subSpans
      return (!inOrder)
          ? new NearSpansUnordered(slop, subSpans)
          : new NearSpansOrdered(slop, subSpans);
    }

    @Override
    public boolean isCacheable(LeafReaderContext ctx) {
      for (Weight w : subWeights) {
        if (w.isCacheable(ctx) == false) return false;
      }
      return true;
    }

    @Override
    public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
      final Spans spans = getSpans(context, Postings.POSITIONS);
      if (spans == null) {
        return null;
      }
      final var scorer =
          new SpanScorer(spans, getSimScorer(), context.reader().getNormValues(field));
      return new DefaultScorerSupplier(scorer);
    }
  }

  @Override
  public Query rewrite(IndexSearcher indexSearcher) throws IOException {
    boolean actuallyRewritten = false;
    List<SpanQuery> rewrittenClauses = new ArrayList<>();
    for (int i = 0; i < clauses.size(); i++) {
      SpanQuery c = clauses.get(i);
      SpanQuery query = (SpanQuery) c.rewrite(indexSearcher);
      actuallyRewritten |= query != c;
      rewrittenClauses.add(query);
    }
    if (actuallyRewritten) {
      try {
        SpanNearQuery rewritten = (SpanNearQuery) clone();
        rewritten.clauses = rewrittenClauses;
        return rewritten;
      } catch (CloneNotSupportedException e) {
        throw new AssertionError(e);
      }
    }
    return super.rewrite(indexSearcher);
  }

  @Override
  public void visit(QueryVisitor visitor) {
    if (visitor.acceptField(getField()) == false) {
      return;
    }
    QueryVisitor v = visitor.getSubVisitor(BooleanClause.Occur.MUST, this);
    for (SpanQuery clause : clauses) {
      clause.visit(v);
    }
  }

  @Override
  public boolean equals(Object other) {
    return sameClassAs(other) && equalsTo(getClass().cast(other));
  }

  private boolean equalsTo(SpanNearQuery other) {
    return inOrder == other.inOrder && slop == other.slop && clauses.equals(other.clauses);
  }

  @Override
  public int hashCode() {
    int result = classHash();
    result ^= clauses.hashCode();
    result += slop;
    int fac = 1 + (inOrder ? 8 : 4);
    return fac * result;
  }

  private static class SpanGapQuery extends SpanQuery {

    private final String field;
    private final int width;

    public SpanGapQuery(String field, int width) {
      this.field = field;
      this.width = width;
    }

    @Override
    public String getField() {
      return field;
    }

    @Override
    public void visit(QueryVisitor visitor) {
      visitor.visitLeaf(this);
    }

    @Override
    public String toString(String field) {
      return "SpanGap(" + field + ":" + width + ")";
    }

    @Override
    public SpanWeight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)
        throws IOException {
      return new SpanGapWeight(searcher, boost);
    }

    private class SpanGapWeight extends SpanWeight {

      SpanGapWeight(IndexSearcher searcher, float boost) throws IOException {
        super(SpanGapQuery.this, searcher, null, boost);
      }

      @Override
      public void extractTermStates(Map<Term, TermStates> contexts) {}

      @Override
      public Spans getSpans(LeafReaderContext ctx, Postings requiredPostings) throws IOException {
        return new GapSpans(width);
      }

      @Override
      public boolean isCacheable(LeafReaderContext ctx) {
        return true;
      }
    }

    @Override
    public boolean equals(Object other) {
      return sameClassAs(other) && equalsTo(getClass().cast(other));
    }

    private boolean equalsTo(SpanGapQuery other) {
      return width == other.width && field.equals(other.field);
    }

    @Override
    public int hashCode() {
      int result = classHash();
      result -= 7 * width;
      return result * 15 - field.hashCode();
    }
  }

  static class GapSpans extends Spans {

    int doc = -1;
    int pos = -1;
    final int width;

    GapSpans(int width) {
      this.width = width;
    }

    @Override
    public int nextStartPosition() throws IOException {
      return ++pos;
    }

    public int skipToPosition(int position) throws IOException {
      return pos = position;
    }

    @Override
    public int startPosition() {
      return pos;
    }

    @Override
    public int endPosition() {
      return pos + width;
    }

    @Override
    public int width() {
      return width;
    }

    @Override
    public void collect(SpanCollector collector) throws IOException {}

    @Override
    public int docID() {
      return doc;
    }

    @Override
    public int nextDoc() throws IOException {
      pos = -1;
      return ++doc;
    }

    @Override
    public int advance(int target) throws IOException {
      pos = -1;
      return doc = target;
    }

    @Override
    public long cost() {
      return 0;
    }

    @Override
    public float positionsCost() {
      return 0;
    }
  }
}
