package com.dreamwing.serverville.log;

import org.apache.lucene.document.Document;

public interface IndexedLogMessage {
	
	void toLuceneDocument(Document doc);

}
