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
package org.apache.solr.client.solrj.request;

import static org.apache.solr.common.params.CommonParams.CHILDDOC;
import static org.apache.solr.common.util.ByteArrayUtf8CharSequence.convertCharSeq;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.util.CollectionUtil;
import org.apache.solr.common.util.DataInputInputStream;
import org.apache.solr.common.util.JavaBinCodec;
import org.apache.solr.common.util.NamedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides methods for marshalling an UpdateRequest to a NamedList which can be serialized in the
 * javabin format and vice versa.
 *
 * @see org.apache.solr.common.util.JavaBinCodec
 * @since solr 1.4
 */
public class JavaBinUpdateRequestCodec {
  private boolean readStringAsCharSeq = false;

  public JavaBinUpdateRequestCodec setReadStringAsCharSeq(boolean flag) {
    this.readStringAsCharSeq = flag;
    return this;
  }

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final AtomicBoolean WARNED_ABOUT_INDEX_TIME_BOOSTS = new AtomicBoolean();

  /**
   * Converts an UpdateRequest to a NamedList which can be serialized to the given OutputStream in
   * the javabin format
   *
   * @param updateRequest the UpdateRequest to be written out
   * @param os the OutputStream to which the request is to be written
   * @throws IOException in case of an exception during marshalling or writing to the stream
   */
  public void marshal(UpdateRequest updateRequest, OutputStream os) throws IOException {
    NamedList<Object> nl = new NamedList<>();
    NamedList<Object> params = updateRequest.getParams().toNamedList();
    if (updateRequest.getCommitWithin() != -1) {
      params.add("commitWithin", updateRequest.getCommitWithin());
    }
    Iterator<SolrInputDocument> docIter = null;

    if (updateRequest.getDocIterator() != null) {
      docIter = updateRequest.getDocIterator();
    }

    Map<SolrInputDocument, Map<String, Object>> docMap = updateRequest.getDocumentsMap();

    nl.add("params", params); // 0: params
    if (updateRequest.getDeleteByIdMap() != null) {
      nl.add("delByIdMap", updateRequest.getDeleteByIdMap());
    }
    nl.add("delByQ", updateRequest.getDeleteQuery());

    if (docMap != null) {
      nl.add("docsMap", docMap.entrySet().iterator());
    } else {
      if (updateRequest.getDocuments() != null) {
        docIter = updateRequest.getDocuments().iterator();
      }
      nl.add("docs", docIter);
    }
    try (JavaBinCodec codec = new JavaBinCodec()) {
      codec.marshal(nl, os);
    }
  }

  /**
   * Reads a NamedList from the given InputStream, converts it into a SolrInputDocument and passes
   * it to the given StreamingUpdateHandler
   *
   * @param is the InputStream from which to read
   * @param handler an instance of StreamingUpdateHandler to which SolrInputDocuments are streamed
   *     one by one
   * @return the UpdateRequest
   * @throws IOException in case of an exception while reading from the input stream or
   *     unmarshalling
   */
  @SuppressWarnings({"unchecked"})
  public UpdateRequest unmarshal(InputStream is, final StreamingUpdateHandler handler)
      throws IOException {
    final UpdateRequest updateRequest = new UpdateRequest();
    List<List<NamedList<?>>> doclist;
    List<Entry<SolrInputDocument, Map<Object, Object>>> docMap;
    List<String> delById;
    Map<String, Map<String, Object>> delByIdMap;
    List<String> delByQ;
    final NamedList<?>[] namedList = new NamedList<?>[1];
    try (JavaBinCodec codec = new StreamingCodec(namedList, updateRequest, handler)) {
      codec.unmarshal(is);
    }

    // NOTE: if the update request contains only delete commands the params
    // must be loaded now
    if (updateRequest.getParams().iterator().hasNext() == false) { // no params
      NamedList<?> params = (NamedList<?>) namedList[0].get("params");
      if (params != null) {
        updateRequest.setParams(new ModifiableSolrParams(params.toSolrParams()));
      }
    }
    delById = (List<String>) namedList[0].get("delById");
    delByIdMap = (Map<String, Map<String, Object>>) namedList[0].get("delByIdMap");
    delByQ = (List<String>) namedList[0].get("delByQ");
    doclist = (List) namedList[0].get("docs");
    Object docsMapObj = namedList[0].get("docsMap");

    if (docsMapObj instanceof Map) { // SOLR-5762
      docMap = new ArrayList<>(((Map) docsMapObj).entrySet());
    } else {
      docMap = (List<Entry<SolrInputDocument, Map<Object, Object>>>) docsMapObj;
    }

    // we don't add any docs, because they were already processed
    // deletes are handled later, and must be passed back on the UpdateRequest

    if (delById != null) {
      for (String s : delById) {
        updateRequest.deleteById(s);
      }
    }
    if (delByIdMap != null) {
      for (Map.Entry<String, Map<String, Object>> entry : delByIdMap.entrySet()) {
        Map<String, Object> params = entry.getValue();
        if (params != null) {
          Long version = (Long) params.get(UpdateRequest.VER);
          if (params.containsKey(ShardParams._ROUTE_)) {
            updateRequest.deleteById(
                entry.getKey(), (String) params.get(ShardParams._ROUTE_), version);
          } else {
            updateRequest.deleteById(entry.getKey(), version);
          }
        } else {
          updateRequest.deleteById(entry.getKey());
        }
      }
    }
    if (delByQ != null) {
      for (String s : delByQ) {
        updateRequest.deleteByQuery(s);
      }
    }

    return updateRequest;
  }

  public interface StreamingUpdateHandler {
    void update(
        SolrInputDocument document, UpdateRequest req, Integer commitWithin, Boolean override);
  }

  static class MaskCharSequenceSolrInputDoc extends SolrInputDocument {
    public MaskCharSequenceSolrInputDoc(Map<String, SolrInputField> fields) {
      super(fields);
    }

    @Override
    public Object getFieldValue(String name) {
      return convertCharSeq(super.getFieldValue(name));
    }
  }

  class StreamingCodec extends JavaBinCodec {

    // TODO This could probably be an AtomicReference<NamedList<?>>
    private final NamedList<?>[] namedList;
    private final UpdateRequest updateRequest;
    private final StreamingUpdateHandler handler;
    // NOTE: this only works because this is an anonymous inner class
    // which will only ever be used on a single stream -- if this class
    // is ever refactored, this will not work.
    private boolean seenOuterMostDocIterator;

    public StreamingCodec(
        NamedList<?>[] namedList, UpdateRequest updateRequest, StreamingUpdateHandler handler) {
      this.namedList = namedList;
      this.updateRequest = updateRequest;
      this.handler = handler;
      seenOuterMostDocIterator = false;
    }

    @Override
    protected SolrInputDocument createSolrInputDocument(int sz) {
      return new MaskCharSequenceSolrInputDoc(CollectionUtil.newLinkedHashMap(sz));
    }

    @Override
    public NamedList<Object> readNamedList(DataInputInputStream dis) throws IOException {
      int sz = readSize(dis);
      NamedList<Object> nl = new NamedList<>();
      if (namedList[0] == null) {
        namedList[0] = nl;
      }
      for (int i = 0; i < sz; i++) {
        String name = (String) readVal(dis);
        Object val = readVal(dis);
        nl.add(name, val);
      }
      return nl;
    }

    private SolrInputDocument listToSolrInputDocument(List<NamedList<?>> namedList) {
      SolrInputDocument doc = new SolrInputDocument();
      for (int i = 0; i < namedList.size(); i++) {
        NamedList<?> nl = namedList.get(i);
        if (i == 0) {
          Float boost = (Float) nl.getVal(0);
          if (boost != null && boost.floatValue() != 1f) {
            String message =
                "Ignoring document boost: "
                    + boost
                    + " as index-time boosts are not supported anymore";
            if (WARNED_ABOUT_INDEX_TIME_BOOSTS.compareAndSet(false, true)) {
              log.warn(message);
            } else {
              log.debug(message);
            }
          }
        } else {
          Float boost = (Float) nl.getVal(2);
          if (boost != null && boost.floatValue() != 1f) {
            String message =
                "Ignoring field boost: "
                    + boost
                    + " as index-time boosts are not supported anymore";
            if (WARNED_ABOUT_INDEX_TIME_BOOSTS.compareAndSet(false, true)) {
              log.warn(message);
            } else {
              log.debug(message);
            }
          }
          doc.addField((String) nl.getVal(0), nl.getVal(1));
        }
      }
      return doc;
    }

    @Override
    public List<Object> readIterator(DataInputInputStream fis) throws IOException {
      // default behavior for reading any regular Iterator in the stream
      if (seenOuterMostDocIterator) return super.readIterator(fis);

      // special treatment for first outermost Iterator
      // (the list of documents)
      seenOuterMostDocIterator = true;
      return readOuterMostDocIterator(fis);
    }

    private List<Object> readOuterMostDocIterator(DataInputInputStream fis) throws IOException {
      if (namedList[0] == null) namedList[0] = new NamedList<>();
      NamedList<?> params = (NamedList<?>) namedList[0].get("params");
      if (params == null) params = new NamedList<>();
      updateRequest.setParams(new ModifiableSolrParams(params.toSolrParams()));
      if (handler == null) return super.readIterator(fis);
      Integer commitWithin = null;
      Boolean overwrite = null;
      Object o = null;
      super.readStringAsCharSeq = JavaBinUpdateRequestCodec.this.readStringAsCharSeq;
      try {
        while (true) {
          if (o == null) {
            o = readVal(fis);
          }

          if (o == END_OBJ) {
            break;
          }

          SolrInputDocument sdoc = null;
          if (o instanceof List) {
            @SuppressWarnings("unchecked")
            List<NamedList<?>> list = (List<NamedList<?>>) o;
            sdoc = listToSolrInputDocument(list);
          } else if (o instanceof NamedList) {
            UpdateRequest req = new UpdateRequest();
            req.setParams(new ModifiableSolrParams(((NamedList) o).toSolrParams()));
            handler.update(null, req, null, null);
          } else if (o instanceof Map.Entry) {
            @SuppressWarnings("unchecked")
            Map.Entry<SolrInputDocument, Map<?, ?>> entry =
                (Map.Entry<SolrInputDocument, Map<?, ?>>) o;
            sdoc = entry.getKey();
            Map<?, ?> p = entry.getValue();
            if (p != null) {
              commitWithin = (Integer) p.get(UpdateRequest.COMMIT_WITHIN);
              overwrite = (Boolean) p.get(UpdateRequest.OVERWRITE);
            }
          } else if (o instanceof SolrInputDocument) {
            sdoc = (SolrInputDocument) o;
          } else if (o instanceof Map) {
            sdoc = convertMapToSolrInputDoc((Map) o);
          }

          // peek at the next object to see if we're at the end
          o = readVal(fis);
          if (o == END_OBJ) {
            // indicate that we've hit the last doc in the batch, used to enable optimizations when
            // doing replication
            updateRequest.lastDocInBatch();
          }

          handler.update(sdoc, updateRequest, commitWithin, overwrite);
        }
        return Collections.emptyList();
      } finally {
        super.readStringAsCharSeq = false;
      }
    }

    private SolrInputDocument convertMapToSolrInputDoc(Map<?, ?> m) {
      SolrInputDocument result = createSolrInputDocument(m.size());
      m.forEach(
          (k, v) -> {
            if (CHILDDOC.equals(k.toString())) {
              if (v instanceof List<?> list) {
                for (Object o : list) {
                  if (o instanceof Map) {
                    result.addChildDocument(convertMapToSolrInputDoc((Map<?, ?>) o));
                  }
                }
              } else if (v instanceof Map) {
                result.addChildDocument(convertMapToSolrInputDoc((Map<?, ?>) v));
              }
            } else {
              result.addField(k.toString(), v);
            }
          });
      return result;
    }
  }
}
