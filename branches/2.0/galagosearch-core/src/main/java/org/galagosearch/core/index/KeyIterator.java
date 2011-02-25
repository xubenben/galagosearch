// BSD License (http://www.galagosearch.org/license)

package org.galagosearch.core.index;

import java.io.IOException;
import org.galagosearch.core.retrieval.structured.StructuredIterator;

/**
 * Each iterator from an index has an extra two methods,
 * getValueString() and nextKey(), that allows the data from
 * the index to be easily printed.  DumpIndex uses this functionality
 * to dump the contents of any Galago index.
 *
 * (12/21/2010, irmarc): Added the skipToKey method to allow for better navigation.
 *
 * (2/22/2011, irmarc): Refactored into the index package to indicate this is functionality
 *                      that a disk-based iterator should have.
 *
 * @author trevor, irmarc
 */
public interface KeyIterator extends StructuredIterator {
    boolean skipToKey(byte[] key) throws IOException;
    boolean nextKey() throws IOException;
    String getKey() throws IOException;
    // sjh: i need this method to ensure key order is consistent when merging indexes
    byte[] getKeyBytes() throws IOException;
    String getStringValue();
}