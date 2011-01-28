// BSD License (http://www.galagosearch.org/license)
package org.galagosearch.core.index;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import org.galagosearch.core.retrieval.query.Node;
import org.galagosearch.core.retrieval.query.NodeType;
import org.galagosearch.core.retrieval.structured.DocumentOrderedScoreIterator;
import org.galagosearch.core.retrieval.structured.IndexIterator;
import org.galagosearch.core.util.CallTable;
import org.galagosearch.tupleflow.DataStream;
import org.galagosearch.tupleflow.Utility;
import org.galagosearch.tupleflow.VByteInput;

/**
 * Retrieves lists of floating point numbers which can be used as document features.
 * 
 * @author trevor
 */
public class SparseFloatListReader implements StructuredIndexPartReader {

  public class Iterator extends DocumentOrderedScoreIterator implements IndexIterator {

    IndexReader.Iterator iterator;
    VByteInput stream;
    int documentCount;
    int index;
    int currentDocument;
    double currentScore;

    public Iterator(IndexReader.Iterator iterator) throws IOException {
      this.iterator = iterator;
      documentCount = 0;
      index = 0;
      load();
    }

    void load() throws IOException {
      if (iterator != null) {
        DataStream buffered = iterator.getValueStream();
        stream = new VByteInput(buffered);
        documentCount = stream.readInt();
        index = -1;
        currentDocument = 0;

        if (documentCount > 0) {
          read();
        }
      }
    }

    void read() throws IOException {
      index += 1;

      if (index < documentCount) {
        currentDocument += stream.readInt();
        currentScore = stream.readFloat();
      }
    }

    public String getRecordString() {
      StringBuilder builder = new StringBuilder();

      builder.append(getKey());
      builder.append(",");
      builder.append(currentDocument);
      builder.append(",");
      builder.append(currentScore);

      return builder.toString();
    }

    public boolean nextRecord() throws IOException {
      read();
      if (!isDone()) {
        return true;
      }
      if (iterator.nextKey()) {
        load();
        return true;
      } else {
        return false;
      }
    }

    public void reset() throws IOException {
      currentDocument = 0;
      currentScore = 0;
      load();
    }

    public boolean skipTo(byte[] key) throws IOException {
      iterator.skipTo(key);
      if (Utility.compare(key, iterator.getKey()) == 0) {
        reset();
        return true;
      }
      return false;
    }

    public int currentCandidate() {
      return currentDocument;
    }

    public String getKey() {
      return Utility.toString(iterator.getKey());
    }

    public byte[] getKeyBytes() {
      return iterator.getKey();
    }

    public boolean nextTerm() throws IOException {
      if (iterator.nextKey()) {
        load();
        return true;
      } else {
        return false;
      }
    }

    public boolean hasMatch(int document) {
      return document == currentDocument;
    }

    public void moveTo(int document) throws IOException {
      while (!isDone() && document > currentDocument) {
        read();
      }
    }

    public void movePast(int document) throws IOException {
      while (!isDone() && document >= currentDocument) {
        read();
      }
    }

    public boolean skipToDocument(int document) throws IOException {
      moveTo(document);
      return this.hasMatch(document);
    }

    public double score(int document, int length) {
      if (currentDocument == document) {
        return currentScore;
      }
      return Double.NEGATIVE_INFINITY;
    }

    public double score() {
      return score(documentToScore, lengthOfDocumentToScore);
    }

    public boolean isDone() {
      return index >= documentCount;
    }

    public long totalCandidates() {
      return documentCount;
    }
  }
  IndexReader reader;

  public SparseFloatListReader(String pathname) throws FileNotFoundException, IOException {
    reader = new IndexReader(pathname);
  }

  public Iterator getIterator() throws IOException {
    return new Iterator(reader.getIterator());
  }

  public Iterator getScores(String term) throws IOException {
    IndexReader.Iterator iterator = reader.getIterator(Utility.fromString(term));
    return new Iterator(iterator);
  }

  public void close() throws IOException {
    reader.close();
  }

  public Map<String, NodeType> getNodeTypes() {
    HashMap<String, NodeType> nodeTypes = new HashMap<String, NodeType>();
    nodeTypes.put("scores", new NodeType(Iterator.class));
    return nodeTypes;
  }

  public IndexIterator getIterator(Node node) throws IOException {
    if (node.getOperator().equals("scores")) {
      return getScores(node.getDefaultParameter());
    } else {
      throw new UnsupportedOperationException(
              "Index doesn't support operator: " + node.getOperator());
    }
  }
}
