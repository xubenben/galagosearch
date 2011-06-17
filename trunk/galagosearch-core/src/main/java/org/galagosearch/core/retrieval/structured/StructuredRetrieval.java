// BSD License (http://www.galagosearch.org/license)
package org.galagosearch.core.retrieval.structured;

import org.galagosearch.core.retrieval.featurefactory.CountFeatureFactory;
import org.galagosearch.core.retrieval.featurefactory.BooleanFeatureFactory;
import org.galagosearch.core.retrieval.featurefactory.RankedFeatureFactory;
import org.galagosearch.core.retrieval.featurefactory.FeatureFactory;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.TreeMap;
import org.galagosearch.core.index.DocumentLengthsReader;
import org.galagosearch.core.index.NameReader;
import org.galagosearch.core.index.PositionIndexReader;
import org.galagosearch.core.index.StructuredIndex;
import org.galagosearch.core.retrieval.query.Node;
import org.galagosearch.core.retrieval.query.StructuredQuery;
import org.galagosearch.core.retrieval.Retrieval;
import org.galagosearch.core.retrieval.ScoredDocument;
import org.galagosearch.core.retrieval.query.NodeType;
import org.galagosearch.core.retrieval.query.SimpleQuery;
import org.galagosearch.core.retrieval.query.Traversal;
import org.galagosearch.core.util.CallTable;
import org.galagosearch.tupleflow.Parameters;
import org.galagosearch.tupleflow.Utility;

/**
 * 10/7/2010 - Modified for asynchronous execution
 *
 * @author trevor
 * @author irmarc
 */
public class StructuredRetrieval implements Retrieval {

  protected String indexId;
  protected StructuredIndex index;
  // feature factories for each type of query
  protected FeatureFactory booleanFeatureFactory;
  protected FeatureFactory countFeatureFactory;
  protected FeatureFactory rankedFeatureFactory;
  // these allow asynchronous evaluation
  protected Thread runner;
  protected Node query;
  protected Parameters queryParams;
  protected List<ScoredDocument> queryResults;
  protected List<String> errors;

  public StructuredRetrieval(StructuredIndex index, Parameters factoryParameters) throws IOException {
    this.index = index;

    Parameters featureParameters = factoryParameters.clone();
    Parameters indexStats = getRetrievalStatistics("all");
    featureParameters.add("collectionLength", indexStats.get("collectionLength"));
    featureParameters.add("documentCount", indexStats.get("documentCount"));
    featureParameters.add("retrievalGroup", "all"); // the value wont matter here


    Parameters bfp = featureParameters.clone();
    bfp.set("queryType", "boolean");
    booleanFeatureFactory = new BooleanFeatureFactory(bfp);
    
    Parameters cfp = featureParameters.clone();
    cfp.set("queryType", "count");
    countFeatureFactory = new CountFeatureFactory(cfp);

    Parameters rfp = featureParameters.clone();
    rfp.set("queryType", "ranked");
    rankedFeatureFactory = new RankedFeatureFactory(rfp);
    
    runner = null;
  }

  public StructuredRetrieval(String filename, Parameters parameters)
          throws FileNotFoundException, IOException {
    this(new StructuredIndex(filename), parameters);
  }

  public void close() throws IOException {
    index.close();
  }

  /*
   * <parameters>
   *  <collectionLength>cl<collectionLength>
   *  <documentCount>dc<documentCount>
   * </parameters>
   */
  public Parameters getRetrievalStatistics(String _retGroup) throws IOException {
    return getRetrievalStatistics();
  }

  public Parameters getRetrievalStatistics() throws IOException {
    Parameters p = new Parameters();
    p.add("collectionLength", Long.toString(index.getCollectionLength()));
    p.add("documentCount", Long.toString(index.getDocumentCount()));

    for (String part : index.getPartNames()) {
      p.copy(index.getPartStatistics(part));
    }

    return p;
  }
  /*
   * <parameters>
   *  <part>
   *   (partName)+
   *  </part>
   *  <nodeType>
   *   <(partName)_(nodeType)>(class)</(partName)_(nodeType)>
   *  </nodeType>
   * </parameters>
   */

  public Parameters getAvailableParts(String _retGroup) throws IOException {
    Parameters p = new Parameters();
    for (String partName : index.getPartNames()) {
      p.add("part", partName);

      Map<String, NodeType> nodeTypes = index.getPartNodeTypes(partName);
      for (String nodeType : nodeTypes.keySet()) {
        p.add("nodeType/" + partName + "/" + nodeType, nodeTypes.get(nodeType).getIteratorClass().getName());
      }
    }
    return p;
  }

  public ScoredDocument[] runBooleanQuery(Node queryTree, Parameters parameters) throws Exception {
    // Give it a context
    DocumentContext context = new DocumentContext();

    // construct the query iterators
    AbstractIndicator iterator = (AbstractIndicator) createIterator(queryTree, context, booleanFeatureFactory);
    ArrayList<ScoredDocument> list = new ArrayList<ScoredDocument>();
    while (!iterator.isDone()) {
      if (iterator.getStatus()) {
        list.add(new ScoredDocument(iterator.currentCandidate(), 1.0));
      }
      iterator.next();
    }
    return list.toArray(new ScoredDocument[0]);
  }

  /**
   * Evaluates a probabilistic query using document-at-a-time evaluation.
   *
   * @param query A query tree that has been already transformed with StructuredRetrieval.transformRankedQuery.
   * @param parameters - query parameters (indexId, # requested, query type, transform)
   * @return
   * @throws java.lang.Exception
   */
  public ScoredDocument[] runRankedQuery(Node queryTree, Parameters parameters) throws Exception {

    long start = System.currentTimeMillis();

    // Give it a context
    DocumentContext context = ContextFactory.createContext(parameters);

    // construct the query iterators
    ScoreValueIterator iterator = (ScoreValueIterator) createIterator(queryTree, context, rankedFeatureFactory);
    int requested = (int) parameters.get("requested", 1000);
    System.err.printf("Running ranked query %s\n", queryTree.toString());

    // now there should be an iterator at the root of this tree
    PriorityQueue<ScoredDocument> queue = new PriorityQueue<ScoredDocument>();
    DocumentLengthsReader.KeyIterator lengthsIterator = index.getLengthsIterator();
    while (!iterator.isDone()) {
      int document = iterator.currentCandidate();
      if (iterator.hasMatch(document)) {
        lengthsIterator.skipToKey(document);
        int length = lengthsIterator.getCurrentLength();
        // This context is shared among all scorers
        context.document = document;
        context.length = length;
        double score = iterator.score();
        CallTable.increment("scored");
        if (queue.size() <= requested || queue.peek().score < score) {
          ScoredDocument scoredDocument = new ScoredDocument(document, score);
          queue.add(scoredDocument);
          if (queue.size() > requested) {
            queue.poll();
          }
        }
      }
      iterator.next();
    }
    long runtime = System.currentTimeMillis() - start;
    CallTable.increment("realtime", runtime);
    String indexId = parameters.get("indexId", "0");
    return getArrayResults(queue, indexId);
  }

  /**
   *
   * @param query - query to be evaluated
   * @param parameters - query parameters (indexId, # requested, query type, transform, retrievalGroup)
   * @param queryResults - object that will contain the results
   * @throws Exception
   */
  public void runAsynchronousQuery(Node query, Parameters parameters, List<ScoredDocument> queryResults, List<String> errors) throws Exception {
    this.query = query;
    this.queryParams = parameters;
    this.queryResults = queryResults;
    this.errors = errors;

    System.err.println();

    runner = new Thread(this);
    runner.start();
  }

  public void waitForAsynchronousQuery() throws InterruptedException {
    this.join();
  }

  // Finish and clean up
  public void join() throws InterruptedException {
    if (runner != null) {
      runner.join();
    }
    query = null;
    runner = null;
  }

  public void run() {
    // we haven't got a query to run - return
    if (query == null) {
      return;
    }

    try {
      // use the query parameters to determine the type of query we're running
      String runType = queryParams.get("querytype", "ranked");
      ScoredDocument[] results;
      if (runType.equals("boolean")) {
        results = runBooleanQuery(query, queryParams);
      } else {
        results = runRankedQuery(query, queryParams);
      }

      // Now add it to the output structure, but synchronously
      synchronized (queryResults) {
        queryResults.addAll(Arrays.asList(results));
      }
    } catch (Exception e) {
      // TODO: use logger here
      System.err.println("StructuredRetrieval ERROR RETRIEVING: " + e);
      e.printStackTrace(System.err);
      synchronized (errors) {
        errors.add(e.toString());
      }
    }
  }

  /*
   * getArrayResults annotates a queue of scored documents
   * returns an array
   *
   */
  protected ScoredDocument[] getArrayResults(PriorityQueue<ScoredDocument> scores, String indexId) throws IOException {
    ScoredDocument[] results = new ScoredDocument[scores.size()];

    TreeMap<Integer, Integer> docIds = new TreeMap();

    for (int i = scores.size() - 1; i >= 0; i--) {
      results[i] = scores.poll();
      results[i].source = indexId;
      results[i].rank = i + 1;
      //results[i].documentName = getName(results[i].intID);
      docIds.put(results[i].document, i);
    }

    NameReader.Iterator iterator = index.getNamesIterator();
    for (int document : docIds.keySet()) {
      iterator.findKey(Utility.fromInt(document));
      String name = iterator.getValueString();
      results[docIds.get(document)].documentName = name;
    }

    return results;
  }

  protected String getDocumentName(int document) throws IOException {
    return index.getName(document);
  }

  protected Node parseQuery(String query, Parameters parameters) {
    String queryType = parameters.get("queryType", "complex");

    if (queryType.equals("simple")) {
      return SimpleQuery.parseTree(query);
    }

    return StructuredQuery.parse(query);
  }

  protected StructuredIterator createIterator(Node node, DocumentContext context, FeatureFactory ff) throws Exception {
    HashMap<String, StructuredIterator> iteratorCache = new HashMap();
    return createNodeMergedIterator(node, context, iteratorCache, ff);
  }

  protected StructuredIterator createNodeMergedIterator(Node node, DocumentContext context,
          HashMap<String, StructuredIterator> iteratorCache, FeatureFactory ff)
          throws Exception {
    ArrayList<StructuredIterator> internalIterators = new ArrayList<StructuredIterator>();
    StructuredIterator iterator;

    // first check if the cache contains this node
    if (iteratorCache.containsKey(node.toString())) {
      return iteratorCache.get(node.toString());
    }

    try {
      for (Node internalNode : node.getInternalNodes()) {
        StructuredIterator internalIterator = createNodeMergedIterator(internalNode, context, iteratorCache, ff);
        internalIterators.add(internalIterator);
      }

      iterator = index.getIterator(node);
      if (iterator == null) {
        iterator = ff.getIterator(node, internalIterators);
      }
    } catch (Exception e) {
      throw e;
    }
    if (ContextualIterator.class.isInstance(iterator) && (context != null)) {
      ((ContextualIterator) iterator).setContext(context);
    }

    // we've created a new iterator - add to the cache for future nodes
    iteratorCache.put(node.toString(), iterator);
    //System.err.printf("node %s = iterator %s\n", node.toString(), iterator.toString());
    return iterator;
  }

  public Node transformBooleanQuery(Node queryTree, String retrievalGroup) throws Exception {
    return transformQuery(booleanFeatureFactory.getTraversals(this), queryTree, retrievalGroup);
  }

  public Node transformCountQuery(Node queryTree, String retrievalGroup) throws Exception {
    return transformQuery(countFeatureFactory.getTraversals(this), queryTree, retrievalGroup);
  }

  public Node transformRankedQuery(Node queryTree, String retrievalGroup) throws Exception {
    return transformQuery(rankedFeatureFactory.getTraversals(this), queryTree, retrievalGroup);
  }

  private Node transformQuery( List<Traversal> traversals, Node queryTree, String retrievalGroup ) throws Exception {
    for (Traversal traversal : traversals) {
      queryTree = StructuredQuery.copy(traversal, queryTree);
    }
    return queryTree;
  }
  
  /**
   * Returns the number of occurrences of the provided
   * expression. If the expression does not produce a CountIterator
   * as a node type, throws an IllegalArgumentException, since it's not
   * an appropriate input. #text, #ow, and #uw should definitely be ok here.
   *
   * @param nodeString
   * @return Number of times the expression occurs.
   * @throws Exception
   */
  public long xCount(String nodeString) throws Exception {

    // first parse the node
    Node root = StructuredQuery.parse(nodeString);
    return xCount(root);
  }

  public long xCount(Node root) throws Exception {

    System.err.printf("Running xcount: %s\n", root.toString());

    NodeCountAggregator agg = new NodeCountAggregator(root);
    return agg.termCount();
  }

  public long docCount(String nodeString) throws Exception {

    // first parse the node
    Node root = StructuredQuery.parse(nodeString);
    return docCount(root);
  }

  public long docCount(Node root) throws Exception {

    System.err.printf("Running doccount: %s\n", root.toString());

    NodeCountAggregator agg = new NodeCountAggregator(root);
    return agg.documentCount();
  }

  public NodeType getNodeType(Node node, String retrievalGroup) throws Exception {
    NodeType nodeType = index.getNodeType(node);
    if (nodeType == null) {
      nodeType = rankedFeatureFactory.getNodeType(node);
    }
    return nodeType;
  }

  /**
   * Subclass that counts the number of occurrences of the provided
   * expression. If the expression does not produce a CountIterator
   * as a node type, throws an IllegalArgumentException, since it's not
   * an appropriate input. #text, #ow, and #uw should be ok here.
   *
   * both term and document counts are maintained.
   *
   * author sjh
   *
   */
  public class NodeCountAggregator {

    private long docCount;
    private long termCount;

    public NodeCountAggregator(Node root) throws IOException, Exception {
      docCount = 0;
      termCount = 0;

      StructuredIterator structIterator = createIterator(root, null, countFeatureFactory);
      if (PositionIndexReader.AggregateIterator.class.isInstance(structIterator)) {
        docCount = ((PositionIndexReader.AggregateIterator) structIterator).totalEntries();
        termCount = ((PositionIndexReader.AggregateIterator) structIterator).totalPositions();
      } else if (structIterator instanceof CountIterator) {
        CountValueIterator iterator = (CountValueIterator) structIterator;
        while (!iterator.isDone()) {
          if (iterator.hasMatch(iterator.currentCandidate())) {
            termCount += iterator.count();
            docCount++;
          }
          iterator.next();
        }
      } else {
        throw new IllegalArgumentException("Node " + root.toString() + " did not return a counting iterator.");
      }
    }

    public long documentCount() throws IOException {
      return docCount;
    }

    public long termCount() throws IOException {
      return termCount;
    }
  }
}
